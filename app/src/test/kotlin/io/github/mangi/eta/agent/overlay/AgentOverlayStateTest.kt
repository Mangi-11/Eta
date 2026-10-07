package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentOverlayStateTest {
    @Test
    fun internalStepsCollapseIntoThinkingAndDoNotFlickerDuringTools() {
        var state = AgentOverlayState.Initial.applyEvent(AgentEvent.RunStarted(0, 0, 10, false))
        val internalSteps = listOf(
            AgentEvent.RoundStarted(1, 2),
            AgentEvent.ProviderRequestStarted(1),
            AgentEvent.ProviderResponseStarted(1, 200),
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap"),
            AgentEvent.AssistantReceived(1, 0, "", listOf("tap")),
        )
        internalSteps.forEach { state = state.applyEvent(it) }
        assertEquals(AgentOverlayStatus.Reasoning, state.status)

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap", "{}"))
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)
        // 工具执行中到达的截图、重试、压缩事件不改写当前文字。
        listOf(
            AgentEvent.ToolImagesAttached(1, "tap", 1, 10),
            AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "MODEL_TIMEOUT"),
            AgentEvent.ProviderRequestStarted(2),
        ).forEach { state = state.applyEvent(it) }
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)

        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0))
        assertEquals(AgentOverlayStatus.Reasoning, state.status)
    }

    @Test
    fun pausedStateSurvivesInFlightEventsUntilRunEnds() {
        val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        listOf(
            AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0),
            AgentEvent.ToolStarted(2, "call-2", "swipe", "{}"),
            AgentEvent.ProviderRequestStarted(2),
        ).forEach { assertEquals(paused, paused.applyEvent(it)) }
        assertEquals(AgentOverlayPhase.FAILED, paused.applyEvent(AgentEvent.RunFailed("已停止")).phase)
    }

    @Test
    fun thoughtKeepsLatestReasoningTailAndSwitchesToToolSummary() {
        var state = AgentOverlayState.Initial
            .applyEvent(AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 0))
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "用户想订\n  明天的"))
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "高铁票"))
        assertEquals("用户想订 明天的高铁票", state.thought)

        val long = "很长的推理".repeat(40)
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, long))
        assertEquals(96, state.thought.length)
        assertEquals(true, state.thought.endsWith("很长的推理"))

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap_element", "点击元素「搜索」"))
        assertEquals("点击元素「搜索」", state.thought)
        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap_element", "ok", 0, 0))
        assertEquals("", state.thought)

        state = state.applyEvent(AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "旧想法"))
        state = state.applyEvent(AgentEvent.AssistantBlockStart(2, AgentEvent.AssistantBlockKind.THINKING, 1))
        assertEquals("", state.thought)
    }
}
