package io.github.mangi.eta.hook.xiaoai

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.XiaoAiContinuityMode
import io.github.mangi.eta.config.XiaoAiContinuitySettings
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
        // A reset must not immediately expire again on the next snapshot.
        val again = conversation.snapshot()
        assertEquals(next.sessionId, again.sessionId)
        assertTrue(again.history.isEmpty())
    }
    @Test
    fun independentModeNeverReusesPreviousVoiceHistory() {
        var sequence = 0
        val conversation = XiaoAiVoiceConversation(
            newSessionId = { "voice-${++sequence}" },
        )
        val first = conversation.snapshot(XiaoAiContinuityMode.INDEPENDENT)
        assertTrue(conversation.recordSuccess(first, "问题一", "回答一", emptyList()))
        val next = conversation.snapshot(XiaoAiContinuityMode.INDEPENDENT)
        assertEquals("voice-2", next.sessionId)
        assertTrue(next.history.isEmpty())
    }

    @Test
    fun manualModeSurvivesIdleTimeoutUntilExplicitClear() {
        var now = 1_000L
        var sequence = 0
        val conversation = XiaoAiVoiceConversation(
            clock = { now },
            newSessionId = { "voice-${++sequence}" },
        )
        val first = conversation.snapshot(XiaoAiContinuityMode.MANUAL)
        assertTrue(conversation.recordSuccess(first, "暗号", "蓝色企鹅", emptyList()))
        now += 86_400_000L
        val second = conversation.snapshot(XiaoAiContinuityMode.MANUAL)
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(2, second.history.size)
        conversation.clear()
        val third = conversation.snapshot(XiaoAiContinuityMode.MANUAL)
        assertEquals("voice-2", third.sessionId)
        assertTrue(third.history.isEmpty())
    }

    @Test
    fun changingPolicyOrTimedTimeoutStartsFreshSession() {
        var sequence = 0
        val conversation = XiaoAiVoiceConversation(newSessionId = { "voice-${++sequence}" })
        val first = conversation.snapshot(XiaoAiContinuityMode.TIMED, timeoutMillis = 180_000L)
        val second = conversation.snapshot(XiaoAiContinuityMode.TIMED, timeoutMillis = 60_000L)
        assertEquals("voice-2", second.sessionId)
        val manual = conversation.snapshot(XiaoAiContinuityMode.MANUAL)
        assertEquals("voice-3", manual.sessionId)
        assertFalse(conversation.recordSuccess(first, "旧问题", "旧回答", emptyList()))
    }

    @Test
    fun configurableTimeoutExpiresOnlyTimedMode() {
        var now = 1_000L
        var sequence = 0
        val conversation = XiaoAiVoiceConversation(
            clock = { now },
            newSessionId = { "voice-${++sequence}" },
        )
        val first = conversation.snapshot(XiaoAiContinuityMode.TIMED, timeoutMillis = 60_000L)
        now += 59_000L
        val second = conversation.snapshot(XiaoAiContinuityMode.TIMED, timeoutMillis = 60_000L)
        assertEquals(first.sessionId, second.sessionId)
        now += 61_000L
        assertEquals("voice-2", conversation.snapshot(
            XiaoAiContinuityMode.TIMED, timeoutMillis = 60_000L
        ).sessionId)
    }

    @Test
    fun onlyExplicitConversationResetPhrasesAreInterpretedAsCommands() {
        assertTrue(XiaoAiContinuitySettings.isNewConversationCommand("小爱同学，开始一个新对话。"))
        assertTrue(XiaoAiContinuitySettings.isNewConversationCommand("开始新对话"))
        assertFalse(XiaoAiContinuitySettings.isNewConversationCommand("如何开始新对话？"))
        assertFalse(XiaoAiContinuitySettings.isNewConversationCommand("帮我写一个开始新对话的程序"))
        assertEquals(3, XiaoAiContinuitySettings.parseMinutes("invalid"))
        assertEquals(3, XiaoAiContinuitySettings.parseMinutes("30"))
        assertEquals(10, XiaoAiContinuitySettings.parseMinutes("10"))
    }

}
