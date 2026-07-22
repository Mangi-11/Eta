package fuck.andes.ui.app

import fuck.andes.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunEventCoalescerTest {
    @Test
    fun coalescesAdjacentDeltasFromTheSameBlock() {
        val coalescer = AgentRunEventCoalescer()

        assertNull(coalescer.append("run-1", delta(index = 0, text = "你")))
        assertNull(coalescer.append("run-1", delta(index = 0, text = "好")))

        assertEquals(
            delta(index = 0, text = "你好", chars = 2),
            coalescer.flush("run-1"),
        )
    }

    @Test
    fun flushesThePreviousBlockBeforeBufferingANewOne() {
        val coalescer = AgentRunEventCoalescer()
        val text = delta(index = 0, text = "回答")
        val thinking = delta(
            index = 1,
            text = "分析",
            kind = AgentEvent.AssistantBlockKind.THINKING,
        )

        assertNull(coalescer.append("run-1", text))
        assertEquals(text, coalescer.append("run-1", thinking))
        assertEquals(thinking, coalescer.flush("run-1"))
    }

    @Test
    fun keepsRunsIndependent() {
        val coalescer = AgentRunEventCoalescer()
        val first = delta(index = 0, text = "A")
        val second = delta(index = 0, text = "B")

        coalescer.append("run-1", first)
        coalescer.append("run-2", second)

        assertEquals(first, coalescer.flush("run-1"))
        assertEquals(second, coalescer.flush("run-2"))
    }

    private fun delta(
        index: Int,
        text: String,
        chars: Int = text.length,
        kind: AgentEvent.AssistantBlockKind = AgentEvent.AssistantBlockKind.TEXT,
    ) = AgentEvent.AssistantBlockDelta(
        round = 1,
        kind = kind,
        index = index,
        deltaChars = chars,
        delta = text,
    )
}
