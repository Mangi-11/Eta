package io.github.mangi.eta.agent.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.runtime.archive.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ipc.AgentUiHandoffPayload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConversationIdentityTest {
    @Test
    fun legacyAndStructuredUiHandoffsUseTheSameConversation() {
        val legacy = AgentRuntimeWire.EntryHandoff("run-a", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conv-a")
        val structured = legacy.copy(payload = AgentUiHandoffPayload("conv-a").toJson())
        assertEquals("conv-a", AgentConversationIdentity.fromHandoff(legacy, "run-a"))
        assertEquals("conv-a", AgentConversationIdentity.fromHandoff(structured, "run-b"))
    }

    @Test
    fun externalRunsKeepThePersistedArchiveIdentity() {
        val payload = AgentExternalArchivePayload("请求", "dialog-42", "会话").toJson()
        val handoff = AgentRuntimeWire.EntryHandoff("run-a", "entry_a", payload)
        assertEquals(
            AgentConversationIdentity.archiveConversationId("entry_a", "dialog-42"),
            AgentConversationIdentity.fromHandoff(handoff, "run-a"),
        )
        assertEquals(
            AgentConversationIdentity.fromHandoff(handoff, "run-a"),
            AgentConversationIdentity.fromHandoff(handoff.copy(id = "run-b"), "run-b"),
        )
        assertNotEquals(
            AgentConversationIdentity.fromHandoff(handoff, "run-a"),
            AgentConversationIdentity.fromHandoff(handoff.copy(source = "entry_b"), "run-a"),
        )
    }

    @Test
    fun requestsWithoutAConversationRemainRunScoped() {
        assertEquals("run:run-a", AgentConversationIdentity.fromHandoff(null, "run-a"))
        assertNotEquals(AgentConversationIdentity.fromHandoff(null, "run-a"),
            AgentConversationIdentity.fromHandoff(null, "run-b"))
    }

    @Test
    fun voiceArchiveKeepsItsPublishedPrefixAndDigest() {
        assertEquals("assistant-fa2945f5951c8f096f360631",
            AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "voice-conversation"))
    }
}
