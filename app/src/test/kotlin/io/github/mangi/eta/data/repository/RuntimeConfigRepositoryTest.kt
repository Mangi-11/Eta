package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeConfigRepositoryTest {
    @Test
    fun oauthConfigUsesBoundEndpointAndNeverSerializesApiKey() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-provider",
            name = "订阅",
            baseUrl = "https://untrusted.invalid/v1",
            apiKey = "fixture-must-not-be-projected",
            authMode = ProviderAuthModes.XAI_GROK,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        )
        val config = RuntimeConfigRepository.buildRuntimeConfig(
            provider,
            Model(id = "local-model", modelId = "fixture-model", displayName = "测试"),
        )

        assertEquals("https://cli-chat-proxy.grok.com/v1", config.baseUrl)
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, config.openAiEndpointMode)
        assertEquals(ProviderSourceTypes.XAI, config.providerSourceType)
        assertEquals(ProviderAuthModes.XAI_GROK, config.authMode)
        assertEquals("", config.apiKey)
        val exported = RuntimeConfigRepository.runtimeConfigJson(config.copy(apiKey = "fixture-must-not-be-projected"))
        assertFalse(exported.contains("fixture-must-not-be-projected"))
        assertEquals("", Json.decodeFromString<AgentModelClient.ModelConfig>(exported).apiKey)
    }

    @Test
    fun previousRuntimeJsonDefaultsToApiKeyAuthentication() {
        val restored = Json.decodeFromString<AgentModelClient.ModelConfig>(
            """{"baseUrl":"https://example.invalid/v1","apiKey":"fixture-key","model":"fixture","systemPrompt":""}""",
        )
        assertEquals(ProviderAuthModes.API_KEY, restored.authMode)
        assertEquals("fixture-key", restored.apiKey)
    }

    @Test
    fun buildsStructuredRuntimeConfigFromProviderAndModel() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Provider",
            baseUrl = "https://api.example.com/v1",
            apiKey = "key",
            sourceType = ProviderSourceTypes.OPENAI,
            customHeaders = listOf(CustomHeader("x-provider", "1"))
        )
        val model = Model(
            id = "m1",
            modelId = "gpt-5.5",
            displayName = "GPT-5.5",
            contextWindow = 1_000_000,
            contextWindowOverride = 256_000,
            reasoning = true,
            reasoningOverride = true,
            reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                canDisable = true,
            ),
            customHeaders = listOf(CustomHeader("x-model", "2"))
        )

        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val raw = RuntimeConfigRepository.runtimeConfigJson(config.copy(autoCompactionEnabled = false))
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals(ProviderTypes.OPENAI_COMPATIBLE, root.getValue("providerType").jsonPrimitive.content)
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(256_000, config.contextWindow)
        assertEquals(listOf("x-provider", "x-model"), config.customHeaders.map { it.name })
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(true, config.thinkingEnabled)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
            ),
            config.reasoningCapabilities?.selectableEfforts,
        )
        assertEquals("false", root.getValue("autoCompactionEnabled").jsonPrimitive.content)
        assertEquals(config.copy(autoCompactionEnabled = false), Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
        assertEquals(config.autoCompactionEnabled,
            Json.decodeFromString<AgentModelClient.ModelConfig>(RuntimeConfigRepository.runtimeConfigJson(config)).autoCompactionEnabled)
    }
}
