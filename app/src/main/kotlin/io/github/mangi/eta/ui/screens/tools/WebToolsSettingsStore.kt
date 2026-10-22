package io.github.mangi.eta.ui.screens.tools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.web.ConfigurableWebBackends
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.repository.WebToolsCredentialsUnavailable
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class WebToolsValidationFailure(val userMessage: String) : IllegalArgumentException(userMessage)

internal class WebToolsSettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(WebToolsSettings())
        private set
    var credentials by mutableStateOf(WebToolsCredentials())
        private set
    var hasProviderDeepSeekKey by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    init {
        scope.launch {
            try {
                settings = WebToolsSettingsRepository.settings()
                hasProviderDeepSeekKey = WebToolsSettingsRepository.deepSeekProviderCredentials() != null
                try {
                    credentials = WebToolsSettingsRepository.credentials(context)
                } catch (_: WebToolsCredentialsUnavailable) {
                    message = context.getString(R.string.web_tools_credentials_lost)
                    messageIsError = true
                }
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.web_tools_load_failed)
                messageIsError = true
            }
        }
    }

    fun edit(value: WebToolsSettings) {
        settings = value
        message = null
    }

    fun editCredentials(value: WebToolsCredentials) {
        credentials = value
        message = null
    }

    fun save() {
        if (!loaded || saving) return
        val config = settings
        val secrets = credentials
        saving = true
        scope.launch {
            try {
                val latestProviderDeepSeek = WebToolsSettingsRepository.deepSeekProviderCredentials() != null
                hasProviderDeepSeekKey = latestProviderDeepSeek
                validateWebToolsSettings(config, secrets, latestProviderDeepSeek)
                WebToolsSettingsRepository.saveCredentials(context, secrets)
                WebToolsSettingsRepository.save(config)
                message = context.getString(
                    if (settings === config && credentials === secrets) {
                        R.string.speech_saved
                    } else {
                        R.string.speech_saved_with_pending
                    },
                )
                messageIsError = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = (error as? WebToolsValidationFailure)?.userMessage
                    ?: context.getString(R.string.speech_save_retry)
                message = context.getString(R.string.speech_save_failed, reason)
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }
}

internal fun validateWebToolsSettings(
    settings: WebToolsSettings,
    credentials: WebToolsCredentials,
    hasProviderDeepSeekKey: Boolean,
) {
    val usesDeepSeek = settings.searchBackend == WebToolBackend.DEEPSEEK ||
        settings.fetchBackend == WebToolBackend.DEEPSEEK
    if (usesDeepSeek) {
        if (credentials.deepSeekApiKey.isBlank() && !hasProviderDeepSeekKey) {
            throw WebToolsValidationFailure("请填写 DeepSeek API Key，或先在模型提供商中配置 DeepSeek")
        }
        runCatching {
            ConfigurableWebBackends.deepSeekEndpoint(settings.deepSeek.baseUrl, "search")
        }.getOrElse {
            throw WebToolsValidationFailure("DeepSeek Base URL 须为合法的 HTTP(S) 地址")
        }
    }
    if (settings.searchBackend == WebToolBackend.CUSTOM) {
        val rawUrl = settings.customSearch.url.trim()
        if (rawUrl.isEmpty()) {
            throw WebToolsValidationFailure("请填写自定义搜索接口地址")
        }
        val probeUrl = rawUrl
            .replace("{{query}}", "eta")
            .replace("{{raw_query}}", "eta")
            .replace("{{count}}", "5")
        runCatching { WebHttpTransport.validateUrl(probeUrl) }.getOrElse {
            throw WebToolsValidationFailure("自定义搜索接口须为合法的 HTTP(S) 地址")
        }
        runCatching {
            ConfigurableWebBackends.parseCustomHeaders(
                settings.customSearch.headersText,
                credentials.customSearchApiKey,
            )
        }.getOrElse {
            throw WebToolsValidationFailure(it.message ?: "自定义搜索请求头格式无效")
        }
    }
    if (settings.fetchBackend == WebToolBackend.CUSTOM) {
        val rawUrl = settings.customFetch.url.trim()
        if (rawUrl.isEmpty()) {
            throw WebToolsValidationFailure("请填写自定义读取接口地址")
        }
        val probeUrl = rawUrl
            .replace("{{url}}", "https%3A%2F%2Fexample.com")
            .replace("{{raw_url}}", "https://example.com")
        runCatching { WebHttpTransport.validateUrl(probeUrl) }.getOrElse {
            throw WebToolsValidationFailure("自定义读取接口须为合法的 HTTP(S) 地址")
        }
        runCatching {
            ConfigurableWebBackends.parseCustomHeaders(
                settings.customFetch.headersText,
                credentials.customFetchApiKey,
            )
        }.getOrElse {
            throw WebToolsValidationFailure(it.message ?: "自定义读取请求头格式无效")
        }
    }
}
