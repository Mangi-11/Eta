package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeHistoryReducerTest {
    @Test
    fun journalRetainsOriginalsAndContextIncludesPostSnapshotSteps() {
        val original = AgentModelClient.ConversationMessage("user", "不能丢失的旧约束")
        val first = AgentModelClient.ConversationMessage("assistant", "快照前已执行")
        val later = AgentModelClient.ConversationMessage("assistant", "快照后已执行")
        val summary = AgentModelClient.ConversationMessage("assistant", "摘要", contextSummary = true)
        val state = AgentChatUiState(emptyList(), history = listOf(original), journal = listOf(original),
            input = "", isStreaming = false, thinkingEnabled = false)
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(operationId = "run", messages = listOf(summary),
            consumedUserTurns = 1, consumedTranscriptMessages = 1)
        val result = AgentRuntimeHistoryReducer.apply(state, "run", listOf(first, later), snapshot).state
        assertEquals(listOf(original, first, later), result.journal)
        assertEquals(listOf(summary, later), result.history)
        assertEquals(result, AgentRuntimeHistoryReducer.apply(result, "run", listOf(first, later), snapshot).state)
    }

    @Test
    fun editingSummarizedTurnRebuildsFromOriginalJournal() {
        val first = AgentModelClient.ConversationMessage("user", "原始指令")
        val answer = AgentModelClient.ConversationMessage("assistant", "原始结果")
        val second = AgentModelClient.ConversationMessage("user", "后续问题")
        val summary = AgentModelClient.ConversationMessage("assistant", "不完整摘要", contextSummary = true, compactedUserTurns = 2)
        val state = AgentChatUiState(listOf(
            io.github.mangi.eta.ui.model.UserMessageUi("u1", "原始指令"),
            io.github.mangi.eta.ui.model.UserMessageUi("u2", "后续问题"),
        ), history = listOf(summary), journal = listOf(first, answer, second),
            input = "", isStreaming = false, thinkingEnabled = false)
        val boundary = checkNotNull(AgentConversationRevisionReducer.boundary(state, "u2"))
        assertEquals(listOf(first, answer), boundary.historyPrefix)
        org.junit.Assert.assertFalse(boundary.contextWasCompacted)
    }

    @Test
    fun replacementKeepsLaterUserMessagesAndIsAppliedOnce() {
        val old = AgentModelClient.ConversationMessage("user", "旧问题")
        val later = AgentModelClient.ConversationMessage("user", "后来追加")
        val summary = AgentModelClient.ConversationMessage("assistant", "摘要", contextSummary = true, compactedUserTurns = 1)
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(
            operationId = "run-1", messages = listOf(summary), consumedUserTurns = 1, coveredUserTurns = 1,
        )
        val state = AgentChatUiState(emptyList(), history = listOf(old, later), input = "", isStreaming = false, thinkingEnabled = false)
        val first = AgentRuntimeHistoryReducer.apply(state, "run-1", listOf(AgentModelClient.ConversationMessage("assistant", "不应重复追加")), snapshot)
        assertEquals(listOf(summary, later), first.state.history)
        val again = AgentRuntimeHistoryReducer.apply(first.state, "run-1", emptyList(), snapshot)
        assertTrue(again.alreadyApplied)
        assertEquals(first.state.history, again.state.history)
    }

    @Test
    fun snapshotKeepsAcceptedButUnconsumedSteeringAfterCancellation() {
        val initial = AgentChatUiState(
            messages = listOf(
                io.github.mangi.eta.ui.model.UserMessageUi("user-run-supplement-1", "已消费"),
                io.github.mangi.eta.ui.model.UserMessageUi("user-run-supplement-2", "待消费"),
            ), history = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
        )
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(
            operationId = "run", messages = listOf(AgentModelClient.ConversationMessage("assistant", "已完成前序步骤")),
            consumedSupplementCount = 1,
        )
        val result = AgentRuntimeHistoryReducer.apply(initial, "run", emptyList(), snapshot)
        assertEquals(listOf("已完成前序步骤", "待消费"), result.state.history.map { it.content })
    }

    @Test
    fun recoveryThenLiveDeliveryCommitsTranscriptOnlyOnce() {
        val initial = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "完成")
        )

        val recovered = AgentRuntimeHistoryReducer.apply(initial, "run-1", transcript)
        val live = AgentRuntimeHistoryReducer.apply(recovered.state, "run-1", transcript)

        assertTrue(live.alreadyApplied)
        assertEquals(transcript, live.state.history)
        assertEquals(listOf("run-1"), live.state.appliedRuntimeRunIds)
    }
}
