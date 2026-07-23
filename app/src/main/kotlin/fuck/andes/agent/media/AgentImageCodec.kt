package fuck.andes.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import fuck.andes.agent.model.AgentModelClient
import java.io.ByteArrayOutputStream
import java.io.File

internal object AgentImageCodec {
    fun fromBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg"
    ): AgentModelClient.ModelImage {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "图片数据过大：${bytes.size}" }
        // 全局发原图：直接 base64 原始 bytes，不 decode+re-encode（零损失），只读尺寸/mime
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val recognizedImage = bytes.hasSupportedImageMagic()
        val mime = opts.outMimeType
            ?.takeIf { recognizedImage && it.isNotBlank() }
            ?: mimeHint.normalizedAgentImageMimeType()
        return AgentModelClient.ModelImage(
            reference = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            mimeType = mime,
            bytes = bytes.size,
            width = opts.outWidth.takeIf { recognizedImage && it > 0 },
            height = opts.outHeight.takeIf { recognizedImage && it > 0 },
            source = source
        )
    }

    /** 用户附件始终保持原始字节、编码和像素尺寸。 */
    fun fromAttachmentBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage = fromBytes(bytes, source, mimeHint)

    /** Root screencap 只允许无损换编码，不改变截图尺寸。 */
    fun fromScreenBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/png",
    ): AgentModelClient.ModelImage =
        AgentModelImageEncoder.screen(bytes, source, mimeHint)
            ?: fromBytes(bytes, source, mimeHint)

    fun fromScreenBitmap(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage = AgentModelImageEncoder.screen(bitmap, source)

    /**
     * 为聊天列表生成独立的小预览。模型仍从 [image.reference] 读取原图，预览不会参与模型输入。
     */
    fun previewFromReference(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = AgentModelImageEncoder.preview(context, image)

    fun fromReference(context: Context?, value: String, source: String): AgentModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return AgentModelClient.ModelImage(
                reference = trimmed,
                mimeType = "image/*",
                bytes = 0,
                source = source
            )
        }
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val markerIndex = trimmed.indexOf("base64,", ignoreCase = true)
            if (markerIndex < 0) return null
            val encoded = trimmed.substring(markerIndex + "base64,".length)
            if (encoded.isBlank() || encoded.length > MAX_AGENT_IMAGE_BYTES * 2) return null
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT).size }.getOrDefault(0)
            if (bytes <= 0 || bytes > MAX_AGENT_IMAGE_BYTES) return null
            return AgentModelClient.ModelImage(
                reference = trimmed,
                mimeType = trimmed.substring("data:".length).substringBefore(";"),
                bytes = bytes,
                source = source
            )
        }

        if (context != null) {
            runCatching {
                val uri = Uri.parse(trimmed)
                if (uri.scheme == "content" || uri.scheme == "file") {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        return fromBytes(input.readBytesLimited(), source)
                    }
                }
            }

            runCatching {
                val file = File(trimmed)
                if (file.isFile) {
                    return fromBytes(file.readBytesLimited(), source)
                }
            }
        }

        return runCatching {
            if (!trimmed.looksLikeBase64()) return@runCatching null
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            if (decoded.hasSupportedImageMagic()) fromBytes(decoded, source) else null
        }.getOrNull()
    }

    /**
     * 为跨进程请求解析图片。远程 URL 与已有 data URL 直接保留；本地 URI/路径只读取元数据，
     * 正文稍后由 [fuck.andes.agent.runtime.AgentRuntimeImageTransfer] 通过文件描述符传输。
     */
    fun fromTransferReference(
        context: Context?,
        value: String,
        source: String,
    ): AgentModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:image/", ignoreCase = true)
        ) {
            return fromReference(context, trimmed, source)
        }
        if (context == null) return fromReference(null, trimmed, source)

        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return inspectContentUri(context, uri, trimmed, source)
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return inspectFile(File(path), trimmed, source)
        }
        val file = File(trimmed)
        if (file.isFile) return inspectFile(file, trimmed, source)
        return fromReference(context, trimmed, source)
    }

    private fun inspectContentUri(
        context: Context,
        uri: Uri,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length
        } ?: -1L
        require(length <= MAX_AGENT_IMAGE_BYTES || length < 0L) { "图片文件过大：$length" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        val mime = options.outMimeType
            ?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: "image/*"
        AgentModelClient.ModelImage(
            reference = reference,
            mimeType = mime,
            bytes = length.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 },
            source = source,
        )
    }.getOrNull()

    private fun inspectFile(
        file: File,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val length = file.length()
        require(file.isFile && length in 1..MAX_AGENT_IMAGE_BYTES.toLong()) { "图片文件不可读或过大" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        AgentModelClient.ModelImage(
            reference = reference,
            mimeType = options.outMimeType?.takeIf { it.isNotBlank() } ?: "image/*",
            bytes = length.toInt(),
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 },
            source = source,
        )
    }.getOrNull()

    private fun File.readBytesLimited(): ByteArray {
        require(length() <= MAX_AGENT_IMAGE_BYTES.toLong()) { "图片文件过大：${length()}" }
        return readBytes()
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_AGENT_IMAGE_BYTES) { "图片数据过大：$total" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun String.looksLikeBase64(): Boolean {
        if (length < 64 || length > MAX_AGENT_IMAGE_BYTES * 2) return false
        return all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' || it == '\r' }
    }
}
