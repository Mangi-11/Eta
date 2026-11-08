package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ConversationBranchMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationBranchBoundaryTest {
    private val emptyRole = RoleplayMessageState()

    @Test
    fun onlyClosedFinalRepliesAreCandidates() {
        val rows = outline("user" to "u1", "assistant" to "a1", "tool" to "t1", "assistant" to "a2",
            "user" to "u2", "assistant" to "a3", "system_notice" to "failed")
        assertEquals(setOf("a2"), ConversationBranchBoundary.eligibleMessageIds(rows))
        assertEquals(emptySet<String>(), ConversationBranchBoundary.eligibleMessageIds(rows.take(4), running = true))
        for (code in listOf("stopped", "runtime_failed", "interrupted", "empty_result", "model_retry")) {
            assertEquals(emptySet<String>(), ConversationBranchBoundary.eligibleMessageIds(
                outline("user" to "u", "assistant" to "a") + ConversationBranchMessage("notice", 2, "system_notice", true, code)))
        }
    }

    @Test
    fun supplementsDoNotMakeAnIntermediateReplyBranchable() {
        val rows = outline("user" to "user-run", "assistant" to "assistant-run-1-0",
            "user" to "user-run-supplement-1", "assistant" to "assistant-run-2-0")
        assertEquals(setOf("assistant-run-2-0"), ConversationBranchBoundary.eligibleMessageIds(rows))
        assertEquals(emptySet<String>(), ConversationBranchBoundary.eligibleMessageIds(rows, running = true))
    }

    @Test
    fun stableIdsIncludeAllBlocksAndToolsButExcludeLaterTurns() {
        val rows = outline("user" to "u1", "tool" to "tool", "assistant" to "assistant-r-2-0",
            "assistant" to "assistant-r-2-1", "user" to "u2", "assistant" to "a2")
        val journal = listOf(user("u1"), call(), result(), assistant("assistant-r-2", "第一块\n\n第二块"),
            user("u2"), assistant("a2", "后续内容"))
        assertEquals(journal.take(4), ConversationBranchBoundary.resolve(rows, journal, emptyRole, "assistant-r-2-1"))
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal, emptyRole, "assistant-r-2-0")
        }
    }

    @Test
    fun repeatedTextDoesNotChangeTheSelectedBoundary() {
        val rows = outline("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2")
        val journal = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals(journal.take(2), ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a1"))
        assertEquals(journal, ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a2"))
    }

    @Test
    fun missingOrDuplicateToolResultsCannotBecomeAClosedPrefix() {
        val rows = outline("user" to "u", "assistant" to "a")
        for (journal in listOf(
            listOf(user("u"), call(), assistant("a")),
            listOf(user("u"), call(), result(), result(), assistant("a")),
            listOf(user("u"), assistant("a"), call(), result()),
            listOf(user("u"), call().copy(messageId = "a", content = "仍在调用工具")),
        )) assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a")
        }
    }

    @Test
    fun historyWithoutStableIdsIsRejected() {
        val rows = outline("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2")
        val journal = listOf(user(""), assistant(""), user(""), assistant(""))
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal, emptyRole, "a1")
        }
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal.drop(2), emptyRole, "a1")
        }
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, journal.toMutableList().apply { this[0] = user("wrong-user") }, emptyRole, "a1")
        }
    }

    @Test
    fun oldSummaryCannotLeakTurnsPastTheCut() {
        val rows = outline("user" to "u1", "assistant" to "a1", "user" to "u2", "assistant" to "a2")
        val summary = ConversationMessage("assistant", "未来内容", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, listOf(summary, user("u2"), assistant("a2")), emptyRole, "a1")
        }
        assertThrows(ConversationBranchUnavailable::class.java) {
            ConversationBranchBoundary.resolve(rows, listOf(summary, assistant("a1")), emptyRole, "a1")
        }
    }

    @Test
    fun greetingAndRemappedBlockIdsRemainBranchable() {
        val greeting = "greeting-source"
        assertEquals(listOf(assistant(greeting)), ConversationBranchBoundary.resolve(
            outline("assistant" to greeting), listOf(assistant(greeting)), emptyRole, greeting))
        val transcriptId = ConversationBranchBoundary.remapId("child", "assistant-r-1")
        val blockId = ConversationBranchBoundary.remapId("child", "assistant-r-1-0")
        val journal = listOf(assistant(transcriptId))
        assertEquals(journal, ConversationBranchBoundary.resolve(outline("assistant" to blockId), journal, emptyRole, blockId))
        assertEquals("greeting-child-source", ConversationBranchBoundary.remapId("child", greeting))
    }

    private fun outline(vararg entries: Pair<String, String>) = entries.mapIndexed { index, (type, id) ->
        ConversationBranchMessage(id, index, type, true)
    }
    private fun user(id: String) = ConversationMessage("user", "相同问题", messageId = id)
    private fun assistant(id: String, content: String = "相同回答") = ConversationMessage("assistant", content, messageId = id)
    private fun call() = ConversationMessage("assistant", toolCallsJson = """[{"id":"tool-1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""")
    private fun result() = ConversationMessage("tool", "完成", toolCallId = "tool-1")
}
