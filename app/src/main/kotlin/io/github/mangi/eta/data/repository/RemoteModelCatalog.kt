package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.runtimeProviderType

/** 目录属于发起读取时的连接与账号；导入确认时必须再次核对，不能套用到后来切换的账号。 */
internal class RemoteModelCatalog(
    val models: List<Model>,
    val connection: ProviderSetting,
    val accountBinding: ProviderCredentialBinding?,
) {
    fun matches(provider: ProviderSetting): Boolean =
        connection.id == provider.id && connection.authMode == provider.authMode &&
            connection.runtimeProviderType == provider.runtimeProviderType &&
            connection.baseUrl.trim().trimEnd('/') == provider.baseUrl.trim().trimEnd('/') &&
            connection.apiKey == provider.apiKey && connection.customHeaders == provider.customHeaders &&
            connection.sourceType == provider.sourceType && connection.isEnabled == provider.isEnabled &&
            connection.endpointMode() == provider.endpointMode() &&
            (connection as? AnthropicProviderSetting)?.anthropicVersion ==
            (provider as? AnthropicProviderSetting)?.anthropicVersion

    override fun toString(): String = "RemoteModelCatalog(modelCount=${models.size}, connection=redacted)"

    private fun ProviderSetting.endpointMode(): String? = when (this) {
        is OpenAiCompatibleProviderSetting -> endpointMode
        is CustomProviderSetting -> endpointMode
        is AnthropicProviderSetting -> null
    }
}
