package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

internal fun supportsSpeechSession(provider: TtsProvider): Boolean = when (provider) {
    TtsProvider.QWEN, TtsProvider.DOUBAO -> true
    TtsProvider.NONE, TtsProvider.GEMINI, TtsProvider.MINIMAX -> false
}

/**
 * 整篇朗读在一个流式合成会话里完成：文本按句送入同一会话，服务端连续生成音频。
 * 与逐段独立请求相比，语调、音量和语速前后连贯，也没有段与段之间的接缝与停顿抖动。
 * 音频按到达顺序经 [onAudio] 交出；任一阶段失败都以 [SpeechFailure] 结束。
 */
internal suspend fun streamSpeech(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = withTimeout(STREAM_TIMEOUT_MS) {
    when (settings.tts) {
        TtsProvider.QWEN -> streamQwen(settings, credentials, chunks, onAudio)
        TtsProvider.DOUBAO -> streamDoubao(settings, credentials, chunks, onAudio)
        TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先选择语音播报服务")
        TtsProvider.GEMINI, TtsProvider.MINIMAX ->
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "当前语音服务使用逐段流式合成")
    }
}

private suspend fun streamQwen(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.qwenTts.endpoint())
        .replaceFirst("https://", "wss://") + "/api-ws/v1/realtime?model=qwen3-tts-flash-realtime")
        .header("Authorization", "Bearer ${credentials.qwenTts.trim()}").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        fun event(type: String) = JSONObject().put("type", type).put("event_id", UUID.randomUUID().toString())
        // server_commit 由服务端按语义切句，客户端只管追加文本。
        socket.text(event("session.update").put("session", JSONObject()
            .put("mode", "server_commit").put("voice", settings.qwenVoice).put("language_type", "Auto")
            .put("response_format", "pcm").put("sample_rate", SAMPLE_RATE)).toString())
        var audioBytes = 0L
        var sent = false
        for (bytes in socket.messages) {
            val message = JSONObject(bytes.toString(Charsets.UTF_8))
            when (message.optString("type")) {
                "session.updated" -> if (!sent) {
                    sent = true
                    chunks.forEach { socket.text(event("input_text_buffer.append").put("text", it).toString()) }
                    socket.text(event("session.finish").toString())
                }
                "response.audio.delta" -> {
                    val audio = Base64.getDecoder().decode(message.optString("delta"))
                    audioBytes = checkedAudio(audioBytes, audio.size)
                    onAudio(audio)
                }
                "session.finished" -> {
                    if (audioBytes == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频不完整，请重试")
                    return@coroutineScope
                }
                "error" -> throw SpeechFailure(SpeechErrorCode.SERVER, "千问语音合成失败，请检查模型权限和配置")
            }
        }
        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "千问语音合成连接提前结束")
    }
}

private suspend fun streamDoubao(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.doubaoTts.baseUrl)
        .replaceFirst("https://", "wss://") + "/api/v3/tts/bidirection")
        .doubaoHeaders(settings.doubaoTts, credentials.doubaoTts, "seed-tts-2.0").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        val sessionId = UUID.randomUUID().toString()
        val user = JSONObject().put("uid", UUID.randomUUID().toString())
        fun params(text: String? = null) = JSONObject().put("speaker", settings.doubaoVoice)
            .put("audio_params", JSONObject().put("format", "pcm").put("sample_rate", SAMPLE_RATE))
            .apply { text?.let { put("text", it) } }
        fun payload(event: Int, text: String? = null) = JSONObject().put("user", user).put("event", event)
            .put("namespace", "BidirectionalTTS").put("req_params", params(text)).toString()
        val sessionStarted = CompletableDeferred<Unit>()
        // 读取必须与发送并行：音频到达很快，消息通道写满会被当作背压失败断开。
        val sender = launch {
            sessionStarted.await()
            chunks.forEach { socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.TASK_REQUEST, sessionId, payload(DoubaoTtsCodec.TASK_REQUEST, it))) }
            socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.FINISH_SESSION, sessionId, "{}"))
        }
        socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.START_CONNECTION, null, "{}"))
        var audioBytes = 0L
        try {
            for (bytes in socket.messages) {
                val frame = DoubaoTtsCodec.decode(bytes)
                if (frame.errorCode != null) {
                    throw SpeechFailure(SpeechErrorCode.SERVER, "豆包语音合成失败（${frame.errorCode}）")
                }
                when (frame.event) {
                    DoubaoTtsCodec.CONNECTION_STARTED -> socket.binary(DoubaoTtsCodec.encode(
                        DoubaoTtsCodec.START_SESSION, sessionId, payload(DoubaoTtsCodec.START_SESSION),
                    ))
                    DoubaoTtsCodec.SESSION_STARTED -> sessionStarted.complete(Unit)
                    DoubaoTtsCodec.TTS_RESPONSE -> if (frame.audio && frame.payload.isNotEmpty()) {
                        audioBytes = checkedAudio(audioBytes, frame.payload.size)
                        onAudio(frame.payload)
                    }
                    DoubaoTtsCodec.SESSION_FINISHED -> {
                        runCatching { socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.FINISH_CONNECTION, null, "{}")) }
                        if (audioBytes == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频不完整，请重试")
                        return@coroutineScope
                    }
                    DoubaoTtsCodec.CONNECTION_FAILED, DoubaoTtsCodec.SESSION_FAILED ->
                        throw SpeechFailure(SpeechErrorCode.SERVER, "豆包语音合成失败，请检查音色和服务配置")
                }
            }
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "豆包语音合成连接提前结束")
        } finally {
            sender.cancel()
        }
    }
}

private fun checkedAudio(total: Long, added: Int): Long {
    val next = total + added
    if (next > MAX_AUDIO_BYTES) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频格式或长度无效")
    return next
}

private const val SAMPLE_RATE = 24_000
/** 与单段请求相同按 24 kHz/16 位估算上限；整篇只建一次连接，时长按正文上限放宽。 */
private const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2L * 60 * 20
private const val STREAM_TIMEOUT_MS = 20 * 60_000L
