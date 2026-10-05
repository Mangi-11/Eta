package io.github.mangi.eta.hook.vivo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VivoConversationHistoryTest {
    @Test
    fun followUpOnlySeesCompletedEtaTurnsInItsOwnSession() {
        val history = VivoConversationHistory()
        history.remember("one", "first question", "first answer")
        history.remember("two", "unrelated", "answer")
        val snapshot = history.snapshot("one")
        history.remember("one", "follow-up", "second answer")

        assertEquals(listOf("first question", "first answer"), snapshot.map { it.content })
        assertEquals(listOf("user", "assistant"), snapshot.map { it.role })
        assertTrue(history.snapshot("unknown").isEmpty())
        assertEquals(4, history.snapshot("one").size)
    }

    @Test
    fun boundedHistoryEvictsWholeTurnsAndRejectsOversizedOrUnidentifiedSessions() {
        val history = VivoConversationHistory()
        repeat(8) { history.remember("one", "question-$it", "answer-$it") }
        history.remember("", "unidentified", "answer")
        history.remember("large", "x".repeat(48_001), "answer")

        assertEquals("question-2", history.snapshot("one").first().content)
        assertEquals(12, history.snapshot("one").size)
        assertTrue(history.snapshot("").isEmpty())
        assertTrue(history.snapshot("large").isEmpty())
    }
}
