package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.File
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentHistoryRetentionTest {
    private lateinit var context: Context
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "规则",
        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
    )

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After fun cleanup() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test fun fullHistoryAndLargeMessageSurviveDatabaseReopen() {
        val text = "中英文😀".repeat(420_000)
        val journal = listOf(AgentModelClient.ConversationMessage("user", text),
            AgentModelClient.ConversationMessage("assistant", "已完成全部步骤"))
        val summary = AgentModelClient.ConversationMessage("assistant", "摘要", contextSummary = true, compactedUserTurns = 1)
        val state = AgentChatUiState(messages = listOf(UserMessageUi("u", text)), history = listOf(summary),
            journal = journal, input = "", isStreaming = false, thinkingEnabled = false, appliedRuntimeRunIds = listOf("run"))
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to state), mapOf("c" to "历史"), mapOf("c" to 1L)) }
        val db = EtaDatabase.get(context).openHelper.readableDatabase
        db.query("SELECT MAX(length(content)) FROM agent_text_chunks").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getInt(0) <= 16_384)
        }
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.load(context).conversationsById.getValue("c")
        assertEquals(journal, restored.journal)
        assertEquals(listOf(summary), restored.history)
        assertEquals(text, (restored.messages.single() as UserMessageUi).content)
        assertEquals(listOf("run"), restored.appliedRuntimeRunIds)
        // 重复保存、缩短正文、删除会话都清理对应分块。
        runBlocking { AgentConversationStore.save(context, "c", mapOf("c" to restored), mapOf("c" to "历史"), mapOf("c" to 2L)) }
        assertEquals(journal, AgentConversationStore.load(context).conversationsById.getValue("c").journal)
        runBlocking { AgentConversationStore.save(context, null, emptyMap(), emptyMap(), emptyMap()) }
        EtaDatabase.get(context).openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_text_chunks").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun largeTranscriptAndSnapshotSurviveOutboxAndArchiveAndCheckpoint() {
        val transcript = listOf(AgentModelClient.ConversationMessage("assistant", "普通工具结果😀".repeat(300_000)))
        val snapshot = AgentContextSnapshot(operationId = "large-run", messages = transcript)
        val request = AgentRuntimeWire.RunRequest("large-run", "请求", config, emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff("large-run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "c"))
        AgentRunCheckpointStore.start(context, request)
        AgentRunCheckpointStore.saveTranscript(context, request.runId, transcript)
        AgentRunCheckpointStore.saveContext(context, request.runId, snapshot)
        val result = AgentRuntimeWire.RunResult(request.runId, true, "完成", transcript = transcript, contextSnapshot = snapshot)
        val completed = AgentRuntimeWire.CompletedRun(request.handoff!!, result, 1L)
        AgentRuntimeResultStore.add(context, completed)
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(completed.handoff, emptyList(), result, 1L))
        EtaDatabase.closeForTests()
        assertEquals(transcript, AgentRunCheckpointStore.list(context).single().transcript)
        assertEquals(snapshot, AgentRunCheckpointStore.list(context).single().contextSnapshot)
        assertEquals(result, AgentRuntimeResultStore.list(context).single().result)
        assertEquals("large-run", AgentRuntimeResultStore.pendingPage(context).single().result.contextSnapshotRef)
        assertTrue(AgentRuntimeResultStore.pendingPage(context).single().result.transcript.isEmpty())
        assertNull(AgentRuntimeResultStore.readOwned(context, request.runId, "other-conversation"))
        assertEquals(result, AgentRuntimeResultStore.readOwned(context, request.runId, "c")?.result)
        assertEquals(result, AgentRunArchiveStore.list(context).single().result)
        AgentRuntimeResultStore.remove(context, request.runId)
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        assertEquals(result, AgentRunArchiveStore.list(context).single().result)
        AgentRunArchiveStore.remove(context, request.runId)
        EtaDatabase.get(context).openHelper.readableDatabase.query("SELECT COUNT(*) FROM agent_text_chunks").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun missingChunkFailsInsteadOfReturningEmptyHistory() {
        val transcript = listOf(AgentModelClient.ConversationMessage("assistant", "事实".repeat(80_000)))
        val run = AgentRuntimeWire.CompletedRun(AgentRuntimeWire.EntryHandoff("broken", "test", "c"),
            AgentRuntimeWire.RunResult("broken", true, "完成", transcript = transcript), System.currentTimeMillis())
        AgentRuntimeResultStore.add(context, run)
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL("DELETE FROM agent_text_chunks WHERE chunk_index = 1")
        assertThrows(IllegalStateException::class.java) { AgentRuntimeResultStore.list(context) }
        assertThrows(Exception::class.java) { AgentConversationCodec.decodeTranscript("[invalid]") }
    }

    @Test fun largeRequestsAndResultsUseDescriptorsAndPreserveSummaryMetadata() {
        val text = "完整历史😀".repeat(100_000)
        val history = listOf(AgentModelClient.ConversationMessage("assistant", text,
            contextSummary = true, compactedUserTurns = 50, summaryThroughUserTurn = 51))
        val request = AgentRuntimeWire.RunRequest("wire-large", text, config.copy(systemPrompt = text), emptyList(), history)
        val bundle = AgentRuntimeWire.toBundle(request, emptyList(), context.cacheDir)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            assertTrue(parcel.dataSize() < 300_000)
        } finally { parcel.recycle() }
        val incoming = AgentRuntimeWire.incomingRunRequestFromBundle(bundle)
        assertEquals(request, AgentRuntimeImageTransfer.materialize(incoming))
        val result = AgentRuntimeWire.RunResult(request.runId, false, text, "fixture failure", transcript = history,
            contextSnapshot = AgentContextSnapshot(operationId = request.runId, messages = history))
        val resultBundle = AgentRuntimeWire.toBundle(result, context.cacheDir)
        assertEquals(result, AgentRuntimeWire.runResultFromBundle(resultBundle))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("eta-context-") })
    }

    @Test fun localMessengerOwnsIndependentDescriptorUntilReceiverConsumesIt() {
        val text = "跨线程完整正文😀".repeat(20_000)
        var received: Bundle? = null
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) { received = msg.data }
        })
        val bundle = Bundle()
        // Robolectric 的 dup 重新打开路径；此 fixture 保留目录项以验证独立描述符所有权。
        // 取消目录链接后的读取与文件清理由上面的完整请求往返测试覆盖。
        val file = File(context.cacheDir, "local-descriptor-fixture")
        file.writeText(text)
        try {
            bundle.putParcelable("history_eta_text_fd", ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            bundle.putLong("history_eta_text_bytes", file.length())
            AgentWireText.send(receiver, Message.obtain(null, 1).apply { data = bundle })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(text, AgentWireText.read(checkNotNull(received), "history"))
        } finally { file.delete() }
    }

    @Test fun incorrectDescriptorLengthIsRejectedAndClosed() {
        val bundle = Bundle()
        AgentWireText.put(bundle, "history", "abc".repeat(20_000), context.cacheDir)
        bundle.putLong("history_eta_text_bytes", 1)
        assertThrows(IllegalArgumentException::class.java) { AgentWireText.read(bundle, "history") }
        assertFalse(bundle.containsKey("history_eta_text_fd"))
    }
}
