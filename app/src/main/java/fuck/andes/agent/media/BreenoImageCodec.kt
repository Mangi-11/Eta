package fuck.andes.agent.media

import fuck.andes.agent.model.BreenoModelClient
import fuck.andes.core.HookSupport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

internal object BreenoImageCodec {
    private const val MAX_EDGE = 1080
    private const val JPEG_QUALITY = 82
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

    fun fromBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg"
    ): BreenoModelClient.ModelImage {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            val mime = mimeHint.ifBlank { "image/jpeg" }
            return BreenoModelClient.ModelImage(
                dataUrl = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
                mimeType = mime,
                bytes = bytes.size,
                source = source
            )
        }

        val scaled = decoded.scaleDown(MAX_EDGE)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        val width = scaled.width
        val height = scaled.height
        val jpeg = output.toByteArray()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        return BreenoModelClient.ModelImage(
            dataUrl = "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}",
            mimeType = "image/jpeg",
            bytes = jpeg.size,
            width = width,
            height = height,
            source = source
        )
    }

    fun fromReference(context: Context?, value: String, source: String): BreenoModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return BreenoModelClient.ModelImage(
                dataUrl = trimmed,
                mimeType = "image/*",
                bytes = 0,
                source = source
            )
        }
        if (trimmed.startsWith("data:image/")) {
            val bytes = trimmed.substringAfter("base64,", "").let {
                runCatching { Base64.decode(it, Base64.DEFAULT).size }.getOrDefault(0)
            }
            return BreenoModelClient.ModelImage(
                dataUrl = trimmed,
                mimeType = trimmed.substringAfter("data:").substringBefore(";"),
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
            if (decoded.hasImageMagic()) fromBytes(decoded, source) else null
        }.getOrNull()
    }

    private fun Bitmap.scaleDown(maxEdge: Int): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= maxEdge) return this
        val scale = maxEdge.toFloat() / edge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun File.readBytesLimited(): ByteArray {
        require(length() <= MAX_IMAGE_BYTES * 2L) { "图片文件过大：${length()}" }
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
            require(total <= MAX_IMAGE_BYTES * 2) { "图片数据过大：$total" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun String.looksLikeBase64(): Boolean {
        if (length < 64 || length > MAX_IMAGE_BYTES * 3) return false
        return all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' || it == '\r' }
    }

    private fun ByteArray.hasImageMagic(): Boolean =
        size >= 8 && (
            this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() ||
                this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
                this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() ||
                this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
                this[2] == 'F'.code.toByte()
            )
}
