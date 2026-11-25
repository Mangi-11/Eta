package io.github.mangi.eta.ui.components.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPaneMotionTest {
    @Test
    fun closedAndOpenPositionsMatchTheViewportEdges() {
        val closed = conversationPaneMotion(0f, 320f)
        assertEquals(0f, closed.foregroundOffset, 0f)
        assertEquals(-320f, closed.panelOffset, 0f)
        assertEquals(0f, closed.progress, 0f)

        val open = conversationPaneMotion(320f, 320f)
        assertEquals(320f, open.foregroundOffset, 0f)
        assertEquals(0f, open.panelOffset, 0f)
        assertEquals(1f, open.progress, 0f)
    }

    @Test
    fun panesStayAdjacentWithoutOverlapOrGapsThroughoutEitherDirection() {
        for (width in listOf(280f, 320f, 340f)) {
            val positions = listOf(0f, 0.1f, 0.5f, 0.6f, 40f, width / 2f, width)
            val opening = positions.map { conversationPaneMotion(it, width) }
            opening.forEach { motion ->
                assertEquals(motion.foregroundOffset, motion.panelOffset + width, 0.0001f)
            }
            assertTrue(opening[3].progress < 0.01f)
            opening.zipWithNext().forEach { (before, after) ->
                assertTrue(after.progress >= before.progress)
                assertEquals(
                    after.foregroundOffset - before.foregroundOffset,
                    after.panelOffset - before.panelOffset,
                    0.0001f,
                )
            }
            val closing = positions.reversed().map { conversationPaneMotion(it, width) }
            assertEquals(opening, closing.reversed())
        }
    }

    @Test
    fun overshootAndUninitializedOffsetsCannotProduceInvalidLayers() {
        assertEquals(conversationPaneMotion(0f, 320f), conversationPaneMotion(-20f, 320f))
        assertEquals(conversationPaneMotion(320f, 320f), conversationPaneMotion(400f, 320f))
        assertEquals(conversationPaneMotion(0f, 320f), conversationPaneMotion(Float.NaN, 320f))
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val motion = conversationPaneMotion(40f, width)
            assertEquals(0f, motion.progress, 0f)
            assertEquals(0f, motion.foregroundOffset, 0f)
            assertTrue(motion.panelOffset.isFinite())
        }
    }

    @Test
    fun resizedWindowsKeepTheSameProgressAtEquivalentDragFractions() {
        val compact = conversationPaneMotion(140f, 280f)
        val wide = conversationPaneMotion(170f, 340f)
        assertEquals(compact.progress, wide.progress, 0f)
        assertEquals(compact.panelOffset / 280f, wide.panelOffset / 340f, 0.00001f)
    }
}
