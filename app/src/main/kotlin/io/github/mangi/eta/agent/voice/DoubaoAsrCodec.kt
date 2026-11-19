package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

internal object DoubaoAsrCodec {
    data class Result(val body: JSONObject, val final: Boolean)

    fun configuration(body: JSONObject): ByteArray = encode(1, 0, 1, body.toString().toByteArray(Charsets.UTF_8))
    fun audio(bytes: ByteArray, final: Boolean): ByteArray = encode(2, if (final) 2 else 0, 0, bytes)

    private fun encode(type: Int, flags: Int, serialization: Int, payload: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(payload) } }.toByteArray()
        return ByteBuffer.allocate(8 + compressed.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11.toByte()).put(((type shl 4) or flags).toByte())
            .put(((serialization shl 4) or 1).toByte()).put(0)
            .putInt(compressed.size).put(compressed).array()
    }

    fun decode(bytes: ByteArray): Result {
        fun invalid(): Nothing = throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_doubao_asr_invalid_frame, "豆包识别返回了无效音频协议帧"),
        )
        if (bytes.size < 8 || bytes.size > SpeechHttp.MAX_JSON_BYTES) invalid()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        val typeFlags = buffer.get().toInt() and 0xff
        val encoding = buffer.get().toInt() and 0xff
        val headerSize = (version and 15) * 4
        if (version shr 4 != 1 || headerSize < 4 || headerSize > bytes.size - 4) invalid()
        buffer.position(headerSize)
        val type = typeFlags shr 4
        val flags = typeFlags and 15
        if (type == 15) {
            if (buffer.remaining() < 8) invalid()
            val code = buffer.int
            throw SpeechFailure(
                SpeechErrorCode.SERVER,
                AppStrings.get(R.string.voice_i18n_doubao_asr_failed_code, "豆包识别失败（%1\$d）", code),
            )
        }
        if (type != 9 || encoding shr 4 != 1 || flags !in 0..3) invalid()
        if (flags and 1 != 0) {
            if (buffer.remaining() < 8) invalid()
            buffer.int
        }
        val size = buffer.int
        if (size < 0 || size != buffer.remaining()) invalid()
        var payload = ByteArray(size).also(buffer::get)
        when (encoding and 15) {
            0 -> Unit
            1 -> payload = GZIPInputStream(ByteArrayInputStream(payload)).use { gzip ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val read = gzip.read(chunk)
                    if (read == -1) break
                    if (out.size() + read > SpeechHttp.MAX_JSON_BYTES) invalid()
                    out.write(chunk, 0, read)
                }
                out.toByteArray()
            }
            else -> invalid()
        }
        return Result(JSONObject(payload.toString(Charsets.UTF_8)), flags and 2 != 0)
    }
}
