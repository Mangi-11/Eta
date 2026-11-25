package io.github.mangi.eta.ui.state

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test
    fun editingUserTurnCoveredBySummaryDropsStaleSummary() {
        val summary = AgentModelClient.ConversationMessage("assistant", "旧操作结果", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("old", "旧请求"), UserMessageUi("latest", "最新请求")),
            history = listOf(summary, AgentModelClient.ConversationMessage("user", "最新请求")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "latest")!!
        assertTrue(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        val next = state.copy(messages = state.messages + UserMessageUi("next", "后续请求"),
            history = state.history + AgentModelClient.ConversationMessage("user", "后续请求"))
        assertTrue(AgentConversationRevisionReducer.boundary(next, "next")!!.historyPrefix.contains(summary))
    }

    @Test
    fun editingTurnAfterCompactionKeepsActiveSummaryHistoryAndTrimsJournal() {
        val summary = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "前两轮摘要",
            contextSummary = true,
            compactedUserTurns = 2,
            summaryThroughUserTurn = 2,
        )
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("user-1", "第一问"),
                AgentMessageUi("assistant-1", "第一答"),
                UserMessageUi("user-2", "第二问"),
                AgentMessageUi("assistant-2", "第二答"),
                UserMessageUi("user-3", "第三问"),
                AgentMessageUi("assistant-3", "第三答"),
            ),
            history = listOf(
                summary,
                AgentModelClient.ConversationMessage("user", "第三问"),
                AgentModelClient.ConversationMessage("assistant", "第三答"),
            ),
            journal = listOf(
                AgentModelClient.ConversationMessage("user", "第一问"),
                AgentModelClient.ConversationMessage("assistant", "第一答"),
                AgentModelClient.ConversationMessage("user", "第二问"),
                AgentModelClient.ConversationMessage("assistant", "第二答"),
                AgentModelClient.ConversationMessage("user", "第三问"),
                AgentModelClient.ConversationMessage("assistant", "第三答"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )

        val boundary = AgentConversationRevisionReducer.boundary(state, "user-3")!!

        assertFalse(boundary.contextWasCompacted)
        assertEquals(listOf(summary), boundary.historyPrefix)
        assertEquals(
            listOf("第一问", "第一答", "第二问", "第二答"),
            boundary.journalPrefix.map { it.content },
        )
    }

    @Test
    fun editingFirstTurnClearsBothHistoryAndJournalPrefixes() {
        val state = conversationState().copy(journal = conversationState().history)

        val boundary = AgentConversationRevisionReducer.boundary(state, "user-1")!!

        assertFalse(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        assertTrue(boundary.journalPrefix.isEmpty())
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "已压缩"),
                AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            ThinkingMessageUi(id = "thinking-1", content = "思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "第二答"),
            UserMessageUi(id = "user-3", content = "第三问"),
            AgentMessageUi(id = "assistant-3", content = "第三答"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        ),
        input = "草稿",
        isStreaming = false,
        thinkingEnabled = false,
    )
}
