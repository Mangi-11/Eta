package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.db.EtaDatabase
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeResultStoreTest {
    @Test
    fun rewriteTargetSurvivesDatabaseReopenInResultHeaderArchiveAndCheckpoint() {
        val runId = "rewrite-storage-${System.nanoTime()}"
        val target = "assistant-original-1-0"
        val handoff = AgentRuntimeWire.EntryHandoff(runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation-1")
        val result = AgentRuntimeWire.RunResult(runId, true, "新的措辞",
            operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = target)
        val request = AgentRuntimeWire.RunRequest(
            runId = runId, prompt = "原始措辞", handoff = handoff, images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.com/v1", apiKey = "", model = "model", systemPrompt = ""),
            operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = target,
        )
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(handoff, emptyList(), result, 1L))
        AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff, result, 1L))
        EtaDatabase.closeForTests()

        assertEquals(target, AgentRuntimeResultStore.pendingPage(context).single().result.rewriteTargetMessageId)
        assertEquals(target, AgentRuntimeResultStore.readOwned(context, runId, handoff.payload)!!.result.rewriteTargetMessageId)
        assertEquals(target, AgentRunArchiveStore.list(context).single().result.rewriteTargetMessageId)
        assertEquals(target, AgentRunCheckpointStore.list(context).single().rewriteTargetMessageId)
    }

    @Test
    fun emptyLegacyTranscriptFallsBackToSuccessfulAssistantContent() {
        val runId = "legacy-${System.nanoTime()}"
        AgentRuntimeResultStore.add(
            context,
            AgentRuntimeWire.CompletedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = runId,
                    source = "agent_ui",
                    payload = "conversation-1",
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = true,
                    content = "旧版本结果",
                ),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val restored = AgentRuntimeResultStore.list(context).single { it.result.runId == runId }
        assertEquals(listOf("assistant"), restored.result.transcript.map { it.role })
        assertEquals("旧版本结果", restored.result.transcript.single().content)
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun acknowledgementBeforeCompletionPreventsLateResultWriteBack() {
        val runId = "ack-before-add-${System.nanoTime()}"
        val completedRun = AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "breeno",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "obsolete result",
            ),
            createdAt = System.currentTimeMillis(),
        )

        AgentRuntimeResultStore.remove(context, runId)

        assertFalse(AgentRuntimeResultStore.add(context, completedRun))
        assertTrue(AgentRuntimeResultStore.list(context).none { it.result.runId == runId })
    }

    @Test
    fun saveAndLoadPreservesTranscript() {
        val runId = "transcript-${System.nanoTime()}"
        val transcript = listOf(
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"call-1\"}]",
            ),
            AgentModelClient.ConversationMessage(
                role = "tool",
                toolCallId = "call-1",
                content = "{\"ok\":true}",
            ),
            AgentModelClient.ConversationMessage(role = "assistant", content = "完成"),
        )
        val completedRun = AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "breeno",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "完成",
                transcript = transcript,
            ),
            createdAt = System.currentTimeMillis(),
        )

        assertTrue(AgentRuntimeResultStore.add(context, completedRun))

        val restored = AgentRuntimeResultStore.list(context).single { it.result.runId == runId }
        assertEquals(transcript.map { it.role }, restored.result.transcript.map { it.role })
        assertEquals("call-1", restored.result.transcript[1].toolCallId)
        assertEquals("完成", restored.result.transcript.last().content)
    }

    @Test
    fun acknowledgementRemovesCommittedCheckpoint() {
        val runId = "checkpoint-${System.nanoTime()}"
        val request = AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "检查运行状态",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )
        val recorder = AgentRunCheckpointRecorder.create(context, request)!!
        recorder.accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uptime",
            )
        )
        recorder.seal()
        assertTrue(
            AgentRuntimeResultStore.add(
                context,
                AgentRuntimeWire.CompletedRun(
                    handoff = requireNotNull(request.handoff),
                    result = AgentRuntimeWire.RunResult(
                        runId = runId,
                        ok = true,
                        content = "完成",
                    ),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        )

        assertEquals(
            runId,
            AgentRunCheckpointStore.list(context).single().runId,
        )

        AgentRuntimeResultStore.remove(context, runId)

        assertTrue(AgentRuntimeResultStore.list(context).none { it.result.runId == runId })
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
    }

    @Test
    fun unacknowledgedResultsAndCheckpointsAreNotEvictedByCount() {
        val now = System.currentTimeMillis()
        repeat(9) { index ->
            val runId = "capacity-$index"
            createCheckpoint(runId)
            assertTrue(
                AgentRuntimeResultStore.add(
                    context,
                    completedRun(runId, createdAt = now + index),
                )
            )
        }

        assertEquals(9, AgentRuntimeResultStore.list(context).size)
        assertTrue(AgentRuntimeResultStore.list(context).any { it.result.runId == "capacity-0" })
        assertTrue(AgentRunCheckpointStore.list(context).any { it.runId == "capacity-0" })
        assertEquals(9, AgentRunCheckpointStore.list(context).size)
    }

    @Test
    fun unacknowledgedResultsAndCheckpointsAreNotEvictedByAge() {
        val runId = "expired-result"
        createCheckpoint(runId)

        assertTrue(
            AgentRuntimeResultStore.add(
                context,
                completedRun(
                    runId = runId,
                    createdAt = System.currentTimeMillis() - 13L * 60L * 60L * 1000L,
                ),
            )
        )

        assertEquals(runId, AgentRuntimeResultStore.list(context).single().result.runId)
        assertEquals(runId, AgentRunCheckpointStore.list(context).single().runId)
    }

    private fun createCheckpoint(runId: String) {
        val recorder = AgentRunCheckpointRecorder.create(
            context,
            AgentRuntimeWire.RunRequest(
                runId = runId,
                prompt = "测试",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.com/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                ),
                images = emptyList(),
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = runId,
                    source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                    payload = "conversation-1",
                ),
            ),
        )!!
        recorder.accept(AgentEvent.RunStarted(0, 0, 1, false))
        recorder.seal()
    }

    private fun completedRun(
        runId: String,
        createdAt: Long,
    ) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = createdAt,
    )
}
