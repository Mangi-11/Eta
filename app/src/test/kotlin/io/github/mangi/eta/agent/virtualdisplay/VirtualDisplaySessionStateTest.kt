package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplaySessionStateTest {
    @Test
    fun releaseDuringCreationRejectsLateReady() {
        val state = VirtualDisplaySessionState()
        val creating = state.begin()

        assertTrue(state.invalidate())
        assertFalse(state.activate(creating, 8))
        assertNull(state.active)
    }

    @Test
    fun oldBridgeExitCannotInvalidateReplacementWithSameDisplayId() {
        val state = VirtualDisplaySessionState()
        val old = state.begin()
        assertTrue(state.activate(old, 8))
        assertTrue(state.invalidate(old))
        val replacement = state.begin()
        assertTrue(state.activate(replacement, 8))

        assertFalse(state.invalidate(old))
        assertFalse(state.activate(old, 9))
        assertEquals(VirtualDisplaySessionState.Active(replacement, 8), state.active)
    }

    @Test
    fun displayRemovalInvalidatesReadySessionOnce() {
        val state = VirtualDisplaySessionState()
        val token = state.begin()
        assertTrue(state.activate(token, 8))

        assertTrue(state.invalidate(token))
        assertFalse(state.invalidate(token))
        assertFalse(state.isCurrent(token))
        assertNull(state.active)
    }

    @Test
    fun oldCreationFailureDoesNotCancelNewPendingCreation() {
        val state = VirtualDisplaySessionState()
        val old = state.begin()
        state.invalidate(old)
        val replacement = state.begin()

        assertFalse(state.invalidate(old))
        assertTrue(state.activate(replacement, 9))
        assertEquals(9, state.active?.displayId)
    }
}
