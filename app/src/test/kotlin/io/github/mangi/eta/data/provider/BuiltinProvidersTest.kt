package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinProvidersTest {
    @Test
    fun builtInsKeepStablePresetMetadataWithoutInliningModels() {
        val providers = BuiltinProviders.PROVIDERS.associateBy { it.id }

        assertTrue(providers[BuiltinProviders.OPENAI_ID] is OpenAiCompatibleProviderSetting)
        assertTrue(providers[BuiltinProviders.ANTHROPIC_ID] is AnthropicProviderSetting)
        assertEquals(0, providers.getValue(BuiltinProviders.OPENAI_ID).models.size)
        assertEquals(
            OpenAiEndpointMode.RESPONSES,
            (providers.getValue(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting).endpointMode,
        )
        assertEquals(false, providers.getValue(BuiltinProviders.OPENAI_ID).hostedWebSearchEnabled)
        assertEquals(0, providers.getValue(BuiltinProviders.BAILIAN_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.MIMO_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.MINIMAX_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.STEPFUN_ID).models.size)
        assertEquals("https://api.moonshot.cn/v1", providers.getValue(BuiltinProviders.KIMI_ID).baseUrl)
        assertEquals(ProviderSourceTypes.BAILIAN, providers.getValue(BuiltinProviders.BAILIAN_ID).sourceType)
        assertEquals(ProviderSourceTypes.MOONSHOT, providers.getValue(BuiltinProviders.KIMI_ID).sourceType)
        assertEquals(ProviderSourceTypes.MIMO, providers.getValue(BuiltinProviders.MIMO_ID).sourceType)
        assertEquals(ProviderSourceTypes.MINIMAX, providers.getValue(BuiltinProviders.MINIMAX_ID).sourceType)
        assertEquals(ProviderSourceTypes.STEPFUN, providers.getValue(BuiltinProviders.STEPFUN_ID).sourceType)
        assertEquals("https://api.x.ai/v1", providers.getValue(BuiltinProviders.XAI_ID).baseUrl)
        assertEquals(ProviderSourceTypes.XAI, providers.getValue(BuiltinProviders.XAI_ID).sourceType)
    }

    @Test
    fun xaiBrandingUsesExactOfficialHosts() {
        for (host in listOf("api.x.ai", "cli-chat-proxy.grok.com")) {
            assertEquals(ProviderSourceTypes.XAI, ProviderSourceRegistry.resolve(
                providerId = "custom", baseUrl = "https://$host/v1", providerType = "openai_compatible",
            ))
        }
        assertEquals(ProviderSourceTypes.CUSTOM, ProviderSourceRegistry.resolve(
            providerId = "custom", baseUrl = "https://api.x.ai.example.invalid/v1", providerType = "openai_compatible",
        ))
    }
}
