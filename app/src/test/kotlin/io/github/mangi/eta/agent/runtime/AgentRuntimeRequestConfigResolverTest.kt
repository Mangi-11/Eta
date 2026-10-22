package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.CustomHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentRuntimeRequestConfigResolverTest {
    @Test
    fun oauthRequestUsesLocalConnectionAndPreservesSupportedTransientChoices() {
        val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
            providerId = "account-provider",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            baseUrl = "https://api.openai.com/v1",
            customHeaders = listOf(CustomHeader("x-local", "fixture")),
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
            ),
        )
        val entry = local.copy(
            apiKey = "fixture-untrusted", baseUrl = "https://untrusted.invalid",
            customHeaders = listOf(CustomHeader("x-untrusted", "fixture")),
            reasoningEffort = ReasoningEffort.HIGH,
            terminalTools = true, deviceSensitiveReadTools = true,
            autoCompactionEnabled = false,
        )
        val request = request("breeno", entry)
        assertTrue(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        val result = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local).config

        assertEquals(local.baseUrl, result.baseUrl)
        assertEquals("", result.apiKey)
        assertEquals(local.customHeaders, result.customHeaders)
        assertEquals(ReasoningEffort.HIGH, result.reasoningEffort)
        assertTrue(result.terminalTools)
        assertTrue(result.deviceSensitiveReadTools)
        assertFalse(result.autoCompactionEnabled)
    }

    @Test
    fun oauthRequestCannotChangeLocalAccountOrInjectUnsupportedReasoning() {
        val local = modelConfig("", ReasoningEffort.DEFAULT).copy(
            providerId = "account-provider", authMode = ProviderAuthModes.XAI_GROK,
            reasoningCapabilities = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW)),
        )
        val request = request("xiaoai", local.copy(reasoningEffort = ReasoningEffort.MAX))
        assertEquals(ReasoningEffort.LOW,
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local).config.reasoningEffort)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, local.copy(providerId = "other-provider"))
        }
    }

    @Test
    fun etaVoiceRequestUsesRuntimeConfigAndUpdatesArchiveMetadata() {
        val runtimeConfig = modelConfig("runtime-key", ReasoningEffort.HIGH)
        val request = request(
            source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            config = modelConfig("entry-key", ReasoningEffort.OFF),
        )

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
        val payload = AgentExternalArchivePayload.from(requireNotNull(resolved.handoff).payload)

        assertTrue(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertEquals("runtime-key", resolved.config.apiKey)
        requireNotNull(payload)
        assertTrue(requireNotNull(payload.thinkingEnabled))
        assertEquals(ReasoningEffort.HIGH, payload.reasoningEffort)
    }

    @Test
    fun etaVoiceRequestKeepsEntryToolSwitchesWhileTakingRuntimeModel() {
        val entry = modelConfig("entry-key", ReasoningEffort.OFF).copy(
            terminalTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            browserTools = false,
        )
        val request = request(source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, config = entry)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        ).config

        assertEquals("runtime-key", resolved.apiKey)
        assertTrue(resolved.terminalTools)
        assertTrue(resolved.deviceSensitiveReadTools)
        assertTrue(resolved.deviceSensitiveActionTools)
        assertFalse(resolved.browserTools)
    }

    @Test
    fun externalAssistantRequestKeepsEntryConfig() {
        val entryConfig = modelConfig("entry-key", ReasoningEffort.OFF)
        val request = request(source = "breeno", config = entryConfig)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        )

        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertSame(request, resolved)
        assertSame(entryConfig, resolved.config)
    }

    private fun request(
        source: String,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest = AgentRuntimeWire.RunRequest(
        runId = "run-1",
        prompt = "测试",
        config = config,
        images = emptyList(),
        handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = source,
            payload = AgentExternalArchivePayload(
                userText = "测试",
                conversationKey = "conversation-1",
                title = "测试",
            ).toJson(),
        ),
    )

    private fun modelConfig(
        apiKey: String,
        reasoningEffort: ReasoningEffort,
    ): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        model = "test-model",
        systemPrompt = "",
        thinkingEnabled = reasoningEffort.enablesReasoning,
        reasoningEffort = reasoningEffort,
    )
}
