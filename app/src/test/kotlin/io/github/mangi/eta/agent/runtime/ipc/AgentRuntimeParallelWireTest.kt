package io.github.mangi.eta.agent.runtime.ipc

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.runtime.GuiControlStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeParallelWireTest {
    @Test
    fun activeRunSnapshotsKeepIndependentIdentityAndPhase() {
        val snapshots = AgentRuntimeWire.RunPhase.entries.mapIndexed { index, phase ->
            AgentRuntimeWire.ActiveRunSnapshot("run-$index", "conversation-$index", phase,
                AgentRuntimeWire.OP_COMPACT, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)
        }
        val restored = AgentRuntimeWire.activeRunsFromBundle(AgentRuntimeWire.activeRunsToBundle(snapshots))
        assertEquals(snapshots, restored)
        assertEquals(snapshots.map { it.runId }.toSet(), AgentRuntimeClient.ActiveRunsQuery.Known(restored).runIds)
    }

    @Test
    fun snapshotsDefaultOptionalFieldsAndIgnoreBlankRunIds() {
        val bundle = Bundle().apply {
            putParcelableArrayList("active_runs", arrayListOf(
                AgentRuntimeWire.ackBundle("legacy-run"),
                AgentRuntimeWire.ackBundle(""),
            ))
        }
        assertEquals(listOf(AgentRuntimeWire.ActiveRunSnapshot("legacy-run", "")), AgentRuntimeWire.activeRunsFromBundle(bundle))
    }

    @Test
    fun guiControlEventsRoundTripAndUnknownStatesAreIgnored() {
        GuiControlStatus.entries.forEach { status ->
            val event = AgentEvent.GuiControlChanged(status)
            assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
            assertNull(AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)))
        }
        assertNull(AgentRuntimeWire.eventFromBundle(Bundle().apply {
            putString("type", "gui_control_changed")
            putString("status", "future_state")
        }))
    }
}
