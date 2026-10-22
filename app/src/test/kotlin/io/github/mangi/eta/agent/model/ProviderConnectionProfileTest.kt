package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionProfileTest {
    @Test
    fun OAuthProjectionBindsProtocolAndHostWithoutPuttingTokensIntoConfig() {
        val original = config().copy(
            baseUrl = "https://custom-gateway.example/v1", apiKey = "old-api-fixture",
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val effective = ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig
        assertEquals("https://chatgpt.com/backend-api/codex", effective.baseUrl)
        assertEquals(OpenAiEndpointMode.RESPONSES, effective.openAiEndpointMode)
        assertEquals("", effective.apiKey)
        assertEquals(OpenAiResponsesProvider, ProviderClientFactory.getClient(original))
        assertEquals("old-api-fixture", original.apiKey)
    }

    @Test
    fun ApiKeyProjectionKeepsCustomEndpointAndCredentials() {
        val original = config().copy(baseUrl = "https://custom-gateway.example/v1", apiKey = "api-fixture")
        assertEquals(original, ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig)
    }

    @Test
    fun grokBodyCannotOverrideSelectedModelOrDisableStreaming() {
        val request = OpenAiChatCompletionsProvider.buildRequestJson(
            config().copy(
                authMode = ProviderAuthModes.XAI_GROK,
                extraBodyJson = """{"model":"unexpected-model","stream":false}""",
            ),
            JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")),
            JSONArray(),
        )
        assertEquals("grok-fixture", request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        assertEquals(1, request.getJSONArray("messages").length())
    }

    @Test
    fun unknownAuthenticationModeDoesNotFallBackToApiKey() {
        assertTrue(runCatching { ProviderConnectionProfile.forAuthMode("unknown-fixture") }.isFailure)
    }

    private fun config() = AgentModelClient.ModelConfig(
        providerId = "account", baseUrl = "https://api.x.ai/v1", apiKey = "",
        model = "grok-fixture", systemPrompt = "fixture",
    )
}
