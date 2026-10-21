package io.github.mangi.eta.hook.xiaoai

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoAiVoiceConversationTest {
    private fun message(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content)

    @Test
    fun successfulVoiceTurnsShareSessionAndCarryRuntimeTranscript() {
        var counter = 0
        val conversation = XiaoAiVoiceConversation(newSessionId = { "voice-${++counter}" })
        val first = conversation.snapshot()
        assertEquals("voice-1", first.sessionId)
        assertTrue(first.history.isEmpty())

        val toolResponse = listOf(
            message("assistant", "让我查一下"),
            AgentModelClient.ConversationMessage(role = "tool", content = "25 °C", toolCallId = "tool-1"),
            message("assistant", "今天 25 度"),
        )
        assertTrue(conversation.recordSuccess(
            snapshot = first,
            prompt = "今天天气怎样",
            reply = "今天 25 度",
            transcript = toolResponse,
        ))
        val second = conversation.snapshot()
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(1, second.priorTurns)
        assertEquals(
            listOf(message("user", "今天天气怎样")) + toolResponse,
            second.history,
        )
    }

    @Test
    fun compactedContextOverridesUncompactedHistory() {
        val conversation = XiaoAiVoiceConversation(newSessionId = { "voice-a" })
        val first = conversation.snapshot()
        val summary = listOf(
            AgentModelClient.ConversationMessage(
                role = "system",
                content = "第一轮的压缩上下文",
                contextSummary = true,
            ),
            message("assistant", "收到"),
        )
        assertTrue(conversation.recordSuccess(
            snapshot = first,
            prompt = "请记录",
            reply = "收到",
            transcript = listOf(message("assistant", "收到")),
            contextMessages = summary,
        ))
        assertEquals(summary, conversation.snapshot().history)
    }

    @Test
    fun interruptedAndOutOfOrderRunsDoNotOverwriteLatestHistory() {
        val conversation = XiaoAiVoiceConversation(newSessionId = { "voice-a" })
        val first = conversation.snapshot()
        val second = conversation.snapshot()
        assertFalse(conversation.recordSuccess(
            snapshot = first,
            prompt = "旧问题",
            reply = "旧回答",
            transcript = emptyList(),
        ))
        assertTrue(conversation.recordSuccess(
            snapshot = second,
            prompt = "新问题",
            reply = "新回答",
            transcript = emptyList(),
        ))
        assertEquals(
            listOf(message("user", "新问题"), message("assistant", "新回答")),
            conversation.snapshot().history,
        )
    }

    @Test
    fun abortedRunDoesNotPolluteNextConversationTurn() {
        val conversation = XiaoAiVoiceConversation(newSessionId = { "voice-a" })
        val failed = conversation.snapshot()
        conversation.abandon(failed)
        assertFalse(conversation.recordSuccess(
            snapshot = failed,
            prompt = "失败问题",
            reply = "不应保存",
            transcript = emptyList(),
        ))
        val next = conversation.snapshot()
        assertEquals(failed.sessionId, next.sessionId)
        assertTrue(next.history.isEmpty())
    }

    @Test
    fun idleExpiryAndTurnLimitResetTheSession() {
        var now = 1000L
        var sequence = 0
        val conversation = XiaoAiVoiceConversation(
            idleTimeoutMillis = 100L,
            maxTurns = 2,
            clock = { now },
            newSessionId = { "voice-${++sequence}" },
        )
        val first = conversation.snapshot()
        assertTrue(conversation.recordSuccess(first, "一", "回答一", emptyList()))
        val second = conversation.snapshot()
        assertEquals(first.sessionId, second.sessionId)
        assertTrue(conversation.recordSuccess(second, "二", "回答二", emptyList()))
        assertEquals("voice-2", conversation.snapshot().sessionId)

        now += 101L
        assertEquals("voice-3", conversation.snapshot().sessionId)
    }

    @Test
    fun excessiveHistoryResetsInsteadOfTruncatingToolPairs() {
        var sequence = 0
        val conversation = XiaoAiVoiceConversation(
            maxHistoryChars = 10,
            newSessionId = { "voice-${++sequence}" },
        )
        val first = conversation.snapshot()
        assertFalse(conversation.recordSuccess(
            snapshot = first,
            prompt = "1234567",
            reply = "1234567",
            transcript = emptyList(),
        ))
        val next = conversation.snapshot()
        assertEquals("voice-2", next.sessionId)
        assertTrue(next.history.isEmpty())
    }
}
