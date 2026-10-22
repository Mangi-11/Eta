package io.github.mangi.eta.data.provider

import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 订阅凭据与官方服务地址绑定，用户配置不能改变其发送目标或计费通道。 */
internal enum class ProviderConnectionProfile(
    val authMode: String,
    val baseUrl: String,
    val endpointMode: String,
    val sourceType: String,
) {
    OPENAI_CODEX(
        ProviderAuthModes.OPENAI_CHATGPT,
        "https://chatgpt.com/backend-api/codex",
        OpenAiEndpointMode.RESPONSES,
        ProviderSourceTypes.OPENAI,
    ),
    XAI_GROK(
        ProviderAuthModes.XAI_GROK,
        "https://cli-chat-proxy.grok.com/v1",
        OpenAiEndpointMode.CHAT_COMPLETIONS,
        ProviderSourceTypes.XAI,
    );

    val providerType: String get() = ProviderTypes.OPENAI_COMPATIBLE
    val modelsUrl: String get() = "$baseUrl/models".toHttpUrl().newBuilder().apply {
        if (this@ProviderConnectionProfile == OPENAI_CODEX) {
            addQueryParameter("client_version", BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+'))
        }
    }.build().toString()
    val inferenceUrl: String get() = when (endpointMode) {
        OpenAiEndpointMode.RESPONSES -> "$baseUrl/responses"
        else -> "$baseUrl/chat/completions"
    }

    fun requireRequestTarget(url: HttpUrl) {
        require(url == modelsUrl.toHttpUrl() || url == inferenceUrl.toHttpUrl()) {
            "订阅凭据只能用于对应服务商的官方模型接口"
        }
    }

    companion object {
        fun forAuthMode(authMode: String): ProviderConnectionProfile? = when (authMode) {
            ProviderAuthModes.API_KEY -> null
            ProviderAuthModes.OPENAI_CHATGPT -> OPENAI_CODEX
            ProviderAuthModes.XAI_GROK -> XAI_GROK
            else -> error("不支持的服务商认证方式")
        }
    }
}
