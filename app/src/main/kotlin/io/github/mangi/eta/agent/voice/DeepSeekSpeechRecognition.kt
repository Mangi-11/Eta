package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.DeepSeekAsrConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal suspend fun recognizeDeepSeekSpeech(
    config: DeepSeekAsrConfig,
    key: String,
    wav: ByteArray,
): String = deepSeekAsrTranscript(SpeechHttp.json(deepSeekAsrRequest(config, key, wav)))

internal fun deepSeekAsrRequest(
    config: DeepSeekAsrConfig,
    key: String,
    wav: ByteArray,
): Request {
    if (wav.size > MAX_DEEPSEEK_ASR_BYTES) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "录音超过 DeepSeek 识别接口 8 MiB 限制")
    }
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "file",
            "speech.wav",
            wav.toRequestBody("audio/wav".toMediaType()),
        )
        .build()
    return Request.Builder()
        .url(speechBaseUrl(config.baseUrl) + "/v1/tools/asr")
        .header("Authorization", "Bearer ${key.trim()}")
        .post(body)
        .build()
}

internal fun deepSeekAsrTranscript(response: JSONObject): String {
    if (!response.isNull("error")) {
        throw SpeechFailure(SpeechErrorCode.SERVER, "DeepSeek 语音识别失败，请检查服务权限和配置")
    }
    val text = response.opt("text") as? String
        ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "DeepSeek 未返回有效的识别结果")
    return text.trim()
}

private const val MAX_DEEPSEEK_ASR_BYTES = 8 * 1024 * 1024
