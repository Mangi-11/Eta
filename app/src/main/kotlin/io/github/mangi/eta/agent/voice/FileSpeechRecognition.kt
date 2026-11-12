package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun recognizeSpeechFile(
    context: Context,
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    wav: ByteArray,
    onProgress: (String) -> Unit,
): String = withContext(Dispatchers.IO) {
    withTimeout(300_000) {
        when (settings.asr) {
            AsrProvider.MIMO -> {
                onProgress("正在识别")
                recognizeMimoSpeech(settings.mimoAsr, credentials.mimoAsr, wav)
            }
            AsrProvider.QWEN_FLASH -> {
                onProgress("正在识别")
                recognizeQwenShortAudio(settings, credentials, wav)
            }
            AsrProvider.QWEN_FILE -> recognizeQwenFile(context, settings, credentials, wav, onProgress)
            AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO ->
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "当前语音服务需要流式识别")
        }
    }
}

private suspend fun recognizeQwenShortAudio(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    wav: ByteArray,
): String {
    val base = speechBaseUrl(settings.qwenAsr.endpoint())
    val options = JSONObject().put("enable_itn", true)
    settings.language.takeIf(String::isNotBlank)?.let { options.put("language", it) }
    val audio = JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
        .put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)))
    val body = JSONObject().put("model", "qwen3-asr-flash").put("stream", false)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audio))))
        .put("asr_options", options)
    val response = SpeechHttp.json(SpeechHttp.jsonRequest(
        "$base/compatible-mode/v1/chat/completions", credentials.qwenAsr, body,
    ).build())
    return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
}

private suspend fun recognizeQwenFile(
    context: Context,
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    wav: ByteArray,
    onProgress: (String) -> Unit,
): String {
    val base = speechBaseUrl(settings.qwenAsr.endpoint())
    onProgress("正在上传录音")
    return SpeechOssUpload(context).withAudio(settings.oss, credentials, wav) { audioUrl ->
        val parameters = JSONObject().put("channel_id", JSONArray().put(0)).put("enable_itn", true)
        settings.language.takeIf(String::isNotBlank)?.let { parameters.put("language", it) }
        val body = JSONObject().put("model", "qwen3-asr-flash-filetrans")
            .put("input", JSONObject().put("file_url", audioUrl)).put("parameters", parameters)
        val submitted = SpeechHttp.json(SpeechHttp.jsonRequest(
            "$base/api/v1/services/audio/asr/transcription", credentials.qwenAsr, body,
        ).header("X-DashScope-Async", "enable").build())
        val task = submitted.getJSONObject("output").getString("task_id")
        if (!Regex("[A-Za-z0-9_-]+").matches(task)) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "文件转写任务编号无效")
        }
        onProgress("正在等待文件转写，可随时取消")
        awaitQwenFileTranscript(base, credentials.qwenAsr, task)
    }
}

private suspend fun awaitQwenFileTranscript(base: String, key: String, task: String): String {
    while (true) {
        delay(1500)
        val result = SpeechHttp.json(Request.Builder().url("$base/api/v1/tasks/$task")
            .header("Authorization", "Bearer ${key.trim()}").build()).getJSONObject("output")
        when (result.getString("task_status")) {
            "PENDING", "RUNNING" -> Unit
            "SUCCEEDED" -> {
                val url = result.getJSONObject("result").getString("transcription_url")
                    .toHttpUrl().newBuilder().scheme("https").build()
                // 下载签名结果链接时不附带百炼认证，避免凭据随跨域请求外传。
                val transcription = SpeechHttp.json(Request.Builder().url(url).build())
                return fileTranscript(transcription)
            }
            "FAILED", "CANCELED", "UNKNOWN" ->
                throw SpeechFailure(SpeechErrorCode.SERVER, "千问文件转写失败，请检查服务权限")
            else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "文件转写返回未知任务状态")
        }
    }
}

internal fun fileTranscript(result: JSONObject): String {
    val transcripts = result.getJSONArray("transcripts")
    return (0 until transcripts.length()).map { transcripts.getJSONObject(it).getString("text") }.joinToString("\n").trim()
}
