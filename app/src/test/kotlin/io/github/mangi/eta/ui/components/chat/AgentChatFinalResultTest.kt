package io.github.mangi.eta.ui.components.chat

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatFinalResultTest {

    @Test
    fun onlyLastAgentMessageOfEachTurnIsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "查一下资料"),
            AgentMessageUi(id = "agent-1", content = "我来帮你搜索。"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "从搜索结果可以看到……"),
            toolActivity("tool-2"),
            AgentMessageUi(id = "agent-3", content = "最终答案"),
        )

        assertEquals(setOf("agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun multipleTurnsEachHaveTheirOwnFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            AgentMessageUi(id = "agent-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问"),
            AgentMessageUi(id = "agent-2", content = "中间步骤"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-3", content = "第二答"),
        )

        assertEquals(setOf("agent-1", "agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnInterruptedAfterToolKeepsPreviousAgentMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "agent-1", content = "我先试试。"),
            toolActivity("tool-1"),
        )

        assertEquals(setOf("agent-1"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnWithoutAgentMessageProducesNoFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            toolActivity("tool-1"),
        )

        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun streamingTurnDoesNotMarkIntermediateMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "agent-1", content = "我来帮你搜索。"),
            toolActivity("tool-1"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingKeepsFinalResultOfCompletedEarlierTurns() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            AgentMessageUi(id = "agent-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问"),
            AgentMessageUi(id = "agent-2", content = "中间步骤"),
            toolActivity("tool-1"),
        )

        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingEndRestoresFinalResultOfLastTurn() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "agent-1", content = "中间步骤"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "最终答案"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
        assertEquals(
            setOf("agent-2"),
            resolveFinalResultMessageIds(messages, isStreaming = false),
        )
    }

    private fun toolActivity(id: String): AgentChatMessageUi = ToolActivityMessageUi(
        id = id,
        toolName = "browser_use",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "query",
    )
}
