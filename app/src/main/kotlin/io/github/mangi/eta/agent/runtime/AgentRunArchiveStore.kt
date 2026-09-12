package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeArchiveEventEntity
import io.github.mangi.eta.data.db.RuntimeArchiveRunEntity
import io.github.mangi.eta.data.db.RuntimeArchiveRunWithEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * Process-persistent archive for externally initiated runs that should later be
 * mirrored into the module's own chat history.
 *
 * Unlike [AgentRuntimeResultStore], entries here are not an entry-adapter retry
 * queue. They preserve the event trace so the first-party UI can reconstruct
 * thinking and tool activity that third-party assistant surfaces cannot show.
 */
internal object AgentRunArchiveStore {

    data class ArchivedRun(
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val result: AgentRuntimeWire.RunResult,
        val createdAt: Long,
        val userImagePreviews: List<String> = emptyList(),
    )

    fun add(context: Context, run: ArchivedRun) {
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(appContext).runtimeRunDao()
            val compacted = run.copy(events = compactEvents(run.events))
            val archiveRunId = compacted.archiveRunId
            dao.replaceArchivedRun(
                run = compacted.toEntity(archiveRunId),
                events = compacted.toEventEntities(archiveRunId),
            )
        }
    }

    fun list(context: Context): List<ArchivedRun> {
        val appContext = context.applicationContext
        return runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext).runtimeRunDao().archivedRuns()
                .mapNotNull { it.toDomain() }
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(appContext)
                .runtimeRunDao()
                .deleteArchivedRun(runId)
        }
    }

    private val ArchivedRun.archiveRunId: String
        get() = result.runId.ifBlank { handoff.id }

    private fun ArchivedRun.toEntity(archiveRunId: String): RuntimeArchiveRunEntity =
        RuntimeArchiveRunEntity(
            archiveRunId = archiveRunId,
            runId = result.runId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            contextSnapshotJson = result.contextSnapshot?.encode().orEmpty(),
            operation = result.operation,
            rewriteTargetMessageId = result.rewriteTargetMessageId,
            userImagePreviewsJson = JSONArray(userImagePreviews).toString(),
            createdAt = createdAt,
        )

    private fun ArchivedRun.toEventEntities(archiveRunId: String): List<RuntimeArchiveEventEntity> =
        events.mapIndexed { index, event ->
            RuntimeArchiveEventEntity(
                archiveRunId = archiveRunId,
                sortIndex = index,
                eventJson = AgentEventJsonCodec.encode(event),
            )
        }

    private fun RuntimeArchiveRunWithEvents.toDomain(): ArchivedRun? =
        runCatching {
            ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = run.handoffId,
                    source = run.handoffSource,
                    payload = run.handoffPayload,
                    dismissEntrySurfaceOnForegroundOperation = run.dismissEntrySurface,
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = run.runId.ifBlank { run.archiveRunId },
                    ok = run.ok,
                    content = run.content,
                    error = run.error,
                    reasoningContent = run.reasoningContent,
                    contextSnapshot = AgentContextSnapshot.decode(run.contextSnapshotJson),
                    operation = run.operation,
                    rewriteTargetMessageId = run.rewriteTargetMessageId,
                    transcript = AgentConversationCodec.decodeTranscript(run.transcriptJson).ifEmpty {
                        if (!run.ok || run.content.isBlank()) return@ifEmpty emptyList()
                        listOf(
                            AgentModelClient.ConversationMessage(
                                role = "assistant",
                                content = run.content,
                                reasoningContent = run.reasoningContent,
                            )
                        )
                    },
                ),
                createdAt = run.createdAt,
                userImagePreviews = JSONArray(run.userImagePreviewsJson).let { previews ->
                    buildList {
                        for (index in 0 until previews.length()) {
                            previews.optString(index)
                                .takeIf { it.startsWith("data:image/") }
                                ?.let(::add)
                        }
                    }
                },
                events = events
                    .sortedBy { it.sortIndex }
                    .mapNotNull { event -> AgentEventJsonCodec.decode(event.eventJson) },
            )
        }.getOrNull()

    private fun compactEvents(events: List<AgentEvent>): List<AgentEvent> {
        val compacted = mutableListOf<AgentEvent>()
        events.forEach { event ->
            if (
                event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
            ) {
                return@forEach
            }
            val previous = compacted.lastOrNull()
            val merged = when {
                previous is AgentEvent.AssistantBlockDelta &&
                    event is AgentEvent.AssistantBlockDelta &&
                    previous.round == event.round -> {
                    if (previous.kind == event.kind && previous.index == event.index) {
                        previous.copy(
                            delta = previous.delta + event.delta,
                            deltaChars = previous.deltaChars + event.deltaChars,
                        )
                    } else {
                        null
                    }
                }

                else -> null
            }
            if (merged != null) {
                compacted[compacted.lastIndex] = merged
            } else {
                compacted += event
            }
        }
        return compacted
    }
}
