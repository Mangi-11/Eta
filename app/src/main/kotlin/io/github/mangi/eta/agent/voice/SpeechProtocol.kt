package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpeechErrorCode {
    CONFIGURATION, CREDENTIALS, PERMISSION, AUDIO, NETWORK, TIMEOUT, AUTHENTICATION,
    RATE_LIMITED, SERVER, PROTOCOL, NO_SPEECH, BACKPRESSURE, STORAGE,
}

internal class SpeechFailure(val code: SpeechErrorCode, val userMessage: String) : IOException(code.name)

internal fun Throwable.speechFailure(): SpeechFailure = when (this) {
    is SpeechFailure -> this
    is SpeechCredentialsUnavailable -> SpeechFailure(SpeechErrorCode.CREDENTIALS, "语音凭据无法解密，请重新填写并保存")
    is SecurityException -> SpeechFailure(SpeechErrorCode.PERMISSION, "请允许 Eta 使用麦克风")
    is kotlinx.coroutines.TimeoutCancellationException -> SpeechFailure(SpeechErrorCode.TIMEOUT, "语音服务响应超时，请重试")
    is IOException -> SpeechFailure(SpeechErrorCode.NETWORK, "无法连接语音服务，请检查网络")
    else -> SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了无法处理的数据")
}

internal fun validateSpeechSettings(settings: SpeechSettings, secrets: SpeechCredentials, synthesis: Boolean) {
    if (!synthesis && !settings.recognitionEnabled) return
    fun required(value: String, name: String) {
        if (value.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先配置$name")
    }
    if (synthesis) {
        when (settings.tts) {
            TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先在设置中配置语音播报")
            TtsProvider.QWEN -> {
                required(secrets.qwenTts, "千问播报 API Key")
                required(settings.qwenVoice, "音色")
                speechBaseUrl(settings.qwenTts.endpoint())
            }
            TtsProvider.DOUBAO -> {
                required(secrets.doubaoTts, "豆包播报凭据")
                required(settings.doubaoVoice, "音色")
                speechBaseUrl(settings.doubaoTts.baseUrl)
                if (settings.doubaoTts.legacyAuth) required(settings.doubaoTts.appId, "豆包 App ID")
            }
            TtsProvider.GEMINI -> {
                required(secrets.geminiTts, "Gemini 播报 API Key")
                required(settings.geminiTts.voice, "音色")
                speechBaseUrl(settings.geminiTts.baseUrl)
                if (settings.geminiTts.style.length > 500) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "朗读风格请控制在 500 字以内")
                }
            }
            TtsProvider.MINIMAX -> {
                required(secrets.minimaxTts, "MiniMax 播报 API Key")
                required(settings.minimaxTts.voice, "音色")
                speechBaseUrl(settings.minimaxTts.baseUrl)
                if (settings.minimaxTts.speed !in 0.5f..2f) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "MiniMax 语速须在 0.5～2 倍之间")
                }
            }
        }
    } else when (settings.asr) {
        AsrProvider.SYSTEM -> Unit
        AsrProvider.DOUBAO -> {
            required(secrets.doubaoAsr, "豆包识别凭据")
            speechBaseUrl(settings.doubaoAsr.baseUrl)
            if (settings.doubaoAsr.legacyAuth) required(settings.doubaoAsr.appId, "豆包 App ID")
        }
        else -> {
            required(secrets.qwenAsr, "千问识别 API Key")
            speechBaseUrl(settings.qwenAsr.endpoint())
            if (settings.asr == AsrProvider.QWEN_FILE) {
                required(settings.oss.bucket, "OSS Bucket")
                required(settings.oss.region, "OSS 地域")
                required(secrets.ossAccessKeyId, "OSS AccessKey ID")
                required(secrets.ossAccessKeySecret, "OSS AccessKey Secret")
                speechBaseUrl(settings.oss.endpoint)
                if (!Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(settings.oss.bucket) ||
                    settings.oss.prefix.split('/').any { it == ".." || it == "." }
                ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请检查 OSS Bucket 和目录前缀")
            }
        }
    }
}

internal fun speechBaseUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "服务地址须为不含认证信息和查询参数的 HTTPS 地址")
    return url.toString().trimEnd('/')
}
