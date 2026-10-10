package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenProfileTest {
    @Test
    fun vivoPhoneUsesNativeResolutionAspectRatioAndDensity() {
        assertEquals(VirtualScreenProfile(1216, 2640, 560), VirtualScreenProfile.fit(1216, 2640, 560))
    }

    @Test
    fun largerDisplaysKeepTheirLogicalSizeWhenScaledToCaptureLimits() {
        val fitted = VirtualScreenProfile.fit(2160, 4800, 960)
        assertEquals(VirtualScreenProfile(1440, 3200, 640), fitted)
        assertEquals(2160.0 / 960, fitted.width.toDouble() / fitted.density, 0.01)
    }

    @Test
    fun retainedDisplayAcceptsNextTurnAndRejectsOldCancellation() {
        val lease = VirtualScreenRunLease("first")
        assertFalse(lease.acquire("second"))
        assertFalse(lease.retireIdle())
        assertTrue(lease.release("first", retain = true))
        assertTrue(lease.acquire("second"))
        assertFalse(lease.release("first", retain = false))
        assertTrue(lease.release("second", retain = false))
        assertFalse(lease.acquire("third"))
    }

    @Test
    fun anotherConversationCanReplaceOnlyAnIdleDisplay() {
        val lease = VirtualScreenRunLease()
        assertTrue(lease.retireIdle())
        assertFalse(lease.acquire("next"))
    }
}
