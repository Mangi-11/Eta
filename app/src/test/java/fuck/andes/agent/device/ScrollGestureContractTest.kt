package fuck.andes.agent.device

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScrollGestureContractTest {
    private val bounds = Rect(100, 200, 500, 1_000)

    @Test
    fun downShowsLowerContentWithUpwardFingerGesture() {
        val gesture = requireNotNull(ScrollDirection.DOWN.gestureWithin(bounds))

        assertEquals(ScrollAxis.VERTICAL, ScrollDirection.DOWN.axis)
        assertEquals(1, ScrollDirection.DOWN.scrollDeltaSign)
        assertEquals(gesture.start.x, gesture.end.x)
        assertTrue(gesture.start.y > gesture.end.y)
        assertNearFractions(gesture.start.y, gesture.end.y, bounds.top, bounds.bottom)
        assertInside(gesture, bounds)
    }

    @Test
    fun upShowsUpperContentWithDownwardFingerGesture() {
        val gesture = requireNotNull(ScrollDirection.UP.gestureWithin(bounds))

        assertEquals(ScrollAxis.VERTICAL, ScrollDirection.UP.axis)
        assertEquals(-1, ScrollDirection.UP.scrollDeltaSign)
        assertEquals(gesture.start.x, gesture.end.x)
        assertTrue(gesture.start.y < gesture.end.y)
        assertNearFractions(gesture.end.y, gesture.start.y, bounds.top, bounds.bottom)
        assertInside(gesture, bounds)
    }

    @Test
    fun rightShowsRightContentWithLeftwardFingerGesture() {
        val gesture = requireNotNull(ScrollDirection.RIGHT.gestureWithin(bounds))

        assertEquals(ScrollAxis.HORIZONTAL, ScrollDirection.RIGHT.axis)
        assertEquals(1, ScrollDirection.RIGHT.scrollDeltaSign)
        assertEquals(gesture.start.y, gesture.end.y)
        assertTrue(gesture.start.x > gesture.end.x)
        assertNearFractions(gesture.start.x, gesture.end.x, bounds.left, bounds.right)
        assertInside(gesture, bounds)
    }

    @Test
    fun leftShowsLeftContentWithRightwardFingerGesture() {
        val gesture = requireNotNull(ScrollDirection.LEFT.gestureWithin(bounds))

        assertEquals(ScrollAxis.HORIZONTAL, ScrollDirection.LEFT.axis)
        assertEquals(-1, ScrollDirection.LEFT.scrollDeltaSign)
        assertEquals(gesture.start.y, gesture.end.y)
        assertTrue(gesture.start.x < gesture.end.x)
        assertNearFractions(gesture.end.x, gesture.start.x, bounds.left, bounds.right)
        assertInside(gesture, bounds)
    }

    @Test
    fun twoPixelBoundsKeepEveryDirectionInsideAndMoving() {
        val tinyBounds = Rect(10, 20, 12, 22)

        ScrollDirection.entries.forEach { direction ->
            val gesture = direction.gestureWithin(tinyBounds)
            assertNotNull(direction.name, gesture)
            requireNotNull(gesture)
            assertTrue(direction.name, gesture.start != gesture.end)
            assertInside(gesture, tinyBounds)
        }
    }

    @Test
    fun onePixelPerpendicularAxisStillSupportsGesture() {
        val narrowVertical = Rect(10, 20, 11, 24)
        val shortHorizontal = Rect(10, 20, 14, 21)

        val vertical = requireNotNull(ScrollDirection.DOWN.gestureWithin(narrowVertical))
        val horizontal = requireNotNull(ScrollDirection.RIGHT.gestureWithin(shortHorizontal))

        assertInside(vertical, narrowVertical)
        assertInside(horizontal, shortHorizontal)
    }

    @Test
    fun boundsWithoutEnoughAxisTravelReturnNull() {
        val onePixel = Rect(10, 20, 11, 21)

        ScrollDirection.entries.forEach { direction ->
            assertNull(direction.name, direction.gestureWithin(onePixel))
        }
        assertNull(ScrollDirection.DOWN.gestureWithin(Rect(0, 0, 0, 10)))
    }

    @Test
    fun parseUsesTheUnifiedPhysicalDirectionsOnly() {
        assertEquals(ScrollDirection.DOWN, ScrollDirection.parse(" Down "))
        assertEquals(ScrollDirection.LEFT, ScrollDirection.parse("LEFT"))
        assertNull(ScrollDirection.parse("forward"))
    }

    private fun assertNearFractions(
        far: Int,
        near: Int,
        start: Int,
        endExclusive: Int,
    ) {
        val span = endExclusive - start - 1
        assertTrue(far in (start + span * 0.7f).toInt()..(start + span * 0.9f).toInt())
        assertTrue(near in (start + span * 0.1f).toInt()..(start + span * 0.3f).toInt())
    }

    private fun assertInside(gesture: ScrollGesture, bounds: Rect) {
        assertTrue(bounds.contains(gesture.start.x, gesture.start.y))
        assertTrue(bounds.contains(gesture.end.x, gesture.end.y))
    }
}
