package io.github.mangi.eta.agent.runtime.archive

import io.github.mangi.eta.data.db.EtaDatabase
import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ipc.AgentUiHandoffPayload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunArchiveStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun archiveDropsSearchSuggestionsAndKeepsAnswer() {
        val answer = AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 2, "回答")
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff("search", "external_test", "fixture"),
            events = listOf(answer, AgentEvent.SearchSuggestions(1, 0, "<div>不应保存</div>")),
            result = AgentRuntimeWire.RunResult("search", true, "回答"),
            createdAt = 1L,
        ))
        val restored = AgentRunArchiveStore.list(context).single()
        assertEquals(listOf(answer), restored.events)
        assertEquals("回答", restored.result.content)
    }

    @Test
    fun onlyRunsWithAnExternalArchiveConsumerAllocateAnEventBuffer() {
        assertNull(AgentRunArchiveStore.createEventBuffer(null))
        assertNull(AgentRunArchiveStore.createEventBuffer(
            AgentRuntimeWire.EntryHandoff(
                id = "ui-run",
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = AgentUiHandoffPayload(conversationId = "conversation").toJson(),
            )
        ))
        assertNull(AgentRunArchiveStore.createEventBuffer(
            AgentRuntimeWire.EntryHandoff("invalid-run", "external_test", "invalid payload")
        ))
        assertNotNull(AgentRunArchiveStore.createEventBuffer(
            AgentRuntimeWire.EntryHandoff(
                id = "external-run",
                source = "external_test",
                payload = AgentExternalArchivePayload("请求", "session", "外部入口").toJson(),
            )
        ))
    }

    @Test
    fun saveAndLoadPreservesHandoffEventsAndResult() {
        val createdAt = System.currentTimeMillis()
        val archivedRun = AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-1",
                source = "external_test",
                payload = AgentExternalArchivePayload(
                    userText = "查一下系统状态",
                    conversationKey = "session-1",
                    title = "外部入口",
                ).toJson(),
            ),
            events = listOf(
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "先",
                ),
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "看",
                ),
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    argsPreview = "执行命令 · Android · root",
                    command = "uptime",
                ),
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    resultSummary = "ok=true, chars=42",
                    imageCount = 0,
                    imageBytes = 0,
                ),
                AgentEvent.RunFinished(
                    round = 1,
                    contentChars = 12,
                ),
            ),
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "系统状态正常",
                reasoningContent = "先看系统状态",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "系统状态正常",
                    )
                ),
            ),
            createdAt = createdAt,
            userImagePreviews = listOf(
                "data:image/png;base64,cHJldmlldw==",
            ),
        )

        AgentRunArchiveStore.add(context, archivedRun)

        val restored = AgentRunArchiveStore.list(context).single()

        assertEquals(archivedRun.handoff, restored.handoff)
        assertEquals(archivedRun.result, restored.result)
        assertEquals(archivedRun.createdAt, restored.createdAt)
        assertEquals(archivedRun.userImagePreviews, restored.userImagePreviews)
        assertEquals(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "先看",
            ),
            restored.events.first()
        )
        assertEquals(4, restored.events.size)
        assertEquals(archivedRun.events[2], restored.events[1])
    }

    @Test
    fun removeDeletesByRunIdOrHandoffId() {
        val createdAt = System.currentTimeMillis()
        AgentRunArchiveStore.add(
            context,
            AgentRunArchiveStore.ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "external_test",
                    payload = AgentExternalArchivePayload(
                        userText = "查一下系统状态",
                        conversationKey = "session-1",
                        title = "外部入口",
                    ).toJson(),
                ),
                events = emptyList(),
                result = AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = true,
                    content = "done",
                ),
                createdAt = createdAt,
            )
        )

        AgentRunArchiveStore.remove(context, "run-1")

        assertEquals(emptyList<AgentRunArchiveStore.ArchivedRun>(), AgentRunArchiveStore.list(context))
    }
}
