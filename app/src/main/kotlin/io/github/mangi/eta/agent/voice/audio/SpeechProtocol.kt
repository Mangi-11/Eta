package io.github.mangi.eta.agent.voice.audio

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
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
    is SpeechCredentialsUnavailable -> SpeechFailure(
        SpeechErrorCode.CREDENTIALS,
        AppStrings.get(R.string.voice_i18n_credentials_decrypt_failed, "语音凭据无法解密，请重新填写并保存"),
    )
    is SecurityException -> SpeechFailure(
        SpeechErrorCode.PERMISSION,
        AppStrings.get(R.string.voice_i18n_mic_permission_required, "请允许 Eta 使用麦克风"),
    )
    is kotlinx.coroutines.TimeoutCancellationException -> SpeechFailure(
        SpeechErrorCode.TIMEOUT,
        AppStrings.get(R.string.voice_i18n_service_timeout, "语音服务响应超时，请重试"),
    )
    is IOException -> SpeechFailure(
        SpeechErrorCode.NETWORK,
        AppStrings.get(R.string.voice_i18n_network_unavailable, "无法连接语音服务，请检查网络"),
    )
    else -> SpeechFailure(
        SpeechErrorCode.PROTOCOL,
        AppStrings.get(R.string.voice_i18n_service_unreadable_data, "语音服务返回了无法处理的数据"),
    )
}

internal fun validateSpeechSettings(
    settings: SpeechSettings,
    secrets: SpeechCredentials,
    synthesis: Boolean,
    hasProviderDeepSeekKey: Boolean = false,
) {
    if (!synthesis && !settings.recognitionEnabled) return
    fun required(value: String, nameResId: Int, fallbackName: String) {
        if (value.isBlank()) {
            val name = AppStrings.get(nameResId, fallbackName)
            throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(R.string.voice_i18n_config_required_field, "请先配置%1\$s", name),
            )
        }
    }
    if (synthesis) {
        when (settings.tts) {
            TtsProvider.NONE -> throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(R.string.voice_i18n_config_tts_required, "请先在设置中配置语音播报"),
            )
            TtsProvider.QWEN -> {
                required(secrets.qwenTts, R.string.voice_i18n_field_qwen_tts_api_key, "千问播报 API Key")
                required(settings.qwenVoice, R.string.voice_i18n_field_voice, "音色")
                speechBaseUrl(settings.qwenTts.endpoint())
            }
            TtsProvider.DOUBAO -> {
                required(secrets.doubaoTts, R.string.voice_i18n_field_doubao_tts_credentials, "豆包播报凭据")
                required(settings.doubaoVoice, R.string.voice_i18n_field_voice, "音色")
                speechBaseUrl(settings.doubaoTts.baseUrl)
                if (settings.doubaoTts.legacyAuth) {
                    required(settings.doubaoTts.appId, R.string.voice_i18n_field_doubao_app_id, "豆包 App ID")
                }
            }
            TtsProvider.GEMINI -> {
                required(secrets.geminiTts, R.string.voice_i18n_field_gemini_tts_api_key, "Gemini 播报 API Key")
                required(settings.geminiTts.voice, R.string.voice_i18n_field_voice, "音色")
                speechBaseUrl(settings.geminiTts.baseUrl)
                if (settings.geminiTts.style.length > 500) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_config_style_too_long, "朗读风格请控制在 500 字以内"),
                    )
                }
            }
            TtsProvider.MINIMAX -> {
                required(secrets.minimaxTts, R.string.voice_i18n_field_minimax_tts_api_key, "MiniMax 播报 API Key")
                required(settings.minimaxTts.voice, R.string.voice_i18n_field_voice, "音色")
                speechBaseUrl(settings.minimaxTts.baseUrl)
                if (settings.minimaxTts.speed !in 0.5f..2f) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_config_minimax_speed_range, "MiniMax 语速须在 0.5～2 倍之间"),
                    )
                }
            }
            TtsProvider.MIMO -> {
                required(secrets.mimoTts, R.string.voice_i18n_field_mimo_tts_api_key, "MiMo 播报 API Key")
                required(settings.mimoTts.voice, R.string.voice_i18n_field_voice, "音色")
                speechBaseUrl(settings.mimoTts.baseUrl)
                if (settings.mimoTts.style.length > 500) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_config_style_too_long, "朗读风格请控制在 500 字以内"),
                    )
                }
            }
            TtsProvider.DEEPSEEK -> {
                if (!hasProviderDeepSeekKey) {
                    required(secrets.deepseekTts, R.string.voice_i18n_field_deepseek_tts_api_key, "DeepSeek 播报 API Key")
                }
                required(settings.deepseekTts.voice, R.string.voice_i18n_field_voice, "音色")
                speechBaseUrl(settings.deepseekTts.baseUrl)
            }
        }
    } else when (settings.asr) {
        AsrProvider.MIMO -> {
            required(secrets.mimoAsr, R.string.voice_i18n_field_mimo_asr_api_key, "MiMo 识别 API Key")
            speechBaseUrl(settings.mimoAsr.baseUrl)
        }
        AsrProvider.DEEPSEEK -> {
            if (!hasProviderDeepSeekKey) {
                required(secrets.deepseekAsr, R.string.voice_i18n_field_deepseek_asr_api_key, "DeepSeek 识别 API Key")
            }
            speechBaseUrl(settings.deepseekAsr.baseUrl)
        }
        AsrProvider.DOUBAO -> {
            required(secrets.doubaoAsr, R.string.voice_i18n_field_doubao_asr_credentials, "豆包识别凭据")
            speechBaseUrl(settings.doubaoAsr.baseUrl)
            if (settings.doubaoAsr.legacyAuth) {
                required(settings.doubaoAsr.appId, R.string.voice_i18n_field_doubao_app_id, "豆包 App ID")
            }
        }
        AsrProvider.QWEN_REALTIME,
        AsrProvider.QWEN_FLASH,
        AsrProvider.QWEN_FILE,
        -> {
            required(secrets.qwenAsr, R.string.voice_i18n_field_qwen_asr_api_key, "千问识别 API Key")
            speechBaseUrl(settings.qwenAsr.endpoint())
            if (settings.asr == AsrProvider.QWEN_FILE) {
                required(settings.oss.bucket, R.string.voice_i18n_field_oss_bucket, "OSS Bucket")
                required(settings.oss.region, R.string.voice_i18n_field_oss_region, "OSS 地域")
                required(secrets.ossAccessKeyId, R.string.voice_i18n_field_oss_access_key_id, "OSS AccessKey ID")
                required(
                    secrets.ossAccessKeySecret,
                    R.string.voice_i18n_field_oss_access_key_secret,
                    "OSS AccessKey Secret",
                )
                speechBaseUrl(settings.oss.endpoint)
                if (!Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(settings.oss.bucket) ||
                    settings.oss.prefix.split('/').any { it == ".." || it == "." }
                ) throw SpeechFailure(
                    SpeechErrorCode.CONFIGURATION,
                    AppStrings.get(R.string.voice_i18n_config_oss_bucket_prefix_invalid, "请检查 OSS Bucket 和目录前缀"),
                )
            }
        }
    }
}

internal fun speechBaseUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) throw SpeechFailure(
        SpeechErrorCode.CONFIGURATION,
        AppStrings.get(R.string.voice_i18n_config_base_url_invalid, "服务地址须为不含认证信息和查询参数的 HTTPS 地址"),
    )
    return url.toString().trimEnd('/')
}
