package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.model.ProviderTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_SCREEN_IMAGE_BYTES = 32 * 1024 * 1024
internal const val CHAT_PREVIEW_MAX_LONG_EDGE = 1_920

/**
 * 模型发送前的图片预算约束：将「用户原图大小」与「模型协议上限」解耦。
 *
 * - 使用总像素预算 + 单边硬顶，避免普通照片无谓降采样，也避免手机长截图被压成细条。
 * - 按渠道区分字节预算：Anthropic / Bedrock / Vertex Claude 要求 Base64 <= 5 MB（raw 约 3.75 MB），
 *   取 3.5 MiB 留足协议余量；OpenAI / Gemini 兼容端点取 8 MiB。
 */
internal data class ModelImageBudget(
    val maxEncodedBytes: Int,
    val maxPixels: Long = DEFAULT_MAX_PIXELS,
    val maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
    val initialQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    init {
        require(maxEncodedBytes > 0)
        require(maxPixels > 0L)
        require(maxEdgePx > 0)
        require(initialQuality in 1..100)
    }

    companion object {
        const val DEFAULT_MAX_PIXELS = 8_000_000L
        const val DEFAULT_MAX_EDGE_PX = 7_680
        const val DEFAULT_JPEG_QUALITY = 95
        const val ANTHROPIC_MAX_BYTES = 3_670_016
        const val STANDARD_MAX_BYTES = 8 * 1024 * 1024

        val ANTHROPIC = ModelImageBudget(maxEncodedBytes = ANTHROPIC_MAX_BYTES)
        val STANDARD = ModelImageBudget(maxEncodedBytes = STANDARD_MAX_BYTES)
        val DEFAULT = STANDARD

        fun forProviderType(providerType: String?): ModelImageBudget =
            if (providerType == ProviderTypes.ANTHROPIC) ANTHROPIC else STANDARD
    }
}

/** 负责将任意来源的图片流式解码并编码为符合目标预算的模型图片或聊天预览。 */
internal object AgentModelImageEncoder {
    private const val PREVIEW_JPEG_QUALITY = 88
    private const val PREVIEW_MAX_BYTES = 2 * 1024 * 1024
    private const val PREVIEW_MAX_PIXELS = 1_800_000L
    private const val HEADER_MAGIC_BYTES = 16
    private const val MAX_SHRINK_ROUNDS = 6
    private const val SHRINK_FACTOR = 0.75
    private const val MIN_SHRINK_EDGE_PX = 256
    private const val MIN_EMERGENCY_EDGE_PX = 32

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val previewBudget = ModelImageBudget(
        maxEncodedBytes = PREVIEW_MAX_BYTES,
        maxPixels = PREVIEW_MAX_PIXELS,
        maxEdgePx = CHAT_PREVIEW_MAX_LONG_EDGE,
        initialQuality = PREVIEW_JPEG_QUALITY,
    )

