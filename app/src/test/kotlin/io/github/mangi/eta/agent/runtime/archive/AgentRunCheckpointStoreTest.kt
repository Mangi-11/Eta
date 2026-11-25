package io.github.mangi.eta.agent.runtime.archive

import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunCheckpointStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun searchSuggestionsNeverReachCheckpointEvenThroughDirectAppend() {
        val request = request("search-transient")
        val recorder = AgentRunCheckpointRecorder.create(context, request)!!
        val event = AgentEvent.SearchSuggestions(1, 0, "<div>短期搜索建议</div>")
        recorder.accept(event)
        AgentRunCheckpointStore.append(context, request.runId, 0, event)
        recorder.accept(textDelta("正文与引用"))
        recorder.seal()
        val restored = AgentRunCheckpointStore.list(context).single()
        assertEquals(listOf(textDelta("正文与引用")), restored.events)
    }

    @Test
    fun recorderCoalescesTextAndPreservesVisibleToolTrace() {
        var nowNanos = 0L
        val request = request("run-1")
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request,
            nanoTime = { nowNanos },
        )!!

        recorder.accept(textDelta("你"))
        nowNanos = 300_000_000L
        recorder.accept(textDelta("好"))
        recorder.accept(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                index = 1,
                deltaChars = 18,
                delta = "{\"secret\":\"value\"}",
            )
        )
        recorder.accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uptime",
            )
        )
        recorder.accept(
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                resultSummary = "完成",
                imageCount = 0,
                imageBytes = 0,
                success = true,
            )
        )

        val restored = AgentRunCheckpointStore.list(context).single()
        val delta = restored.events.filterIsInstance<AgentEvent.AssistantBlockDelta>().single()
        assertEquals("你好", delta.delta)
        assertEquals(2, delta.deltaChars)
        val toolStarted = restored.events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("执行命令 · Android · root", toolStarted.argsPreview)
        assertEquals("uptime", toolStarted.command)
        val toolFinished = restored.events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("完成", toolFinished.resultSummary)
        assertFalse(restored.events.any { event ->
            event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
        })

        recorder.seal()
        assertEquals(
            "run-1",
            AgentRunCheckpointStore.list(context).single().runId,
        )
        AgentRunCheckpointStore.remove(context, "run-1")
        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    @Test
    fun checkpointsRemainVisibleToAReplacementUiInTheSameProcess() {
        val request = request("run-2")
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = request,
                ownerInstanceId = "old-process",
            )
        )
        AgentRunCheckpointStore.append(
            context = context,
            runId = request.runId,
            sortIndex = 0,
            event = AgentEvent.RunStarted(0, 0, 1, false),
        )

        assertEquals(
            "run-2",
            AgentRunCheckpointStore.list(context).single().runId,
        )
    }

    @Test
    fun discardRemovesCheckpointWithoutFlushingBufferedText() {
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request("run-discard"),
            nanoTime = { 0L },
        )!!
        recorder.accept(textDelta("不应保留"))

        recorder.discard()

        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    private fun request(runId: String): AgentRuntimeWire.RunRequest =
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
        )

    private fun textDelta(text: String): AgentEvent.AssistantBlockDelta =
        AgentEvent.AssistantBlockDelta(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            deltaChars = text.length,
            delta = text,
        )
}
