package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import java.util.Base64
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal suspend fun synthesizeSpeech(settings: SpeechSettings, credentials: SpeechCredentials,
    text: String, onAudio: (ByteArray) -> Unit) = withTimeout(90_000) {
    when (settings.tts) {
        TtsProvider.GEMINI -> return@withTimeout synthesizeGemini(settings.geminiTts, credentials.geminiTts, text, onAudio)
        TtsProvider.MINIMAX -> return@withTimeout synthesizeMiniMax(settings.minimaxTts, credentials.minimaxTts, text, onAudio)
        TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先选择语音播报服务")
        TtsProvider.QWEN, TtsProvider.DOUBAO -> Unit
    }
    val qwen = settings.tts == TtsProvider.QWEN
    val request = if (qwen) {
        SpeechHttp.jsonRequest(speechBaseUrl(settings.qwenTts.endpoint()) + "/api/v1/services/aigc/multimodal-generation/generation",
            credentials.qwenTts, JSONObject().put("model", "qwen3-tts-flash")
                .put("input", JSONObject().put("text", text).put("voice", settings.qwenVoice).put("language_type", "Auto")))
            .header("X-DashScope-SSE", "enable").build()
    } else {
        Request.Builder().url(speechBaseUrl(settings.doubaoTts.baseUrl) + "/api/v3/tts/unidirectional")
            .doubaoHeaders(settings.doubaoTts, credentials.doubaoTts, "seed-tts-2.0")
            .post(JSONObject().put("req_params", JSONObject().put("text", text).put("speaker", settings.doubaoVoice)
                .put("audio_params", JSONObject().put("format", "pcm").put("sample_rate", 24000)))
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }
    SpeechHttp.read(request) { response ->
        val decoder = SpeechAudioStreamDecoder(qwen, onAudio)
        val source = response.body.source()
        while (!source.exhausted() && !decoder.finished) {
            val line = try { source.readUtf8LineStrict(SpeechHttp.MAX_JSON_BYTES) } catch (error: java.io.EOFException) {
                if (source.buffer.size > SpeechHttp.MAX_JSON_BYTES) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频块过大")
                source.readUtf8()
            }
            decoder.line(line)
        }
        decoder.end()
    }
}

/** SSE 事件边界与 HTTP chunk 边界无关；豆包则每行一个 JSON 对象。 */
internal class SpeechAudioStreamDecoder(private val qwen: Boolean, onAudio: (ByteArray) -> Unit) {
    private val sse = SpeechSseDecoder { _, value -> payload(value) }
    private val audio = SpeechSegmentAudio(onAudio)
    var finished = false
        private set

    fun line(line: String) {
        if (finished) return
        if (!qwen) {
            if (line.isNotBlank()) payload(line)
        } else {
            sse.line(line)
        }
    }

    private fun payload(value: String) {
        val message = JSONObject(value)
        val encoded: String
        if (qwen) {
            if (message.optString("code").isNotBlank() || message.optInt("status_code", 200) != 200) {
                throw SpeechFailure(SpeechErrorCode.SERVER, "千问语音合成失败，请检查配置")
            }
            val output = message.getJSONObject("output")
            encoded = output.optJSONObject("audio")?.optString("data").orEmpty()
            finished = output.optString("finish_reason") == "stop"
        } else {
            when (message.getInt("code")) {
                0 -> Unit
                20000000 -> finished = true
                else -> throw SpeechFailure(SpeechErrorCode.SERVER, "豆包语音合成失败（${message.getInt("code")}）")
            }
            encoded = message.optString("data")
        }
        if (encoded.isNotEmpty()) {
            audio.write(Base64.getDecoder().decode(encoded))
        }
    }

    fun end() {
        if (qwen && !finished) sse.end()
        audio.end(finished)
    }
}
