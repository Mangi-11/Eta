package fuck.andes.agent.runtime

import fuck.andes.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeWireTest {
    @Test
    fun runRequestBundleRoundTripPreservesConfigHistoryAndImages() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-1",
            prompt = "继续分析",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "test-key",
                model = "qwen3-max",
                systemPrompt = "你是手机 Agent",
                terminalTools = true,
                thinkingEnabled = true,
                extraBodyJson = """{"thinking_budget":256}""",
            ),
            images = listOf(
                AgentModelClient.ModelImage(
                    dataUrl = "data:image/png;base64,abc",
                    mimeType = "image/png",
                    bytes = 123,
                    width = 1080,
                    height = 2400,
                    source = "screenshot",
                )
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "上一轮问题",
                ),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "上一轮回答",
                ),
            ),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "handoff-1",
                source = "overlay",
                payload = """{"package":"com.tencent.mm"}""",
            ),
        )

        val roundTripped = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toBundle(request))

        assertEquals(request, roundTripped)
    }

    @Test
    fun eventBundleRoundTripPreservesReasoningAndUsage() {
        val events = listOf(
            AgentEvent.AssistantReasoningDelta(
                round = 2,
                deltaChars = 4,
                delta = "思考",
            ),
            AgentEvent.AssistantReceived(
                round = 2,
                contentChars = 12,
                reasoningContent = "完整思考内容",
                toolNames = listOf("observe_screen", "input_text"),
            ),
            AgentEvent.UsageReceived(
                round = 2,
                usage = AgentTokenUsage(
                    contextTokens = 4096,
                    inputTokens = 1200,
                    outputTokens = 320,
                    reasoningTokens = 80,
                    cachedTokens = 900,
                ),
            ),
        )

        events.forEach { event ->
            assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        }
    }
}
