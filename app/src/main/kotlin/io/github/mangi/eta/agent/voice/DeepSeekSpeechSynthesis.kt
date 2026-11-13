package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.DeepSeekTtsConfig
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

internal suspend fun synthesizeDeepSeek(
    config: DeepSeekTtsConfig,
    key: String,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    SpeechHttp.read(deepSeekTtsRequest(config, key, text)) { response ->
        val audio = SpeechSegmentAudio(onAudio)
        val source = response.body.source()
        val buffer = ByteArray(8192)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            if (read > 0) {
                audio.write(buffer.copyOf(read))
            }
        }
        audio.end(completed = true)
    }
}

internal fun deepSeekTtsRequest(
    config: DeepSeekTtsConfig,
    key: String,
    text: String,
): Request {
    val body = JSONObject()
        .put("text", text)
        .put("voice_id", config.voice.trim())
        .put("format", "pcm")
        .put("filter_markdown", config.filterMarkdown)
    return SpeechHttp.jsonRequest(
        speechBaseUrl(config.baseUrl) + "/v1/tools/tts",
        key,
        body,
    ).build()
}

internal fun deepSeekTtsStreamRequest(
    config: DeepSeekTtsConfig,
    key: String,
): Request {
    val httpUrl = speechBaseUrl(config.baseUrl).toHttpUrl().newBuilder()
        .addPathSegments("v1/tools/tts")
        .addQueryParameter("voice_id", config.voice.trim())
        .addQueryParameter("format", "pcm")
        .addQueryParameter("filter_markdown", config.filterMarkdown.toString())
        .build()
    val wsUrl = httpUrl.toString().replaceFirst("https://", "wss://")
    return Request.Builder()
        .url(wsUrl)
        .header("Authorization", "Bearer ${key.trim()}")
        .build()
}

internal suspend fun streamDeepSeekSpeech(
    config: DeepSeekTtsConfig,
    key: String,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    // DeepSeek WebSocket 单次会话上限为 10,000 计费字符（汉字算 2 个）与 64 KiB 文本；
    // 超出时在出声前抛出配置异常，由 SpeechPlaybackController 无缝回落到逐段 HTTP 合成。
    val totalBillingChars = chunks.sumOf(::deepSeekBillingCharCount)
    val totalBytes = chunks.sumOf { it.toByteArray(Charsets.UTF_8).size }
    if (totalBillingChars > MAX_WS_BILLING_CHARS || totalBytes > MAX_WS_UTF8_BYTES) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "正文超过 DeepSeek 流式会话字符上限")
    }
    SpeechSocket(deepSeekTtsStreamRequest(config, key), typed = true).use { socket ->
        socket.awaitReady()
        for (chunk in chunks) {
            socket.text(JSONObject().put("event", "text").put("text", chunk).toString())
        }
        socket.text(JSONObject().put("event", "finish_text").toString())
        val pcm = SpeechPcmDecoder(onAudio)
        var audioBytes = 0L
        for (frame in socket.frames) {
            if (frame.binary) {
                if (frame.bytes.isNotEmpty()) {
                    audioBytes += frame.bytes.size
                    if (audioBytes > MAX_DEEPSEEK_STREAM_AUDIO_BYTES) {
                        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频格式或长度无效")
                    }
                    pcm.write(frame.bytes)
                }
                continue
            }
            val message = JSONObject(frame.bytes.toString(Charsets.UTF_8))
            if (message.optString("event") == "finish") {
                when (val code = message.optString("code")) {
                    "success" -> {
                        if (audioBytes == 0L) {
                            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频不完整，请重试")
                        }
                        pcm.end()
                        return@coroutineScope
                    }
                    "content_filter" -> throw SpeechFailure(
                        SpeechErrorCode.SERVER,
                        "DeepSeek 未能合成这段文本，请调整后重试",
                    )
                    "invalid_input" -> throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        "DeepSeek 语音合成参数或文本无效",
                    )
                    else -> throw SpeechFailure(
                        SpeechErrorCode.SERVER,
                        "DeepSeek 语音合成失败（${code.ifBlank { "unknown" }}）",
                    )
                }
            }
        }
        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "DeepSeek 语音合成连接提前结束")
    }
}

internal fun deepSeekBillingCharCount(text: String): Int = text.sumOf { ch ->
    if (ch in '\u4E00'..'\u9FFF') 2L else 1L
}.toInt()

private const val MAX_WS_BILLING_CHARS = 9_500
private const val MAX_WS_UTF8_BYTES = 60 * 1024
private const val MAX_DEEPSEEK_STREAM_AUDIO_BYTES = 24_000L * 2L * 60L * 30L
