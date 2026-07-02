package fuck.andes.agent.overlay

import fuck.andes.agent.runtime.AgentEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayVisibilityPolicyTest {
    @Test
    fun `text-only and background tool events do not reveal operation overlay`() {
        val events = listOf(
            AgentEvent.RunStarted(
                initialImages = 0,
                initialImageBytes = 0,
                toolCount = 24,
                terminalTools = false
            ),
            AgentEvent.RoundStarted(round = 1, messageCount = 2),
            AgentEvent.ProviderRequestStarted(round = 1),
            AgentEvent.ProviderResponseStarted(round = 1, httpCode = 200),
            AgentEvent.AssistantReasoningDelta(round = 1, deltaChars = 7, delta = "thinking"),
            AgentEvent.AssistantTextDelta(round = 1, deltaChars = 5, delta = "hello"),
            AgentEvent.AssistantReceived(
                round = 1,
                contentChars = 5,
                reasoningContent = "",
                toolNames = emptyList()
            ),
            AgentEvent.ProviderToolCallDelta(
                round = 1,
                index = 0,
                name = "terminal",
                argumentsChars = 12
            ),
            AgentEvent.AssistantReceived(
                round = 1,
                contentChars = 0,
                reasoningContent = "",
                toolNames = listOf("run_command", "observe_screen")
            ),
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_status",
                name = "run_command",
                argsPreview = "{}"
            ),
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_status",
                name = "run_command",
                resultSummary = "ok",
                imageCount = 0,
                imageBytes = 0
            ),
            AgentEvent.RunFinished(round = 1, contentChars = 5)
        )

        assertFalse(events.any(AgentOverlayVisibilityPolicy::shouldRevealFor))
    }

    @Test
    fun `foreground operation tools reveal operation overlay`() {
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ProviderToolCallDelta(
                    round = 1,
                    index = 0,
                    name = "tap",
                    argumentsChars = 12
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.AssistantReceived(
                    round = 1,
                    contentChars = 0,
                    reasoningContent = "",
                    toolNames = listOf("search_apps", "launch_app")
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_1",
                    name = "tap",
                    argsPreview = "{}"
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call_1",
                    name = "tap",
                    resultSummary = "ok",
                    imageCount = 0,
                    imageBytes = 0
                )
            )
        )
    }
}
