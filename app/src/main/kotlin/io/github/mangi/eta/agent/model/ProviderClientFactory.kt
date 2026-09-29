package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient =
        when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> if (
                    config.authMode == CodexCompatibilityProfile.AUTH_MODE ||
                    config.providerSourceType == ProviderSourceTypes.OPENAI_CODEX ||
                    config.providerId == BuiltinProviders.OPENAI_CODEX_ID
                ) CodexResponsesProvider else OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
}
