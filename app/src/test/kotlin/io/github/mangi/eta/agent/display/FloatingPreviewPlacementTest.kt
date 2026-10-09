package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingPreviewPlacementTest {
    @Test
    fun draggingStaysWithinSystemBarsAndFoldsOnlyAtAnEdge() {
        val left = FloatingPreviewPlacement.constrain(-50, -50, 124, 278, 1080, 2400, 80, 100, 24)
        assertEquals(FloatingPreviewPlacement.Position(0, 80, FloatingPreviewPlacement.Edge.LEFT), left)
        val right = FloatingPreviewPlacement.constrain(1100, 2400, 124, 278, 1080, 2400, 80, 100, 24)
        assertEquals(FloatingPreviewPlacement.Position(956, 2022, FloatingPreviewPlacement.Edge.RIGHT), right)
        assertNull(FloatingPreviewPlacement.constrain(200, 200, 124, 278, 1080, 2400, 80, 100, 24).edge)
    }

    @Test
    fun rotationToSmallerWindowDoesNotProduceInvalidClampBounds() {
        val position = FloatingPreviewPlacement.constrain(1000, 2000, 124, 278, 100, 200, 80, 100, 24)
        assertEquals(0, position.x)
        assertEquals(0, position.y)
    }
}
