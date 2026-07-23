package fuck.andes.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootScrollMotionContractTest {
    @Test
    fun `content moving upward proves scroll position moved down`() {
        assertEquals(120, RootScrollMotionContract.inferScrollDelta(listOf(-119, -120, -121)))
    }

    @Test
    fun `uses median and ignores subpixel noise`() {
        assertEquals(-80, RootScrollMotionContract.inferScrollDelta(listOf(1, 79, 80, 500)))
        assertNull(RootScrollMotionContract.inferScrollDelta(listOf(-1, 0, 1)))
    }

    @Test
    fun `single animation or opposite anchors cannot prove scrolling`() {
        assertNull(RootScrollMotionContract.inferScrollDelta(listOf(-120)))
        assertNull(RootScrollMotionContract.inferScrollDelta(listOf(-100, 100)))
        assertNull(RootScrollMotionContract.inferScrollDelta(listOf(-100, -98, 100, 102)))
    }
}
