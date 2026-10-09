package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenProgressGuardTest {
    private val still = VirtualScreenProgressGuard.Evidence("run:display:app", nodes = "same", image = 0)
    private val tap = VirtualScreenProgressGuard.Action("tap", x = 90, y = 88)

    @Test
    fun missedClickAllowsThreeAttemptsThenPausesBeforeFourth() {
        val guard = VirtualScreenProgressGuard()
        repeat(3) { attempt ->
            val decision = guard.beforeAction(tap, still)
            assertFalse(decision.paused)
            assertEquals(attempt + 1, decision.repeatedAttempts)
            assertEquals(attempt >= 1, decision.warning)
        }
        assertTrue(guard.beforeAction(tap, still).paused)
    }

    @Test
    fun smallCoordinateChangesAndReadOnlyObservationsCannotResetBudget() {
        val guard = VirtualScreenProgressGuard()
        repeat(3) { attempt ->
            assertFalse(guard.beforeAction(tap.copy(x = 90 + attempt * 5), still).paused)
            repeat(2) { assertFalse(guard.onObservation(still).paused) }
        }
        assertTrue(guard.beforeAction(tap.copy(x = 99), still).paused)
    }

    @Test
    fun differentTargetsHaveBoundedRecoveryBudget() {
        val guard = VirtualScreenProgressGuard()
        repeat(6) { attempt ->
            assertFalse(guard.beforeAction(tap.copy(x = 100 + attempt * 100), still).paused)
        }
        assertTrue(guard.beforeAction(VirtualScreenProgressGuard.Action("key", "BACK"), still).paused)
    }

    @Test
    fun changingNodeStateAllowsLongTasksWithSameTarget() {
        val guard = VirtualScreenProgressGuard()
        repeat(100) { counter ->
            val result = guard.beforeAction(tap, still.copy(nodes = "counter:$counter"))
            assertFalse(result.paused)
            assertEquals(1, result.attempts)
        }
    }

    @Test
    fun animationCannotResetBudgetWhenNodesAreStable() {
        val guard = VirtualScreenProgressGuard()
        repeat(3) { assertFalse(guard.beforeAction(tap, still.copy(image = if (it % 2 == 0) -1 else 0)).paused) }
        assertTrue(guard.beforeAction(tap, still).paused)
    }

    @Test
    fun screenshotChangeIsFallbackWhenTreeIsUnavailable() {
        val guard = VirtualScreenProgressGuard()
        val screenshotOnly = still.copy(nodes = null)
        repeat(3) { assertFalse(guard.beforeAction(tap, screenshotOnly).paused) }
        assertEquals(1, guard.beforeAction(tap, screenshotOnly.copy(image = -1)).attempts)
    }

    @Test
    fun smallImageNoiseDoesNotResetBudget() {
        val guard = VirtualScreenProgressGuard()
        val screenshotOnly = still.copy(nodes = null)
        repeat(3) { assertFalse(guard.beforeAction(tap, screenshotOnly.copy(image = it.toLong())).paused) }
        assertTrue(guard.beforeAction(tap, screenshotOnly.copy(image = 7)).paused)
    }

    @Test
    fun temporaryMissingTreeDoesNotResetBudget() {
        val guard = VirtualScreenProgressGuard()
        assertFalse(guard.beforeAction(tap, still).paused)
        assertFalse(guard.beforeAction(tap, still.copy(nodes = null)).paused)
        assertFalse(guard.beforeAction(tap, still).paused)
        assertTrue(guard.beforeAction(tap, still).paused)
    }

    @Test
    fun missingEvidenceStillBoundsAttemptsWithoutDeclaringAppFrozen() {
        val guard = VirtualScreenProgressGuard()
        val missing = still.copy(nodes = null, image = null)
        repeat(3) { assertFalse(guard.beforeAction(tap, missing).paused) }
        assertTrue(guard.beforeAction(tap, missing).paused)
    }

    @Test
    fun readOnlyScreenInspectionDoesNotConsumeActionBudget() {
        var time = 0L
        val guard = VirtualScreenProgressGuard { time }
        repeat(100) {
            time += 10_000
            assertFalse(guard.onObservation(still).paused)
        }
        assertEquals(1, guard.beforeAction(tap, still).attempts)
    }

    @Test
    fun waitingAndObservingForeverAfterAnActionEventuallyPauses() {
        var time = 0L
        val guard = VirtualScreenProgressGuard { time }
        guard.beforeAction(tap, still)
        repeat(7) { time += 5000; assertFalse(guard.onObservation(still).paused) }
        time += 5000
        assertTrue(guard.onObservation(still).paused)
    }

    @Test
    fun newRunManualInputAndWindowChangesStartNewBudgets() {
        val guard = VirtualScreenProgressGuard()
        repeat(3) { guard.beforeAction(tap, still) }
        assertEquals(1, guard.beforeAction(tap, still.copy(scope = "next-run")).attempts)
        assertEquals(1, guard.beforeAction(tap, still.copy(scope = "next-run:manual:1")).attempts)
        val firstWindow = still.copy(scope = "next-run:manual:1", window = "first")
        guard.beforeAction(tap, firstWindow)
        assertEquals(1, guard.beforeAction(tap, firstWindow.copy(window = "second")).attempts)
    }
}
