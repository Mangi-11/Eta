package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExternalConversationHistoryTest {
    private fun state() = AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false)
    private fun reply(runId: String) = ConversationMessage(role = "assistant", content = "已完成", messageId = "assistant-$runId-1")

    @Test
    fun importedUserAndToolTranscriptSurviveFollowUpAndIdempotentRecovery() {
        val tool = ConversationMessage(role = "tool", content = "result", toolCallId = "call-1")
        val first = AgentRuntimeHistoryReducer.apply(
            ExternalConversationHistory.appendUser(state(), "one", "请记住我的约束"), "one", listOf(reply("one"), tool),
        ).state
        val second = AgentRuntimeHistoryReducer.apply(
            ExternalConversationHistory.appendUser(first, "two", "继续"), "two", listOf(reply("two")),
        ).state
        assertEquals(listOf("user", "assistant", "tool", "user", "assistant"), second.history.map { it.role })
        assertEquals("请记住我的约束", second.history.first().content)
        assertEquals(second.history, second.journal)
        assertEquals(second, AgentRuntimeHistoryReducer.apply(second, "two", listOf(reply("two"))).state)
    }

    @Test
    fun repairsOldImportsInOrderWithoutDroppingToolRecordsOrDuplicatingUsers() {
        val old = state().copy(messages = listOf(UserMessageUi("user-one", "最初要求"), UserMessageUi("user-two", "继续")),
            history = listOf(reply("one"), reply("two")))
        val fixed = ExternalConversationHistory.repair("archive-test", old)
        assertEquals(listOf("最初要求", "已完成", "继续", "已完成"), fixed.history.map { it.content })
        assertEquals(fixed, ExternalConversationHistory.repair("archive-test", fixed))
        assertSame(old, ExternalConversationHistory.repair("ordinary", old))
    }

    @Test
    fun repairDoesNotExpandCompactedModelHistoryButRestoresTheJournal() {
        val summary = ConversationMessage(role = "system", content = "压缩摘要", contextSummary = true, compactedUserTurns = 1)
        val old = state().copy(messages = listOf(UserMessageUi("user-one", "最初要求")),
            history = listOf(summary), journal = listOf(reply("one")))
        val fixed = ExternalConversationHistory.repair("archive-test", old)
        assertEquals(listOf(summary), fixed.history)
        assertEquals(listOf("user", "assistant"), fixed.journal.map { it.role })
    }
}
