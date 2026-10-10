package io.github.mangi.eta.agent.display

import io.github.mangi.eta.data.model.VirtualScreenIdleTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenIdleTimerTest {
    @Test fun expiresOnlyAfterTheSelectedIdleDuration() {
        for (minutes in listOf(10, 20, 60)) {
            var now = 0L
            val timer = VirtualScreenIdleTimer(minutes, { now })
            now = minutes * 60_000L - 1
            assertFalse(timer.expired())
            now++
            assertTrue(timer.expired())
        }
    }

    @Test fun longRunningTasksStartTheirIdleDeadlineOnCompletion() {
        var now = 0L
        val timer = VirtualScreenIdleTimer(10, { now }, active = true)
        now = 120 * 60_000L
        assertFalse(timer.expired())
        timer.update(10, active = false)
        now += 9 * 60_000L
        assertFalse(timer.expired())
        now += 60_000L
        assertTrue(timer.expired())
    }

    @Test fun manualInputProlongsIdleAndNeverCloseSurvivesSettingsChanges() {
        var now = 0L
        val timer = VirtualScreenIdleTimer(10, { now })
        now = 9 * 60_000L
        timer.touch()
        now += 60_000L
        assertFalse(timer.expired())
        timer.update(0, false)
        now += 24 * 60 * 60_000L
        assertFalse(timer.expired())
        timer.update(20, false)
        assertTrue(timer.expired())
        assertEquals(20, VirtualScreenIdleTimeout.normalize(-1))
        assertEquals(listOf(10, 20, 60, 0), VirtualScreenIdleTimeout.options)
    }
}
