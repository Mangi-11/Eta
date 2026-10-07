package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualScreenCoordinatesTest {
    @Test
    fun normalizedCoordinatesUseVirtualDisplayDimensionsAndInclusiveEdges() {
        assertEquals(0 to 0, VirtualScreenUiTools.coordinatePoint(0, 0, "normalized", 800, 1200))
        assertEquals(799 to 1199, VirtualScreenUiTools.coordinatePoint(999, 999, "normalized", 800, 1200))
        assertEquals(399 to 600, VirtualScreenUiTools.coordinatePoint(500, 500, "normalized", 800, 1200))
    }

    @Test
    fun screenAndNativeScreenshotCoordinatesKeepVirtualPixels() {
        assertEquals(750 to 1100, VirtualScreenUiTools.coordinatePoint(750, 1100, "screen", 800, 1200))
        assertEquals(750 to 1100, VirtualScreenUiTools.coordinatePoint(750, 1100, "screenshot", 800, 1200))
    }

    @Test
    fun missingCoordinateSpaceAndOutOfRangePointsAreRejected() {
        assertThrows(IllegalStateException::class.java) { VirtualScreenUiTools.coordinatePoint(1, 1, "", 800, 1200) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenUiTools.coordinatePoint(1000, 1, "normalized", 800, 1200) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenUiTools.coordinatePoint(-1, 1, "normalized", 800, 1200) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenUiTools.coordinatePoint(800, 1, "screen", 800, 1200) }
    }
}
