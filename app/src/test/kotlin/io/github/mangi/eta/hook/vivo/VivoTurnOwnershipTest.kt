package io.github.mangi.eta.hook.vivo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VivoTurnOwnershipTest {
    private fun turn(trace: String = "trace", request: String = "request") =
        VivoReplyProtocol.Turn(trace, request, "session", "product")

    @Test
    fun repeatedDispatchDoesNotStartAnotherRunAndLateRepliesStayOwned() {
        val ownership = VivoTurnOwnership()
        assertTrue(ownership.claim(turn()))
        assertFalse(ownership.claim(turn()))
        assertTrue(ownership.owns("trace", "request"))
        assertTrue(ownership.owns("trace")) // Native end events sometimes only include a trace ID.
        assertFalse(ownership.owns("trace", "another-request"))
        assertFalse(ownership.owns("another-trace", "request"))
        assertFalse(ownership.owns(null))
        assertFalse(ownership.owns(""))
    }

    @Test
    fun rejectedQueueReservationCanReturnToNativeDispatch() {
        val ownership = VivoTurnOwnership()
        assertTrue(ownership.claim(turn()))
        ownership.release(turn())
        assertFalse(ownership.owns("trace", "request"))
        assertTrue(ownership.claim(turn()))
    }

    @Test
    fun incompleteIdsCannotTakeOwnership() {
        val ownership = VivoTurnOwnership()
        assertFalse(ownership.claim(turn(trace = "")))
        assertFalse(ownership.claim(turn(request = "")))
    }

    @Test
    fun retentionIsBoundedWithoutEvictingRecentTurns() {
        val ownership = VivoTurnOwnership(capacity = 2)
        assertTrue(ownership.claim(turn("old", "1")))
        assertTrue(ownership.claim(turn("recent", "2")))
        assertTrue(ownership.claim(turn("new", "3")))
        assertFalse(ownership.owns("old"))
        assertTrue(ownership.owns("recent", "2"))
        assertTrue(ownership.owns("new", "3"))
    }
}
