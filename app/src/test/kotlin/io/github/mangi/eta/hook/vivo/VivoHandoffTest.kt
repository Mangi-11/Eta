package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VivoHandoffTest {
    @Test
    fun runtimeHandoffKeepsVivoSeparateAndPreservesTurnCorrelation() {
        val handoff = VivoHandoff.create("run", "trace", "request", "session", "你好")
        val restored = AgentRuntimeWire.entryHandoffFromBundle(AgentRuntimeWire.toBundle(handoff))
        val archive = AgentExternalArchivePayload.from(restored.payload)!!

        assertEquals("vivo", restored.source)
        assertTrue(restored.dismissEntrySurfaceOnForegroundOperation)
        assertEquals("你好", archive.userText)
        assertEquals("session", archive.conversationKey)
        assertEquals("trace", archive.adapterPayload.getString("traceId"))
        assertEquals("request", archive.adapterPayload.getString("requestId"))
    }

    @Test
    fun emptyNativeSessionDoesNotGroupUnrelatedTurnsTogether() {
        val archive = AgentExternalArchivePayload.from(
            VivoHandoff.create("run", "trace", "request", "", "你好").payload,
        )!!
        assertEquals("trace", archive.conversationKey)
    }
}
