package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

internal data class FileToolTextChunk(
    val text: String,
    val bytesConsumed: Int,
    val eof: Boolean,
)

/** 字节游标只能越过已完整解码并输出成文本的字符，不把解码器内部暂存的半个 UTF-8 字符记为已读。 */
internal class FileToolTextReader(
    private val backend: FileToolBackend,
    private val decoderFactory: () -> CharsetDecoder = {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    },
) {
    fun read(stat: FileToolStat, offset: Long, maxBytes: Int, maxChars: Int = 16_000): FileToolTextChunk {
        val bytes = backend.readBytes(stat.path, offset, maxBytes.coerceIn(1, 16_000))
        if (bytes.any { it == 0.toByte() }) throw FileToolException("BINARY_FILE", "文件包含二进制内容，请使用文件导出或终端工具处理")
        if (bytes.isEmpty() && offset < stat.sizeBytes) throw FileToolException("FILE_CHANGED", "文件在读取期间已变化")
        val eof = offset + bytes.size >= stat.sizeBytes
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(maxChars.coerceIn(2, 16_000))
        val decoder = decoderFactory()
        var result = decoder.decode(input, output, eof)
        if (eof && result.isUnderflow) {
            result = decoder.flush(output)
        }
        if (result.isError) throw FileToolException("NOT_UTF8_TEXT", "内容不是有效 UTF-8 文本，或字节游标位于字符中间")
        output.flip()
        val text = output.toString()
        val consumedBytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes.isNotEmpty() && consumedBytes == 0) {
            throw FileToolException("READ_LIMIT_TOO_SMALL", "字节上限不足以读取一个完整字符，请使用至少 4 字节")
        }
        return FileToolTextChunk(text, consumedBytes, eof && consumedBytes == bytes.size)
    }
}
