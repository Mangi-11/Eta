package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContinuationBuilderTest {
    @Test
    fun rewriteAndCompactionCannotBecomeAnOrdinaryToolEnabledContinuation() {
        listOf(AgentRuntimeWire.OP_REWRITE_REPLY, AgentRuntimeWire.OP_COMPACT).forEach { operation ->
            val request = AgentRuntimeWire.RunRequest(
                runId = "run-old", prompt = "原角色回复", config = modelConfig(), images = emptyList(),
                operation = operation, rewriteTargetMessageId = "assistant-old",
                handoff = AgentRuntimeWire.EntryHandoff("run-old", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                AgentContinuationBuilder.build(request, AgentModelClient.ModelResponse.Text("改写正文"), "继续")
            }
        }
    }

    @Test
    fun continuationPreservesPromptImagesAndFullTranscript() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA==",
            mimeType = "image/png",
            bytes = 1,
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-old",
            prompt = "观察屏幕",
            config = modelConfig(),
            images = listOf(image),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "更早的问题")),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-old",
                source = "agent_ui",
                payload = AgentUiHandoffPayload(conversationId = "conversation-1").toJson(),
            ),
        )
        val response = AgentModelClient.ModelResponse.Text(
            content = "完成",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    toolCallsJson = "[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"observe_screen\",\"arguments\":\"{}\"}}]",
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "call-1",
                    content = "{\"ok\":true}",
                ),
                AgentModelClient.ConversationMessage(role = "assistant", content = "完成"),
            ),
        )

        val continuation = AgentContinuationBuilder.build(
            request = request,
            response = response,
            supplement = "继续检查",
            newRunId = "run-next",
            createdAt = 123L,
        )

        assertEquals("conversation-1", continuation.effectiveModelSessionId)
        assertEquals("run-next", continuation.runId)
        assertEquals("继续检查", continuation.prompt)
        assertTrue(continuation.images.isEmpty())
        assertEquals(
            listOf("user", "user", "assistant", "tool", "assistant"),
            continuation.history.map { it.role },
        )
        assertTrue(continuation.history[1].contentJson.contains("未写入持久会话"))
        assertEquals("user-run-old", continuation.history[1].messageId)
        assertTrue(!continuation.history[1].contentJson.contains("base64"))
        assertEquals("call-1", continuation.history[3].toolCallId)
        assertEquals("run-next", continuation.handoff?.id)
        val payload = AgentUiHandoffPayload.from(continuation.handoff?.payload.orEmpty())
        assertEquals("conversation-1", payload.conversationId)
        assertEquals(
            AgentUiHandoffPayload.Supplement(
                index = 1,
                text = "继续检查",
                createdAt = 123L,
            ),
            payload.promptSupplement,
        )
        assertTrue(payload.supplements.isEmpty())
    }

    @Test
    fun entryContinuationKeepsInitialSessionAcrossRuns() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "entry-run", prompt = "开始", config = modelConfig(), images = emptyList(),
        )
        val continuation = AgentContinuationBuilder.build(
            request, AgentModelClient.ModelResponse.Text("完成"), "继续", newRunId = "next-run",
        )
        assertEquals("entry-run", continuation.effectiveModelSessionId)
    }

    @Test
    fun continuationHistoryPreservesTheInitialSupplementMessageIdentifier() {
        val payload = AgentUiHandoffPayload("conversation", promptSupplement = AgentUiHandoffPayload.Supplement(7, "续接正文", 1L))
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-old", prompt = "续接正文", config = modelConfig(), images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff("run-old", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, payload.toJson()),
        )
        val continuation = AgentContinuationBuilder.build(request, AgentModelClient.ModelResponse.Text("完成"), "继续")
        assertEquals("user-run-old-supplement-7", continuation.history.single().messageId)
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        )
}
