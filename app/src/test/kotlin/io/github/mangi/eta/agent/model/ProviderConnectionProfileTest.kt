package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun grokUsesResponsesForPreviouslySavedChatConnections() {
        val original = config().copy(
            authMode = ProviderAuthModes.XAI_GROK,
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
        )
        val effective = ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig
        assertEquals(OpenAiEndpointMode.RESPONSES, effective.openAiEndpointMode)
        assertEquals("https://cli-chat-proxy.grok.com/v1", effective.baseUrl)
        assertEquals(OpenAiResponsesProvider, ProviderClientFactory.getClient(original))
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, original.openAiEndpointMode)
    }

    @Test
    fun grokSubscriptionAllowsOnlyItsCatalogAndResponsesRoutes() {
        val profile = ProviderConnectionProfile.XAI_GROK
        listOf("https://cli-chat-proxy.grok.com/v1/models", "https://cli-chat-proxy.grok.com/v1/responses").forEach {
            profile.requireRequestTarget(it.toHttpUrl())
        }
        listOf(
            "https://cli-chat-proxy.grok.com/v1/chat/completions",
            "https://api.x.ai/v1/responses",
            "https://cli-chat-proxy.grok.com.evil.test/v1/responses",
            "https://cli-chat-proxy.grok.com/v1/responses?target=elsewhere",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { profile.requireRequestTarget(url.toHttpUrl()) }
        }
    }

    @Test
    fun grokBodyCannotOverrideSelectedModelOrDisableStreaming() {
        val request = OpenAiResponsesProvider.buildRequestJson(
            config().copy(
                authMode = ProviderAuthModes.XAI_GROK,
                extraBodyJson = """{"model":"unexpected-model","stream":false}""",
            ),
            JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")),
            JSONArray(),
        )
        assertEquals("grok-fixture", request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        assertEquals(1, request.getJSONArray("input").length())
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
