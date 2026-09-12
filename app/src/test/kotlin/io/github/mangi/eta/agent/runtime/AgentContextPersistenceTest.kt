package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentContextPersistenceTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After fun close() {
        AgentRunArchiveStore.remove(context, "context-persist")
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun committedSnapshotSurvivesDatabaseReopenAndOutboxAcknowledgement() {
        val request = AgentRuntimeWire.RunRequest(
            "context-persist", "", AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            emptyList(), handoff = AgentRuntimeWire.EntryHandoff("context-persist", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            operation = AgentRuntimeWire.OP_COMPACT,
        )
        val summary = AgentModelClient.ConversationMessage("assistant", "已完成前序操作。",
            contextSummary = true, compactedUserTurns = 2, summaryThroughUserTurn = 2)
        val snapshot = AgentContextSnapshot(operationId = request.runId, messages = listOf(summary),
            coveredUserTurns = 2, consumedUserTurns = 2)
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentRunCheckpointStore.saveContext(context, request.runId, snapshot)
        val event = AgentEvent.ContextCompaction("op", "completed", 30_000, 2_000)
        AgentRunCheckpointStore.append(context, request.runId, 0, event)
        EtaDatabase.closeForTests()
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertEquals(snapshot, checkpoint.contextSnapshot)
        assertEquals(AgentRuntimeWire.OP_COMPACT, checkpoint.operation)
        assertEquals(listOf(event), checkpoint.events)
        val result = AgentRuntimeWire.RunResult(request.runId, true, "", contextSnapshot = snapshot,
            operation = AgentRuntimeWire.OP_COMPACT)
        val completed = AgentRuntimeWire.CompletedRun(request.handoff!!, result, System.currentTimeMillis())
        assertTrue(AgentRuntimeResultStore.add(context, completed))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(completed.handoff, listOf(event), result, completed.createdAt))
        EtaDatabase.closeForTests()
        assertEquals(result, AgentRuntimeResultStore.list(context).single().result)
        assertEquals(result, AgentRunArchiveStore.list(context).single().result)
        AgentRuntimeResultStore.remove(context, request.runId)
        assertTrue(AgentRuntimeResultStore.list(context).isEmpty())
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        assertFalse(AgentRuntimeResultStore.add(context, completed))
    }
}
