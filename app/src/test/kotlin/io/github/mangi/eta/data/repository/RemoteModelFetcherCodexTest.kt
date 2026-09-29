package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.CodexCompatibilityProfile
import io.github.mangi.eta.agent.model.CodexCredentials
import io.github.mangi.eta.agent.model.CodexResponsesProvider
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelFetcherCodexTest {
    @Test
    fun modelsUrlUsesIndependentCodexCatalogCompatibilityVersion() {
        val url = RemoteModelFetcher.codexModelsUrl().toHttpUrl()

        assertEquals("/backend-api/codex/models", url.encodedPath)
        assertEquals("0.153.0", url.queryParameter("client_version"))
        assertFalse(url.queryParameter("client_version") == "3.0.5")
    }

    @Test
    fun parsesCodexSnakeCaseCatalogAndMaxReasoningCapability() {
        val models = RemoteModelFetcher.parseCodexModelsStrict(
            """
            {
              "models": [
                {
                  "slug": "gpt-6-astra",
                  "display_name": "GPT-6 Astra",
                  "visibility": "list",
                  "priority": 1,
                  "context_window": 272000,
                  "max_context_window": 872000,
                  "default_reasoning_level": "low",
                  "supported_reasoning_levels": [
                    {"effort": "low"},
                    {"effort": "high"},
                    {"effort": "max"},
                    {"effort": "ultra"}
                  ],
                  "input_modalities": ["text", "image"],
                  "tool_call": true
                },
                {"slug": "internal-preview", "visibility": "hide"}
              ]
            }
            """.trimIndent(),
        )

        val model = models.single()
        assertEquals("gpt-6-astra", model.modelId)
        assertEquals("GPT-6 Astra", model.displayName)
        assertEquals(272000, model.contextWindow)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsTools)
        assertEquals(ReasoningEffort.LOW, model.reasoningCapabilities?.defaultEffort)
        assertTrue(ReasoningEffort.MAX in model.reasoningCapabilities?.supportedEfforts.orEmpty())
        assertFalse(ReasoningEffort.DEFAULT in model.reasoningCapabilities?.supportedEfforts.orEmpty())
    }

    @Test
    fun catalogRequestUsesAccountIdentityAndDoesNotForwardProviderCustomHeaders() {
        val fakeCredentials = fakeCredentials()
        val provider = OpenAiCompatibleProviderSetting(
            id = "codex-test-provider",
            name = "Codex test",
            baseUrl = CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL,
            sourceType = ProviderSourceTypes.OPENAI_CODEX,
            customHeaders = listOf(
                CustomHeader("x-custom-token", "test-only-not-for-upstream"),
                CustomHeader("x-provider-debug", "test-only"),
            ),
        )

        val request = RemoteModelFetcher.buildCodexModelsRequest(provider, fakeCredentials)

        assertEquals("Bearer ${fakeCredentials.accessToken}", request.header("Authorization"))
        assertEquals("codex-test-account", request.header("ChatGPT-Account-ID"))
        assertEquals("us", request.header("x-openai-internal-codex-residency"))
        assertEquals("Eta/3.0.5", request.header("User-Agent"))
        assertEquals("eta", request.header("originator"))
        assertNull(request.header("x-custom-token"))
        assertNull(request.header("x-provider-debug"))
        assertNull(request.header("session_id"))
        assertNull(request.header("x-client-request-id"))
    }

    @Test
    fun responsesRequestConfigDropsProviderAndModelCustomHeaders() {
        val source = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid",
            apiKey = "",
            model = "gpt-6-astra",
            systemPrompt = "",
            customHeaders = listOf(
                CustomHeader("x-custom-token", "test-only-not-for-upstream"),
                CustomHeader("x-provider-debug", "test-only"),
            ),
        )

        val requestConfig = CodexResponsesProvider.buildRequestConfig(
            sourceConfig = source,
            accessToken = "fake-codex-access-token",
            claims = CodexCompatibilityProfile.JwtClaims(
                accountId = "codex-test-account",
                residency = "us",
            ),
            sessionId = "test-session-id",
        )

        assertEquals(CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL, requestConfig.baseUrl)
        assertEquals(CodexCompatibilityProfile.AUTH_MODE, requestConfig.authMode)
        assertTrue(requestConfig.customHeaders.none { it.name.equals("x-custom-token", true) })
        assertTrue(requestConfig.customHeaders.none { it.name.equals("x-provider-debug", true) })
        assertTrue(requestConfig.customHeaders.any { it.name.equals("ChatGPT-Account-ID", true) })
    }

    @Test
    fun unauthorizedCatalogFailureIsTypedAndKeepsFallbackActionableMessage() {
        val message = CodexUiError.models(CodexBackendHttpException(401))

        assertTrue(message.contains("401"))
        assertTrue(message.contains("兜底"))
        assertFalse(message.contains("test-only"))
    }

    private fun fakeCredentials(): CodexCredentials {
        val claims = JSONObject()
            .put(
                "https://api.openai.com/auth",
                JSONObject()
                    .put("chatgpt_account_id", "codex-test-account")
                    .put("chatgpt_data_residency", "us"),
            )
        val payload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(claims.toString().toByteArray(Charsets.UTF_8))
        return CodexCredentials(
            accessToken = "test-header.$payload.test-signature",
            refreshToken = "fake-codex-refresh-token",
            expiresAtEpochMillis = null,
            accountId = "codex-fallback-account",
        )
    }
}
