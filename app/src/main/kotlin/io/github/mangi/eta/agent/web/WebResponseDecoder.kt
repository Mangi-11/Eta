package io.github.mangi.eta.agent.web

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.jsoup.Jsoup

internal data class DecodedWebResponse(val text: String, val contentType: String, val charset: String, val lossy: Boolean)

/** 响应头、BOM 与 HTML 编码声明决定解码；下载截断处不把半个字符当正文返回。 */
internal object WebResponseDecoder {
    fun decode(response: WebHttpResponse, additionalMimeTypes: Set<String> = emptySet()): DecodedWebResponse {
        val bytes = response.bytes
        if (bytes.size > WebHttpTransport.MAX_RESPONSE_BYTES) throw WebRequestException("WEB_DECODE_LIMIT", "网页响应超过解码容量限制")
        val bom = when {
            bytes.startsWith(0xef, 0xbb, 0xbf) -> "UTF-8" to 3
            bytes.startsWith(0xfe, 0xff) -> "UTF-16BE" to 2
            bytes.startsWith(0xff, 0xfe) -> "UTF-16LE" to 2
            else -> null
        }
        val offset = bom?.second ?: 0
        // 未声明 MIME 时也先去除 BOM；UTF-16 的标签不能按单字节前缀识别。
        val prefixBytes = bytes.copyOfRange(offset, minOf(bytes.size, offset + 8192))
        val prefixCharset = bom?.first?.let(Charset::forName) ?: Charsets.ISO_8859_1
        val prefix = decodeBytes(prefixBytes, 0, prefixCharset, CodingErrorAction.REPLACE, endOfInput = false)
        val declared = response.contentType?.toMediaTypeOrNull()
        if (declared == null && bom == null && hasBinarySignature(prefixBytes)) {
            throw WebRequestException("BINARY_CONTENT", "响应包含二进制文件内容，请使用对应的文件或媒体工具")
        }
        val mime = declared?.let { "${it.type}/${it.subtype}" }?.lowercase(Locale.ROOT)
            ?: if (HTML_PREFIX.containsMatchIn(prefix)) "text/html" else "text/plain"
        if (!mime.startsWith("text/") && mime !in additionalMimeTypes && mime !in setOf("application/json", "application/xhtml+xml") &&
            !(mime.startsWith("application/") && mime.endsWith("+json"))
        ) throw WebRequestException("UNSUPPORTED_CONTENT_TYPE", "此网址不是可读取的文本网页，请使用对应的文件或媒体工具")
        val headerCharset = declared?.parameter("charset")
        val htmlCharset = if (mime == "text/html" && bom == null && headerCharset == null) {
            val head = Jsoup.parse(prefix)
            head.selectFirst("meta[charset]")?.attr("charset")?.takeIf(String::isNotBlank)
                ?: head.select("meta[http-equiv]").firstOrNull { it.attr("http-equiv").equals("content-type", true) }
                    ?.attr("content")?.toMediaTypeOrNull()?.parameter("charset")
        } else null
        val charset = try {
            Charset.forName(bom?.first ?: headerCharset ?: htmlCharset ?: "UTF-8")
        } catch (_: IllegalArgumentException) {
            throw WebRequestException("UNSUPPORTED_CHARSET", "网页声明了当前环境不支持的字符编码")
        }
        var lossy = false
        val text = try { decodeBytes(bytes, offset, charset, CodingErrorAction.REPORT, !response.truncated) } catch (_: CharacterCodingException) {
            lossy = true
            decodeBytes(bytes, offset, charset, CodingErrorAction.REPLACE, !response.truncated)
        }
        if (text.any(::isBinaryControl)) throw WebRequestException("BINARY_CONTENT", "响应包含二进制控制字符，不能作为网页正文读取")
        return DecodedWebResponse(text, mime, charset.name(), lossy)
    }

    private fun decodeBytes(bytes: ByteArray, offset: Int, charset: Charset, policy: CodingErrorAction, endOfInput: Boolean): String {
        val decoder = charset.newDecoder().onMalformedInput(policy).onUnmappableCharacter(policy)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val output = CharBuffer.allocate(((bytes.size - offset) * decoder.maxCharsPerByte()).toInt() + 2)
        val result = decoder.decode(input, output, endOfInput)
        if (result.isError) result.throwException()
        if (result.isOverflow) throw WebRequestException("WEB_DECODE_LIMIT", "网页解码超过容量限制")
        if (endOfInput) {
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            if (flushed.isOverflow) throw WebRequestException("WEB_DECODE_LIMIT", "网页解码超过容量限制")
        }
        output.flip()
        return output.toString()
    }

    private fun hasBinarySignature(bytes: ByteArray): Boolean =
        bytes.startsWith(0x25, 0x50, 0x44, 0x46, 0x2d) || // PDF
            bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
            bytes.startsWith(0xff, 0xd8, 0xff) ||
            bytes.startsWith(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || bytes.startsWith(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) ||
            bytes.startsWith(0x50, 0x4b, 0x03, 0x04) || bytes.startsWith(0x50, 0x4b, 0x05, 0x06) || bytes.startsWith(0x50, 0x4b, 0x07, 0x08) ||
            bytes.startsWith(0x1f, 0x8b) || bytes.any { isBinaryControl((it.toInt() and 0xff).toChar()) }

    private fun isBinaryControl(character: Char): Boolean =
        character.code in 0..8 || character.code == 11 || character.code in 14..26 || character.code in 28..31
    private fun ByteArray.startsWith(vararg prefix: Int): Boolean = size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }
    private val HTML_PREFIX = Regex("(?is)^\\s*(?:<!doctype\\s+html|<!--|<(?:html|head|body|main|article|script|iframe|h[1-6]|div|font|table|a|style|title|b|br|p)(?=[\\s/>]))")
}
