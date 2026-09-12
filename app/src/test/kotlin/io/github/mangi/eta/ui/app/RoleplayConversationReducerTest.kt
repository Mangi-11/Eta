package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class RoleplayConversationReducerTest {
    private fun conversation(): AgentChatUiState {
        val journal = listOf(
            ConversationMessage("assistant", "你好", messageId = "greeting"),
            ConversationMessage("user", "查询日历", messageId = "user-r1"),
            ConversationMessage("assistant", toolCallsJson = """[{"id":"tool-1","type":"function","function":{"name":"get_current_context","arguments":"{}"}}]""", messageId = "assistant-r1-1"),
            ConversationMessage("tool", "今天有会议", toolCallId = "tool-1"),
            ConversationMessage("assistant", "今天有会议。", messageId = "assistant-r1-2"),
            ConversationMessage("user", "谢谢", messageId = "user-r2"),
        )
        return RoleplayConversationReducer.linkRun(AgentChatUiState(
            messages = listOf(AgentMessageUi("greeting", "你好"), UserMessageUi("user-r1", "查询日历"),
                AgentMessageUi("assistant-r1-2-0", "今天有会议。"), UserMessageUi("user-r2", "谢谢")),
            history = journal, journal = journal, input = "", isStreaming = false, thinkingEnabled = false,
            roleplay = RoleplayBinding("c1", "{}", "角色"),
        ), "r1")
    }

    @Test fun editingUserPreservesFollowingMessagesAndExecutionAudit() {
        val state = conversation()
        val edited = requireNotNull(RoleplayConversationReducer.edit(state, "user-r1", "修改日历"))
        assertEquals(state.journal, edited.journal)
        assertEquals(state.messages.size, edited.messages.size)
        assertEquals("谢谢", (edited.messages.last() as UserMessageUi).content)
        assertTrue(edited.history.any { it.role == "system" && it.content.contains("原请求") })
        assertEquals("今天有会议", edited.history.first { it.role == "tool" }.content)
        assertEquals("修改日历", edited.history.first { it.messageId == "user-r1" }.content)
    }

    @Test fun rewriteUsesOnlyPrefixAndRetainsToolEvidence() {
        val history = requireNotNull(RoleplayConversationReducer.rewriteHistory(conversation(), "assistant-r1-2-0"))
        assertEquals("tool", history.last().role)
        assertFalse(history.any { it.messageId == "user-r2" })
        assertFalse(history.any { it.messageId == "assistant-r1-2" })
    }

    @Test fun rewriteResultIsIdempotentAndCandidatesCanBeRestored() {
        val state = conversation()
        val result = AgentRuntimeWire.RunResult("rewrite-1", true, "你的日历上今天有会议。",
            operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = "assistant-r1-2-0")
        val rewritten = RoleplayConversationReducer.applyRewrite(state, result.runId, result)
        assertEquals(state.journal, rewritten.journal)
        assertEquals(2, rewritten.roleplayMessages.revisions.getValue("assistant-r1-2-0").candidates.size)
        assertEquals(rewritten, RoleplayConversationReducer.applyRewrite(rewritten, result.runId, result))
        val restored = requireNotNull(RoleplayConversationReducer.select(rewritten, "assistant-r1-2-0", 0))
        assertEquals("今天有会议。", restored.history.first { it.messageId == "assistant-r1-2" }.content)
        assertEquals("谢谢", (restored.messages.last() as UserMessageUi).content)
    }

    @Test fun interruptedRewriteKeepsOriginalAndRemovesPendingMarker() {
        val initial = conversation()
        val state = initial.copy(roleplayMessages = initial.roleplayMessages.copy(
            pendingRewrites = mapOf("rewrite-1" to "assistant-r1-2-0")), isStreaming = true)
        val result = AgentRuntimeWire.RunResult("rewrite-1", false, "", "已停止", operation = AgentRuntimeWire.OP_REWRITE_REPLY)
        val recovered = AgentPendingResultRecovery.apply(state, result.runId, result, supplements = emptyList()).state
        assertEquals(state.journal, recovered.journal)
        assertEquals(state.history, recovered.history)
        assertEquals("今天有会议。", recovered.messages.filterIsInstance<AgentMessageUi>().last().content)
        assertTrue(recovered.roleplayMessages.pendingRewrites.isEmpty())
        assertFalse(recovered.isStreaming)
    }

    @Test fun greetingCanBeEditedWithoutUserTurnAndDuplicateTextDoesNotChooseTarget() {
        val state = conversation()
        val edited = requireNotNull(RoleplayConversationReducer.edit(state, "greeting", "新的开场白"))
        assertEquals("新的开场白", edited.history.first().content)
        assertEquals(state.journal, edited.journal)
        assertNull(RoleplayConversationReducer.edit(state, "missing", "今天有会议。"))
    }

    @Test fun rewriteOfLaterBlockKeepsEarlierSiblingWithoutFabricatingToolCalls() {
        val original = conversation()
        val state = RoleplayConversationReducer.linkRun(original.copy(messages = original.messages.flatMap {
            if (it.id == "assistant-r1-2-0") listOf(AgentMessageUi(it.id, "第一段"), AgentMessageUi("assistant-r1-2-1", "第二段"))
            else listOf(it)
        }), "r1")
        val prefix = requireNotNull(RoleplayConversationReducer.rewriteHistory(state, "assistant-r1-2-1"))
        assertEquals("第一段", prefix.last().content)
        assertEquals("", prefix.last().toolCallsJson)
        assertFalse(prefix.any { it.content == "第二段" })
    }

    @Test fun legacySummaryIsRetainedWhenItIsTheOnlyEarlyHistory() {
        val state = conversation()
        val summary = ConversationMessage("assistant", "已有早期摘要", contextSummary = true, compactedUserTurns = 5)
        val legacy = state.copy(journal = listOf(summary) + state.journal)
        val edited = requireNotNull(RoleplayConversationReducer.edit(legacy, "user-r1", "新正文"))
        assertTrue(edited.history.contains(summary))
        assertTrue(edited.history.any { it.content.contains("已缺失的历史") })
    }

    @Test fun interruptedPendingSupplementRemainsEditableAfterRecovery() {
        val base = conversation()
        val supplement = UserMessageUi("user-r3-supplement-1", "补充说明")
        val restored = AgentRuntimeHistoryReducer.apply(
            state = base.copy(messages = base.messages + supplement), runId = "r3",
            additions = emptyList(), retainPendingSupplements = true,
        ).state.let { RoleplayConversationReducer.linkRun(it, "r3") }
        val edited = requireNotNull(RoleplayConversationReducer.edit(restored, supplement.id, "更正的说明"))
        assertEquals(supplement.id, edited.journal.last().messageId)
        assertEquals("补充说明", edited.journal.last().content)
        assertEquals("更正的说明", edited.history.last().content)
    }

    @Test fun mismatchedRewriteTargetCannotModifyAnotherReply() {
        val state = RoleplayConversationReducer.restorePendingRewrite(conversation(), "rewrite-1", "assistant-r1-2-0")
        val result = AgentRuntimeWire.RunResult("rewrite-1", true, "不应覆盖开场白",
            operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = "greeting")
        val recovered = RoleplayConversationReducer.applyRewrite(state, "rewrite-1", result)
        assertTrue(recovered.roleplayMessages.revisions.isEmpty())
        assertEquals(state.history, recovered.history)
        assertEquals("你好", (recovered.messages.first() as AgentMessageUi).content)
    }
}
