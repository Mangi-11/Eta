package io.github.mangi.eta.agent.voice.asr

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.agent.voice.audio.SpeechRecorder
import io.github.mangi.eta.agent.voice.audio.SpeechSocket
import io.github.mangi.eta.agent.voice.audio.doubaoHeaders
import io.github.mangi.eta.agent.voice.audio.speechBaseUrl

internal suspend fun recognizeSpeechStream(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    frames: ReceiveChannel<ByteArray>,
    onReady: () -> Unit,
    onPreview: (String) -> Unit,
    onEndpoint: () -> Unit,
    endpointSilenceMs: Int,
): String = withTimeout(90_000) {
    // 服务端在静音达到断句时长后给出确定结果；自动结束时断句时长就是用户可感知的“停顿多久算说完”。
    // 不自动结束时沿用服务默认断句，只影响预览分段，不会提前结束录音。
    val automaticEndpoint = endpointSilenceMs > 0
    val sentenceSilenceMs = if (automaticEndpoint) endpointSilenceMs else DEFAULT_SENTENCE_SILENCE_MS
    if (settings.asr == AsrProvider.DOUBAO) {
        recognizeDoubao(settings, credentials, frames, onReady, onPreview, onEndpoint, automaticEndpoint, sentenceSilenceMs)
    } else recognizeQwen(settings, credentials, frames, onReady, onPreview, onEndpoint, automaticEndpoint, sentenceSilenceMs)
}

private const val DEFAULT_SENTENCE_SILENCE_MS = 800

private suspend fun recognizeQwen(
    settings: SpeechSettings, credentials: SpeechCredentials, frames: ReceiveChannel<ByteArray>,
    onReady: () -> Unit, onPreview: (String) -> Unit, onEndpoint: () -> Unit, automaticEndpoint: Boolean,
    sentenceSilenceMs: Int,
): String = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.qwenAsr.endpoint())
        .replaceFirst("https://", "wss://") + "/api-ws/v1/realtime?model=qwen3-asr-flash-realtime")
        .header("Authorization", "Bearer ${credentials.qwenAsr.trim()}").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        fun event(type: String) = JSONObject().put("type", type).put("event_id", UUID.randomUUID().toString())
        val transcription = JSONObject()
        settings.language.takeIf { it.isNotBlank() }?.let { transcription.put("language", it) }
        socket.text(event("session.update").put("session", JSONObject()
            .put("input_audio_format", "pcm").put("sample_rate", SpeechRecorder.RATE)
            .put("input_audio_transcription", transcription)
            .put("turn_detection", JSONObject().put("type", "server_vad")
                .put("threshold", 0.2).put("silence_duration_ms", sentenceSilenceMs))).toString())
        val transcript = QwenTranscript()
        var sender: kotlinx.coroutines.Job? = null
        var endpointSent = false
        try {
            for (bytes in socket.messages) {
                val message = JSONObject(bytes.toString(Charsets.UTF_8))
                when (message.optString("type")) {
                    "session.updated" -> if (sender == null) {
                        onReady()
                        sender = launch {
                            for (audio in frames) socket.text(event("input_audio_buffer.append")
                                .put("audio", Base64.getEncoder().encodeToString(audio)).toString())
                            socket.text(event("session.finish").toString())
                        }
                    }
                    "conversation.item.input_audio_transcription.text",
                    "conversation.item.input_audio_transcription.completed" -> {
                        onPreview(transcript.accept(message))
                        if (automaticEndpoint && !endpointSent &&
                            message.optString("type").endsWith(".completed") && transcript.result().isNotBlank()) {
                            endpointSent = true
                            onEndpoint()
                        }
                    }
                    "session.finished" -> return@coroutineScope transcript.result()
                    "error", "conversation.item.input_audio_transcription.failed" ->
                        throw SpeechFailure(
                            SpeechErrorCode.SERVER,
                            AppStrings.get(R.string.voice_i18n_qwen_asr_failed, "千问识别失败，请检查模型权限和配置"),
                        )
                }
            }
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_qwen_asr_connection_ended_early, "千问识别连接提前结束"),
            )
        } finally { sender?.cancel() }
    }
}

private suspend fun recognizeDoubao(
    settings: SpeechSettings, credentials: SpeechCredentials, frames: ReceiveChannel<ByteArray>,
    onReady: () -> Unit, onPreview: (String) -> Unit, onEndpoint: () -> Unit, automaticEndpoint: Boolean,
    sentenceSilenceMs: Int,
): String = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.doubaoAsr.baseUrl)
        .replaceFirst("https://", "wss://") + "/api/v3/sauc/bigmodel_async")
        .doubaoHeaders(settings.doubaoAsr, credentials.doubaoAsr, "volc.seedasr.sauc.duration").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        socket.binary(DoubaoAsrCodec.configuration(JSONObject()
            .put("user", JSONObject().put("uid", UUID.randomUUID().toString()))
            .put("audio", JSONObject().put("format", "pcm").put("codec", "raw")
                .put("rate", SpeechRecorder.RATE).put("bits", 16).put("channel", 1))
            .put("request", JSONObject().put("model_name", "bigmodel").put("result_type", "full")
                .put("enable_nonstream", true).put("show_utterances", true)
                .put("end_window_size", sentenceSilenceMs).put("enable_itn", true).put("enable_punc", true))))
        onReady()
        val sender = launch {
            var pending: ByteArray? = null
            for (audio in frames) {
                pending?.let { socket.binary(DoubaoAsrCodec.audio(it, false)) }
                pending = audio
            }
            socket.binary(DoubaoAsrCodec.audio(pending ?: byteArrayOf(), true))
        }
        var text = ""
        var endpointSent = false
        try {
            for (bytes in socket.messages) {
                val message = DoubaoAsrCodec.decode(bytes)
                val result = message.body.optJSONObject("result")
                result?.optString("text")?.let { text = it; onPreview(it) }
                val utterances = result?.optJSONArray("utterances")
                if (automaticEndpoint && !endpointSent && utterances != null &&
                    (0 until utterances.length()).any { utterances.optJSONObject(it)?.optBoolean("definite") == true }) {
                    endpointSent = true
                    onEndpoint()
                }
                if (message.final) return@coroutineScope text.trim()
            }
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_doubao_asr_connection_ended_early, "豆包识别连接提前结束"),
            )
        } finally { sender.cancel() }
    }
}
