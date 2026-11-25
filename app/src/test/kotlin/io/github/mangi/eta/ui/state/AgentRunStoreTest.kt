package io.github.mangi.eta.ui.state

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class AgentRunStoreTest {
    private val pending = mutableListOf<Runnable>()
    private val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending += block
        }
    })
    private val delivered = mutableListOf<Pair<String, AgentEvent>>()
    private val store = AgentRunStore(scope) { id, event -> delivered += id to event }

    @After
    fun tearDown() {
        scope.cancel()
        while (pending.isNotEmpty()) pending.removeAt(0).run()
    }

    @Test
    fun differentConversationsRemainIndependentWhenCompletingInReverseOrder() {
        assertTrue(store.register("a", "conversation-a"))
        assertTrue(store.register("b", "conversation-b"))
        assertFalse(store.register("second-a", "conversation-a"))
        assertFalse(store.register("a", "conversation-b"))

        store.requestStop("b", byUser = true)
        assertFalse(store.isStopRequested("a"))
        assertTrue(store.wasStoppedByUser("b"))
        store.remove("b")

        assertEquals(setOf("a"), store.observedRunIds)
        assertEquals("a", store.activeRunId("conversation-a"))
        assertNull(store.activeRunId("conversation-b"))
        assertTrue(store.register("next-b", "conversation-b"))
    }

    @Test
    fun interleavedDeltasAndTerminalBoundariesFlushOnlyTheirOwnRun() {
        store.register("a", "conversation-a")
        store.register("b", "conversation-b")
        store.enqueue("a", delta("甲"))
        store.enqueue("b", delta("乙"))
        store.enqueue("a", delta("丙"))
        store.enqueue("b", AgentEvent.RunFinished(1, 1))

        assertEquals(listOf("b", "b"), delivered.map { it.first })
        assertEquals("乙", (delivered.first().second as AgentEvent.AssistantBlockDelta).delta)
        store.flush("a")
        assertEquals("甲丙", (delivered.last().second as AgentEvent.AssistantBlockDelta).delta)
    }

    @Test
    fun lateEventsDoNotRecreateRemovedRunOrChangeAnotherRunStatus() {
        store.register("a", "conversation-a")
        store.register("b", "conversation-b")
        store.updateGuiStatus("b", GuiControlStatus.Waiting)
        store.enqueue("a", delta("未提交"))
        store.remove("a")
        store.enqueue("a", AgentEvent.RunFinished(1, 1))
        store.updateGuiStatus("a", GuiControlStatus.Acquired)

        assertTrue(delivered.isEmpty())
        assertEquals(GuiControlStatus.Waiting, store.guiStatus("conversation-b"))
        assertNull(store.conversationId("a"))
    }

    @Test
    fun replayOnlyRecordsDoNotClaimAnActiveSubscription() {
        store.register("archived", "conversation-a", observed = false)
        assertTrue(store.observedRunIds.isEmpty())
        assertFalse(store.hasActiveRuns)
        assertEquals("conversation-a", store.conversationId("archived"))
        store.remove("archived")
    }

    @Test
    fun settlementRejectsLateEventsAndKeepsConversationUntilSaved() {
        store.register("a", "conversation-a")
        assertTrue(store.beginSettlement("a"))
        assertFalse(store.beginSettlement("a"))
        store.enqueue("a", delta("迟到的增量"))
        assertTrue(delivered.isEmpty())
        assertFalse(store.register("next-a", "conversation-a"))
        store.remove("a")
        assertTrue(store.register("next-a", "conversation-a"))
    }

    private fun delta(text: String) = AgentEvent.AssistantBlockDelta(
        round = 1, kind = AgentEvent.AssistantBlockKind.TEXT, index = 0,
        deltaChars = text.length, delta = text,
    )
}