    fun attachment(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = encodeStreamForModel(
        openStream = { ByteArrayInputStream(bytes) },
        source = source,
        mimeHint = mimeHint,
        budget = budget,
    )

    fun encodeFileForModel(
        file: File,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? {
        if (!file.isFile || file.length() <= 0L) return null
        return encodeStreamForModel(
            openStream = { file.inputStream() },
            source = source,
            mimeHint = mimeHint,
            budget = budget,
        )
    }

    /** 针对可 seek 的文件描述符（如跨进程传入的本地原图）直接流式采样解码，避免整图读入堆内存。 */
    fun encodeSeekableDescriptorForModel(
        inputStream: FileInputStream,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = encodeStreamForModel(
        openStream = {
            inputStream.channel.position(0L)
            NonClosingInputStream(inputStream)
        },
        source = source,
        mimeHint = mimeHint,
        budget = budget,
    )

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage {
        // Bitmap 尚无文件编码，仅序列化一次 PNG；不缩放、不铺底、不转换像素。
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        val initialCapacity = minOf(
            bitmap.width * bitmap.height / 4,
            2 * 1024 * 1024,
        ).coerceAtLeast(32 * 1024)
        val output = ByteArrayOutputStream(initialCapacity)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片编码失败" }
        val encoded = output.toByteArray()
        require(encoded.isNotEmpty() && encoded.size <= MAX_SCREEN_IMAGE_BYTES) {
            "图片数据过大：${encoded.size}"
        }
        return AgentModelClient.ModelImage(
            reference = "data:image/png;base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
            mimeType = "image/png",
            bytes = encoded.size,
            width = bitmap.width,
            height = bitmap.height,
            source = source,
            preserveOriginal = true,
        )
    }

    /** 文件工具图片在发送模型前流式采样并按目标渠道预算编码为 JPEG。 */
    fun toolVision(
        file: File,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = runCatching {
        encodeFileForModel(file, source, mimeHint, budget)
    }.getOrNull()

    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? = runCatching {
        attachment(bytes, source, mimeHint, budget)
    }.getOrNull()

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = runCatching {
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val bounds = inspectBounds(openStream, image.mimeType) ?: return@runCatching null
        val width = image.width?.takeIf { it > 0 } ?: bounds.width
        val height = image.height?.takeIf { it > 0 } ?: bounds.height
        val target = targetSize(width, height, previewBudget)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeBitmapWithinBudget(
                bitmap = decoded,
                source = image.source,
                budget = previewBudget,
                flattenAlpha = true,
            )
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()

    internal fun encodeStreamForModel(
        openStream: () -> InputStream,
        source: String,
        mimeHint: String,
        budget: ModelImageBudget,
    ): AgentModelClient.ModelImage? {
        val header = readHeaderMagic(openStream) ?: return null
        if (!header.hasSupportedImageMagic()) return null
        val bounds = inspectBounds(openStream, mimeHint) ?: return null
        val target = targetSize(bounds.width, bounds.height, budget)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        return try {
            encodeBitmapWithinBudget(
                bitmap = decoded,
                source = source,
                budget = budget,
                flattenAlpha = !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun encodeBitmapWithinBudget(
        bitmap: Bitmap,
        source: String,
        budget: ModelImageBudget,
        flattenAlpha: Boolean,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        var current = renderBitmap(
            bitmap = bitmap,
            target = targetSize(bitmap.width, bitmap.height, budget),
            flattenAlpha = flattenAlpha,
        )
        try {
            val initialQualities = listOf(budget.initialQuality, 88, 80, 72, 62, 50)
                .filter { it <= budget.initialQuality }
                .distinct()
            var lastEncoded = compressJpeg(current, initialQualities.first())
            if (lastEncoded.size <= budget.maxEncodedBytes) {
                return buildJpegModelImage(lastEncoded, current.width, current.height, source)
            }
            for (quality in initialQualities.drop(1)) {
                lastEncoded = compressJpeg(current, quality)
                if (lastEncoded.size <= budget.maxEncodedBytes) {
                    return buildJpegModelImage(lastEncoded, current.width, current.height, source)
                }
            }

            // 极端高噪点图像在最低常规质量下仍超字节预算时，逐步等比缩小直至单调收敛。
            val shrinkQualities = listOf(72, 58, 45)
            for (round in 0 until MAX_SHRINK_ROUNDS) {
                val minWidth = minOf(MIN_SHRINK_EDGE_PX, current.width)
                val minHeight = minOf(MIN_SHRINK_EDGE_PX, current.height)
                val nextWidth = (current.width * SHRINK_FACTOR).toInt().coerceAtLeast(minWidth)
                val nextHeight = (current.height * SHRINK_FACTOR).toInt().coerceAtLeast(minHeight)
                if (nextWidth == current.width && nextHeight == current.height) break
                val resized = renderBitmap(current, TargetSize(nextWidth, nextHeight), flattenAlpha = false)
                if (current !== bitmap && !current.isRecycled) current.recycle()
                current = resized
                for (quality in shrinkQualities) {
                    lastEncoded = compressJpeg(current, quality)
                    if (lastEncoded.size <= budget.maxEncodedBytes) {
                        return buildJpegModelImage(lastEncoded, current.width, current.height, source)
                    }
                }
            }

            // 兜底一步缩放，确保在极严苛预算下依然收敛。
            val emergencyRatio = sqrt(budget.maxEncodedBytes.toDouble() / lastEncoded.size.coerceAtLeast(1)) * 0.9
            val emergencyWidth = (current.width * emergencyRatio).toInt()
                .coerceIn(minOf(MIN_EMERGENCY_EDGE_PX, current.width), current.width)
            val emergencyHeight = (current.height * emergencyRatio).toInt()
                .coerceIn(minOf(MIN_EMERGENCY_EDGE_PX, current.height), current.height)
            if (emergencyWidth < current.width || emergencyHeight < current.height) {
                val resized = renderBitmap(current, TargetSize(emergencyWidth, emergencyHeight), flattenAlpha = false)
                if (current !== bitmap && !current.isRecycled) current.recycle()
                current = resized
                lastEncoded = compressJpeg(current, 40)
            }
            return buildJpegModelImage(lastEncoded, current.width, current.height, source)
        } finally {
            if (current !== bitmap && !current.isRecycled) {
                current.recycle()
            }
        }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val initialCapacity = minOf(
            bitmap.width * bitmap.height / 4,
            2 * 1024 * 1024,
        ).coerceAtLeast(32 * 1024)
        val output = ByteArrayOutputStream(initialCapacity)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "图片编码失败" }
        val encoded = output.toByteArray()
        require(encoded.isNotEmpty()) { "图片内容为空" }
        return encoded
    }

    private fun buildJpegModelImage(
        encoded: ByteArray,
        width: Int,
        height: Int,
        source: String,
    ): AgentModelClient.ModelImage = AgentModelClient.ModelImage(
        reference = "data:image/jpeg;base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
        mimeType = "image/jpeg",
        bytes = encoded.size,
        width = width,
        height = height,
        source = source,
    )

    private fun renderBitmap(
        bitmap: Bitmap,
        target: TargetSize,
        flattenAlpha: Boolean,
    ): Bitmap {
        if (
            target.width == bitmap.width &&
            target.height == bitmap.height &&
            !flattenAlpha
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            if (flattenAlpha) canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, target.width, target.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            output.setHasAlpha(false)
        }
    }

    private fun readHeaderMagic(openStream: () -> InputStream): ByteArray? = openStream().use { input ->
        val header = ByteArray(HEADER_MAGIC_BYTES)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read < 0) break
            offset += read
        }
        if (offset == 0) null else header.copyOf(offset)
    }

    private fun inspectBounds(openStream: () -> InputStream, mimeHint: String): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun targetSize(
        width: Int,
        height: Int,
        budget: ModelImageBudget,
    ): TargetSize {
        if (width <= 0 || height <= 0) return TargetSize(width, height)
        var scale = min(1.0, budget.maxEdgePx.toDouble() / maxOf(width, height))
        val scaledPixels = width.toDouble() * height * scale * scale
        if (scaledPixels > budget.maxPixels) {
            scale *= sqrt(budget.maxPixels.toDouble() / scaledPixels)
        }
        return TargetSize(
            width = (width * scale).toInt().coerceIn(1, width),
            height = (height * scale).toInt().coerceIn(1, height),
        )
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        target: TargetSize,
    ): Int {
        var sample = 1
        while (sample <= 64) {
            val next = sample * 2
            val nextWidth = width.toDouble() / next
            val nextHeight = height.toDouble() / next
            if (
                nextWidth < target.width * 0.9 ||
                nextHeight < target.height * 0.9
            ) {
                break
            }
            sample = next
        }
        return sample
    }

    private fun referenceStreamFactory(
        context: Context,
        reference: String,
    ): (() -> InputStream)? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            val decoded = Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            if (decoded.isEmpty() || decoded.size > MAX_SCREEN_IMAGE_BYTES) return null
            return { ByteArrayInputStream(decoded) }
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return {
                context.contentResolver.openInputStream(uri)
                    ?: error("无法打开本地图片")
            }
        }
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null, "" -> File(trimmed)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return { file.inputStream() }
    }

    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() = Unit
    }
}

internal fun String.normalizedAgentImageMimeType(): String =
    substringBefore(';')
        .trim()
        .takeIf {
            it.startsWith("image/", ignoreCase = true) &&
                !it.equals("image/*", ignoreCase = true)
        }
        ?: "image/jpeg"

internal fun ByteArray.hasSupportedImageMagic(): Boolean {
    if (size < 8) return false
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return true
    }
    if (
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    }
    return false
}
