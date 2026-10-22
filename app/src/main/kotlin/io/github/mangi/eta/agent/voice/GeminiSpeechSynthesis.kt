package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.GeminiTtsConfig
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun synthesizeGemini(
    config: GeminiTtsConfig,
    key: String,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    SpeechHttp.read(geminiSpeechRequest(config, key, text)) { response ->
        val decoder = GeminiSpeechDecoder(onAudio)
        readSpeechEvents(response, decoder::event)
        decoder.end()
    }
}

internal fun geminiSpeechRequest(config: GeminiTtsConfig, key: String, text: String): Request {
    val content = JSONObject().put("type", "text").put("text", text)
    if (config.style.isNotBlank()) {
        content.put("annotations", JSONArray().put(
            JSONObject().put("type", "speech_metadata").put("style", config.style.trim()),
        ))
    }
    val body = JSONObject()
        .put("model", config.model.apiId)
        .put("input", JSONArray().put(
            JSONObject().put("type", "user_input").put("content", JSONArray().put(content)),
        ))
        .put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/l16").put("sample_rate", 24000))
        .put("generation_config", JSONObject().put("speech_config",
            JSONArray().put(JSONObject().put("voice", config.voice.trim()))))
        .put("stream", true)
        .put("store", false)
    return Request.Builder()
        .url(speechBaseUrl(config.baseUrl) + "/v1beta/interactions")
        .header("x-goog-api-key", key.trim())
        .header("Accept", "text/event-stream")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
}

internal class GeminiSpeechDecoder(onAudio: (ByteArray) -> Unit) {
    private val audio = SpeechSegmentAudio(onAudio)
    private var completed = false

    fun event(name: String, data: String): Boolean {
        if (completed) return false
        // [DONE] 只代表传输结束，不能代替 interaction 的成功状态。
        if (data == "[DONE]") return false
        val message = JSONObject(data)
        if (message.has("error")) throw geminiFailure(message.optJSONObject("error"))
        when (message.optString("event_type").ifEmpty { name }) {
            "step.delta" -> {
                val delta = message.getJSONObject("delta")
                if (delta.optString("type") == "audio") {
                    val mime = delta.optString("mime_type", "audio/l16").substringBefore(';')
                    if (mime != "audio/l16" || delta.optInt("sample_rate", 24000) != 24000 ||
                        delta.optInt("channels", 1) != 1
                    ) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Gemini 返回了不支持的音频格式")
                    val encoded = delta.optString("data")
                    if (encoded.isNotEmpty()) audio.write(Base64.getDecoder().decode(encoded))
                }
            }
            "interaction.completed" -> {
                if (message.getJSONObject("interaction").optString("status") != "completed") {
                    throw SpeechFailure(SpeechErrorCode.SERVER, "Gemini 未完成朗读，请缩短文本或调整朗读风格后重试")
                }
                completed = true
            }
            "error" -> throw geminiFailure(message.optJSONObject("error"))
        }
        return !completed
    }

    fun end() = audio.end(completed)
}

private fun geminiFailure(error: JSONObject?): SpeechFailure = when (error?.optString("status")) {
    "UNAUTHENTICATED", "PERMISSION_DENIED" ->
        SpeechFailure(SpeechErrorCode.AUTHENTICATION, "Gemini 认证失败，请检查 API Key、地区和模型权限")
    "RESOURCE_EXHAUSTED" ->
        SpeechFailure(SpeechErrorCode.RATE_LIMITED, "Gemini 额度不足或请求过于频繁，请稍后重试")
    else -> SpeechFailure(SpeechErrorCode.SERVER, "Gemini 语音合成失败，请检查模型权限和配置")
}
