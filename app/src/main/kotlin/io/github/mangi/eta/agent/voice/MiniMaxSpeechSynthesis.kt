package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.MiniMaxTtsConfig
import okhttp3.Request
import org.json.JSONObject

internal suspend fun synthesizeMiniMax(
    config: MiniMaxTtsConfig,
    key: String,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    SpeechHttp.read(minimaxSpeechRequest(config, key, text)) { response ->
        val decoder = MiniMaxSpeechDecoder(onAudio)
        readSpeechEvents(response) { _, data -> decoder.event(data) }
        decoder.end()
    }
}

internal fun minimaxSpeechRequest(config: MiniMaxTtsConfig, key: String, text: String): Request =
    SpeechHttp.jsonRequest(
        speechBaseUrl(config.baseUrl) + "/v1/t2a_v2",
        key,
        JSONObject().put("model", config.model.apiId).put("text", text).put("stream", true)
            .put("stream_options", JSONObject().put("exclude_aggregated_audio", true))
            .put("voice_setting", JSONObject().put("voice_id", config.voice.trim())
                .put("speed", config.speed.toDouble()).put("vol", 1).put("pitch", 0))
            .put("audio_setting", JSONObject().put("format", "pcm").put("sample_rate", 24000).put("channel", 1))
            .put("text_normalization_mode", "basic"),
    ).header("Accept", "text/event-stream").build()

internal class MiniMaxSpeechDecoder(onAudio: (ByteArray) -> Unit) {
    private val audio = SpeechSegmentAudio(onAudio)
    private var completed = false

    fun event(value: String): Boolean {
        if (completed) return false
        val message = JSONObject(value)
        val code = message.getJSONObject("base_resp").getInt("status_code")
        if (code != 0) throw minimaxFailure(code)
        val data = message.getJSONObject("data")
        when (data.getInt("status")) {
            1 -> {
                val encoded = data.optString("audio")
                if (encoded.isNotEmpty()) audio.write(decodeHexAudio(encoded))
            }
            // 末包的 audio 是整段聚合音频；即使网关忽略 exclude_aggregated_audio，也不能重复播放。
            2 -> completed = true
            else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "MiniMax 返回了无效的音频状态")
        }
        return !completed
    }

    fun end() = audio.end(completed)
}

private fun decodeHexAudio(value: String): ByteArray {
    if (value.length % 2 != 0) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "MiniMax 音频编码不完整")
    return ByteArray(value.length / 2) { index ->
        val high = value[index * 2].digitToIntOrNull(16)
        val low = value[index * 2 + 1].digitToIntOrNull(16)
        if (high == null || low == null) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "MiniMax 音频编码无效")
        ((high shl 4) or low).toByte()
    }
}

private fun minimaxFailure(code: Int): SpeechFailure = when (code) {
    1001 -> SpeechFailure(SpeechErrorCode.TIMEOUT, "MiniMax 语音合成超时，请重试")
    1002, 1039 -> SpeechFailure(SpeechErrorCode.RATE_LIMITED, "MiniMax 请求过于频繁，请稍后重试")
    1004 -> SpeechFailure(SpeechErrorCode.AUTHENTICATION, "MiniMax 认证失败，请检查 API Key")
    1042, 2013 -> SpeechFailure(SpeechErrorCode.CONFIGURATION, "MiniMax 无法处理当前文本或音色，请检查配置")
    else -> SpeechFailure(SpeechErrorCode.SERVER, "MiniMax 语音合成失败（$code）")
}
