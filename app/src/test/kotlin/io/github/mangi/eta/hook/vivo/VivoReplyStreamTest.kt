package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class VivoReplyStreamTest {
    private fun delta(text: String, round: Int = 1, kind: AgentEvent.AssistantBlockKind = AgentEvent.AssistantBlockKind.TEXT) =
        AgentEvent.AssistantBlockDelta(round, kind, 0, text.length, text)

    @Test
    fun intermediatePacketsAppendAndFinalFlushIncludesTheTailWithoutDuplication() {
        val stream = VivoReplyStream()
        stream.accept(AgentEvent.RoundStarted(1, 0))
        stream.accept(delta("你好"))
        assertEquals("你好", stream.flush()!!.text)
        stream.accept(delta("，世界"))
        val final = stream.complete("你好，世界")
        assertEquals(listOf("，世界", ""), final.map { it.text })
        assertEquals(listOf(2, 3), final.map { it.index })
        assertFalse(final.first().isLast)
        assertTrue(final.last().isLast)
        assertEquals("你好，世界", final.last().fullText)
        assertNull(stream.flush())
        assertFalse(stream.accept(delta("late")))
        assertTrue(stream.complete("late").isEmpty())
    }

    @Test
    fun retriesAndAuthoritativeReplacementsReplacePreviousSpeculativeAnswer() {
        val stream = VivoReplyStream()
        stream.accept(AgentEvent.RoundStarted(1, 0))
        stream.accept(delta("失败的尝试"))
        stream.flush()
        stream.accept(AgentEvent.RoundStarted(2, 0))
        stream.accept(delta("新回答", round = 2))
        assertEquals("新回答", stream.flush()!!.fullText)
        stream.accept(AgentEvent.AssistantBlockEnd(2, AgentEvent.AssistantBlockKind.TEXT, 0, contentChars = 4, replacementContent = "修订回答"))
        assertEquals("修订回答", stream.flush()!!.fullText)
        assertEquals("已停止", stream.complete("已停止").last().fullText)
    }

    @Test
    fun reasoningToolArgumentsAndOldRoundEventsNeverReachTheNativeAnswer() {
        val stream = VivoReplyStream()
        stream.accept(AgentEvent.RoundStarted(2, 0))
        assertFalse(stream.accept(delta("private thought", 2, AgentEvent.AssistantBlockKind.THINKING)))
        assertFalse(stream.accept(delta("private tool args", 2, AgentEvent.AssistantBlockKind.TOOL_CALL)))
        assertFalse(stream.accept(delta("old", 1)))
        assertNull(stream.flush())
        assertEquals("完整回答", stream.complete("完整回答").first().text)
    }
}
