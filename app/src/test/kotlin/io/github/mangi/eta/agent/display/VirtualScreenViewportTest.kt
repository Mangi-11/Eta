package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun landscapeFrameRotatesClockwiseAndFillsPortraitViewer() {
        val viewport = VirtualScreenViewport.fit(720, 1600, 1600, 720)!!
        assertTrue(viewport.rotatesClockwise)
        assertEquals(1f, viewport.scale, 0f)
        assertEquals(0f, viewport.left, 0f)
        assertEquals(0f, viewport.top, 0f)
        assertEquals(720f / 1600, VirtualScreenViewport.previewAspectRatio(720f, 1600f, 1600, 720), 0f)
        assertEquals(VirtualScreenViewport.ViewPoint(719.5f, 0.5f), viewport.toView(0, 0))
        assertEquals(VirtualScreenViewport.ViewPoint(0.5f, 0.5f), viewport.toView(0, 719))
        assertEquals(VirtualScreenViewport.ViewPoint(719.5f, 1599.5f), viewport.toView(1599, 0))
        assertEquals(VirtualScreenViewport.ViewPoint(0.5f, 1599.5f), viewport.toView(1599, 719))
        assertEquals(VirtualScreenViewport.Point(0, 719), viewport.toDisplay(0.5f, 0.5f))
        assertEquals(VirtualScreenViewport.Point(1599, 0), viewport.toDisplay(719.5f, 1599.5f))
        for (point in listOf(VirtualScreenViewport.Point(0, 0), VirtualScreenViewport.Point(700, 300),
            VirtualScreenViewport.Point(1599, 719))) {
            val mapped = viewport.toView(point.x, point.y)
            assertEquals(point, viewport.toDisplay(mapped.x, mapped.y))
        }
    }

    @Test
    fun rotatedPreviewIgnoresBarsAndClampsDraggingInTheRotatedDirection() {
        val viewport = VirtualScreenViewport.fit(1000, 2000, 1280, 720)!!
        assertNull(viewport.toDisplay(500f, 100f))
        assertNull(viewport.toDisplay(500f, 1900f))
        assertEquals(VirtualScreenViewport.Point(0, 719), viewport.toDisplay(-20f, -20f, clamp = true))
        assertEquals(VirtualScreenViewport.Point(1279, 0), viewport.toDisplay(1100f, 2200f, clamp = true))
        assertNull(viewport.toDisplay(Float.NaN, 50f))
        assertNull(viewport.toDisplay(50f, Float.POSITIVE_INFINITY, clamp = true))
    }

    @Test
    fun landscapeFrameStaysUprightWhenViewerIsLandscape() {
        val viewport = VirtualScreenViewport.fit(1600, 720, 1600, 720)!!
        assertFalse(viewport.rotatesClockwise)
        assertEquals(1600f / 720, VirtualScreenViewport.previewAspectRatio(1600f, 720f, 1600, 720), 0f)
        assertEquals(VirtualScreenViewport.Point(200, 400), viewport.toDisplay(200.5f, 400.5f))
        assertEquals(VirtualScreenViewport.ViewPoint(200.5f, 400.5f), viewport.toView(200, 400))
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
