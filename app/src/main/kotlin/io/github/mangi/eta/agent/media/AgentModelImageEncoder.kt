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
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_AGENT_IMAGE_BYTES = 12 * 1024 * 1024
internal const val CHAT_PREVIEW_MAX_LONG_EDGE = 1_920

/** 仅负责为工具截图和聊天预览生成独立的图片副本。 */
internal object AgentModelImageEncoder {
    private const val MODEL_JPEG_QUALITY = 95
    private const val PREVIEW_JPEG_QUALITY = 88

    private data class EncodingProfile(
        val format: Bitmap.CompressFormat,
        val mimeType: String,
        val quality: Int,
        val maxLongEdge: Int? = null,
        val maxPixels: Long? = null,
    )

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val screenProfile = EncodingProfile(
        format = Bitmap.CompressFormat.PNG,
        mimeType = "image/png",
        quality = 100,
    )
    private val attachmentProfile = EncodingProfile(
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
    )
    // 聊天图片支持点击全屏缩放查看文字细节（如聊天记录与终端长截图），保留高清边长上限。
    private val previewProfile = EncodingProfile(
        maxLongEdge = CHAT_PREVIEW_MAX_LONG_EDGE,
        maxPixels = 1_800_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = PREVIEW_JPEG_QUALITY,
    )
    private val toolVisionProfile = EncodingProfile(
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
    )

    fun attachment(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
    ): AgentModelClient.ModelImage? {
        if (!bytes.hasSupportedImageMagic()) return null
        val bounds = inspectBounds(bytes, mimeHint)
        val encoded = transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = bounds,
            profile = attachmentProfile,
        )
        // 普通附件保持 JPEG 兼容路径；截图不经过这里的解码与重编码。
        return encoded
    }

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        // Bitmap 尚无文件编码，仅序列化一次 PNG；不缩放、不铺底、不转换像素。
        encodeBitmap(bitmap, source, screenProfile, flattenAlpha = false).copy(preserveOriginal = true)

    /** 文件工具图片在发送模型前统一编码为 JPEG。 */
    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage? = runCatching {
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = toolVisionProfile,
        )
    }.getOrNull()

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = runCatching {
        val width = image.width ?: return@runCatching null
        val height = image.height ?: return@runCatching null
        val target = targetSize(width, height, previewProfile)
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeBitmap(decoded, image.source, previewProfile, flattenAlpha = true)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()

    private fun transcodeBytes(
        bytes: ByteArray,
        source: String,
        bounds: ImageBounds,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? {
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val target = bounds.targetSize(profile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            encodeBitmap(
                bitmap = bitmap,
                source = source,
                profile = profile,
                flattenAlpha = profile.format == Bitmap.CompressFormat.JPEG &&
                    !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        source: String,
        profile: EncodingProfile,
        flattenAlpha: Boolean,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        val encodedBitmap = renderBitmap(
            bitmap = bitmap,
            target = targetSize(bitmap.width, bitmap.height, profile),
            flattenAlpha = flattenAlpha,
        )
        try {
            val initialCapacity = minOf(
                encodedBitmap.width * encodedBitmap.height / 4,
                2 * 1024 * 1024,
            ).coerceAtLeast(32 * 1024)
            val output = ByteArrayOutputStream(initialCapacity)
            check(encodedBitmap.compress(profile.format, profile.quality, output)) {
                "图片编码失败"
            }
            val encoded = output.toByteArray()
            require(encoded.isNotEmpty() && encoded.size <= MAX_AGENT_IMAGE_BYTES) {
                "图片数据过大：${encoded.size}"
            }
            return AgentModelClient.ModelImage(
                reference = "data:${profile.mimeType};base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
                mimeType = profile.mimeType,
                bytes = encoded.size,
                width = encodedBitmap.width,
                height = encodedBitmap.height,
                source = source,
            )
        } finally {
            if (encodedBitmap !== bitmap && !encodedBitmap.isRecycled) {
                encodedBitmap.recycle()
            }
        }
    }

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

    private fun inspectBounds(bytes: ByteArray, mimeHint: String): ImageBounds {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "图片数据过大：${bytes.size}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun ImageBounds.targetSize(profile: EncodingProfile): TargetSize =
        targetSize(width, height, profile)

    private fun targetSize(
        width: Int,
        height: Int,
        profile: EncodingProfile,
    ): TargetSize {
        if (width <= 0 || height <= 0) return TargetSize(width, height)
        var scale = profile.maxLongEdge
            ?.let { limit -> min(1.0, limit.toDouble() / maxOf(width, height)) }
            ?: 1.0
        val scaledPixels = width.toDouble() * height * scale * scale
        profile.maxPixels?.let { limit ->
            if (scaledPixels > limit) {
                scale *= sqrt(limit / scaledPixels)
            }
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
            if (decoded.isEmpty() || decoded.size > MAX_AGENT_IMAGE_BYTES) return null
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
