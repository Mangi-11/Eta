package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.CodexCompatibilityProfile
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeConfigRepositoryTest {
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
        val raw = RuntimeConfigRepository.runtimeConfigJson(config)
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
        assertEquals(config, Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
    }

    @Test
    fun runtimeJsonNeverContainsCodexAccessToken() {
        val config = AgentModelClient.ModelConfig(
            providerId = "builtin-openai-codex",
            providerSourceType = ProviderSourceTypes.OPENAI_CODEX,
            authMode = CodexCompatibilityProfile.AUTH_MODE,
            baseUrl = CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL,
            apiKey = "fake-codex-access-token-for-test-only",
            model = "gpt-6-astra",
            systemPrompt = "",
        )

        val raw = RuntimeConfigRepository.runtimeConfigJson(config)
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals("", root.getValue("apiKey").jsonPrimitive.content)
        org.junit.Assert.assertFalse(raw.contains("fake-codex-access-token-for-test-only"))
    }
}
