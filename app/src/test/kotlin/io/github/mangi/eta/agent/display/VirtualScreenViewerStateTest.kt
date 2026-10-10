package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class VirtualScreenViewerStateTest {
    private val display = VirtualDisplayInfo("screen", "conversation", 4, 1216, 2640)

    @Test
    fun geometryChangesInvalidateObservationsWhileKeepingDisplayOwnership() {
        val landscape = display.withGeometry(2640, 1216, 1)
        assertEquals(display.sessionId, landscape.sessionId)
        assertEquals(display.displayId, landscape.displayId)
        assertEquals(display.owner, landscape.owner)
        assertEquals(1L, landscape.manualInputGeneration)
        assertFalse(display.hasSameGeometry(landscape))
        assertSame(landscape, landscape.withGeometry(2640, 1216, 1))
        assertEquals(2L, landscape.withGeometry(2640, 1216, 3).manualInputGeneration)
    }

    @Test
    fun pauseRetainsDisplayAndStopsControlWithoutClaimingSuccess() {
        val state = VirtualScreenViewerState(display = display).beginRun("run")
            .finishRun("run", success = true, cancelled = false, paused = true)
        assertEquals(VirtualScreenTaskPhase.PAUSED, state.taskPhase)
        assertEquals(display, state.display)
        assertNull(state.activeRunId)
        assertFalse(state.isAgentControlling)
        assertEquals(VirtualScreenTaskPhase.RUNNING, state.beginRun("resume").taskPhase)
    }

    @Test
    fun completedTaskStopsControlBorderWhileDisplayIsRetained() {
        val running = VirtualScreenViewerState(display = display).beginRun("first")
        assertTrue(running.isAgentControlling)
        val completed = running.finishRun("first", success = true, cancelled = false)
        assertEquals(display, completed.display)
        assertEquals(VirtualScreenTaskPhase.COMPLETED, completed.taskPhase)
        assertFalse(completed.isAgentControlling)
    }

    @Test
    fun delayedCompletionCannotChangeNextRunStatus() {
        val next = VirtualScreenViewerState(display = display).beginRun("next")
        assertSame(next, next.finishRun("previous", success = true, cancelled = false))
        assertTrue(next.isAgentControlling)
    }

    @Test
    fun failuresAndCancellationHaveDistinctTerminalStates() {
        val state = VirtualScreenViewerState(display = display).beginRun("run")
        assertEquals(VirtualScreenTaskPhase.FAILED, state.finishRun("run", success = false, cancelled = false).taskPhase)
        assertEquals(VirtualScreenTaskPhase.STOPPED, state.finishRun("run", success = false, cancelled = true).taskPhase)
    }

    @Test
    fun operationHistoryKeepsLatestHundredAcrossTurns() {
        var state = VirtualScreenViewerState(display = display).beginRun("first")
        repeat(120) { index -> state = state.record(VirtualScreenOperation(index.toLong(), "tap", index.toLong(), true, false)) }
        state = state.finishRun("first", success = true, cancelled = false).beginRun("second")
        assertEquals(100, state.operations.size)
        assertEquals(20L, state.operations.first().id)
        assertEquals(119L, state.operations.last().id)
    }
}
