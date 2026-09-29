package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.CodexCompatibilityProfile
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodexRuntimeSecretTest {
    @Test
    fun codexAccessTokenIsNotPutIntoRuntimeBundles() {
        val testCredential = "synthetic-codex-credential-must-not-cross-wire"
        val request = AgentRuntimeWire.RunRequest(
            runId = "codex-wire-secret-test",
            prompt = "test",
            config = AgentModelClient.ModelConfig(
                providerId = "builtin-openai-codex",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
                providerSourceType = ProviderSourceTypes.OPENAI_CODEX,
                authMode = CodexCompatibilityProfile.AUTH_MODE,
                baseUrl = CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL,
                apiKey = testCredential,
                model = "gpt-5.5",
                systemPrompt = "",
                openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
            ),
            images = emptyList(),
        )

        val bundles = listOf(
            AgentRuntimeWire.toLegacyBundle(request),
            AgentRuntimeWire.toBundle(request, emptyList()),
        )

        bundles.forEach { bundle ->
            assertEquals("", bundle.getString("api_key"))
            assertFalse(bundle.getString("api_key").orEmpty().contains(testCredential))
            assertEquals("", AgentRuntimeWire.runRequestFromBundle(bundle).config.apiKey)
            assertEquals(CodexCompatibilityProfile.AUTH_MODE,
                AgentRuntimeWire.runRequestFromBundle(bundle).config.authMode)
        }
    }
}
