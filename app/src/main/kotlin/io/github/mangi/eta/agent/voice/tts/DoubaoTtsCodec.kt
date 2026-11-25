package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.agent.voice.audio.SpeechHttp

/**
 * 豆包双向流式合成的二进制帧。头部 4 字节：版本/头长、消息类型/标志、序列化/压缩、保留；
 * 带事件标志时依次是事件号、会话 ID（连接级事件改为连接 ID）、负载长度与负载，全部大端。
 */
internal object DoubaoTtsCodec {
    const val START_CONNECTION = 1
    const val FINISH_CONNECTION = 2
    const val CONNECTION_STARTED = 50
    const val CONNECTION_FAILED = 51
    const val CONNECTION_FINISHED = 52
    const val START_SESSION = 100
    const val FINISH_SESSION = 102
    const val SESSION_STARTED = 150
    const val SESSION_FINISHED = 152
    const val SESSION_FAILED = 153
    const val TASK_REQUEST = 200
    const val TTS_RESPONSE = 352

    private const val FULL_CLIENT_REQUEST = 0b0001
    private const val FULL_SERVER_RESPONSE = 0b1001
    private const val AUDIO_ONLY_SERVER = 0b1011
    private const val ERROR = 0b1111
    private const val FLAG_WITH_EVENT = 0b0100
    private const val SERIALIZATION_JSON = 0b0001
    private const val COMPRESSION_GZIP = 0b0001

    class Frame(val event: Int, val audio: Boolean, val payload: ByteArray, val errorCode: Int? = null) {
        fun text(): String = payload.toString(Charsets.UTF_8)
    }

    fun encode(event: Int, sessionId: String?, payload: String): ByteArray {
        val session = sessionId?.toByteArray(Charsets.UTF_8)
        val body = payload.toByteArray(Charsets.UTF_8)
        val size = 4 + 4 + (session?.let { 4 + it.size } ?: 0) + 4 + body.size
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x11.toByte())
            put(((FULL_CLIENT_REQUEST shl 4) or FLAG_WITH_EVENT).toByte())
            put((SERIALIZATION_JSON shl 4).toByte())
            put(0)
            putInt(event)
            session?.let { putInt(it.size); put(it) }
            putInt(body.size)
            put(body)
        }.array()
    }

    fun decode(bytes: ByteArray): Frame {
        fun invalid(): Nothing = throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_doubao_tts_invalid_frame, "豆包语音合成返回了无效协议帧"),
        )
        if (bytes.size < 8) invalid()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        val typeFlags = buffer.get().toInt() and 0xff
        val encoding = buffer.get().toInt() and 0xff
        val headerSize = (version and 15) * 4
        if (version shr 4 != 1 || headerSize < 4 || headerSize > bytes.size) invalid()
        buffer.position(headerSize)
        val type = typeFlags shr 4
        val flags = typeFlags and 15
        fun int(): Int = if (buffer.remaining() >= 4) buffer.int else invalid()
        fun sized(): ByteArray {
            val length = int()
            if (length < 0 || length > buffer.remaining()) invalid()
            return ByteArray(length).also(buffer::get)
        }
        if (type !in setOf(FULL_SERVER_RESPONSE, AUDIO_ONLY_SERVER, ERROR)) invalid()
        if (flags and 0b11 == 0b01 || flags and 0b11 == 0b11) int()
        val errorCode = if (type == ERROR) int() else null
        var event = 0
        if (flags and FLAG_WITH_EVENT != 0) {
            event = int()
            // 连接级事件携带连接 ID，会话级事件携带会话 ID；两者长度前缀相同，都只需跳过。
            if (event != START_CONNECTION && event != FINISH_CONNECTION) sized()
        }
        var payload = sized()
        if (type != AUDIO_ONLY_SERVER && encoding and 15 == COMPRESSION_GZIP) {
            payload = GZIPInputStream(ByteArrayInputStream(payload)).use { gzip ->
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
        }
        return Frame(event, type == AUDIO_ONLY_SERVER, payload, errorCode)
    }
}
