package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeRequestConfigResolverTest {
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
