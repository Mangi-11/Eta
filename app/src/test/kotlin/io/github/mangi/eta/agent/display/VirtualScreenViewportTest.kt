package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VirtualScreenViewportTest {
    @Test
    fun portraitFrameMapsTouchesAfterVerticalLetterboxing() {
        val viewport = VirtualScreenViewport.fit(1000, 2000, 720, 1280)!!
        assertEquals(VirtualScreenViewport.Point(360, 640), viewport.toDisplay(500f, 1000f))
        assertNull(viewport.toDisplay(500f, 40f))
        assertNull(viewport.toDisplay(500f, 1960f))
    }

    @Test
    fun landscapeViewportIgnoresSideBarsAndClampsDraggingAtEdges() {
        val viewport = VirtualScreenViewport.fit(1200, 600, 720, 1280)!!
        assertNull(viewport.toDisplay(100f, 300f))
        assertEquals(VirtualScreenViewport.Point(360, 640), viewport.toDisplay(600f, 300f))
        assertEquals(
            VirtualScreenViewport.Point(0, 0),
            viewport.toDisplay(-20f, -20f, clamp = true)
        )
        assertEquals(
            VirtualScreenViewport.Point(719, 1279),
            viewport.toDisplay(1500f, 900f, clamp = true)
        )
    }

    @Test
    fun zeroDimensionsAndInvalidCoordinatesAreNeverDispatched() {
        assertNull(VirtualScreenViewport.fit(0, 600, 720, 1280))
        assertNull(VirtualScreenViewport.fit(1000, 600, 0, 1280))
        val viewport = VirtualScreenViewport.fit(720, 1280, 720, 1280)!!
        assertNull(viewport.toDisplay(Float.NaN, 50f))
        assertNull(viewport.toDisplay(Float.POSITIVE_INFINITY, 50f, clamp = true))
        assertNull(viewport.toDisplay(720f, 0f))
    }
}
