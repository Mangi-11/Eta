package io.github.mangi.eta.ui.state

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** UI 订阅的生命周期和事件缓冲；所有方法在主线程调用，执行事实仍由 Runtime 持有。 */
internal class AgentRunStore(
    private val scope: CoroutineScope,
    private val onEvent: (String, AgentEvent) -> Unit,
) {
    private class Entry(val conversationId: String, val observed: Boolean) {
        var job: Job? = null
        var flushJob: Job? = null
        var stopRequested = false
        var stoppedByUser = false
        var settling = false
        var guiStatus: GuiControlStatus? = null
    }

    private val entries = linkedMapOf<String, Entry>()
    private val coalescer = AgentRunEventCoalescer()

    val observedRunIds: Set<String>
        get() = entries.filterValues { it.observed }.keys.toSet()

    val hasActiveRuns: Boolean get() = entries.values.any { it.observed }

    fun activeRunId(conversationId: String?): String? = entries.entries.firstOrNull {
        it.value.observed && it.value.conversationId == conversationId
    }?.key

    fun conversationId(runId: String): String? = entries[runId]?.conversationId

    fun register(runId: String, conversationId: String, observed: Boolean = true): Boolean {
        if (runId in entries || observed && activeRunId(conversationId) != null) return false
        entries[runId] = Entry(conversationId, observed)
        return true
    }

    fun observe(runId: String, job: Job) {
        val entry = checkNotNull(entries[runId])
        check(entry.observed && entry.job == null)
        entry.job = job
    }

    fun requestStop(runId: String, byUser: Boolean = false): Boolean {
        val entry = entries[runId] ?: return false
        entry.stoppedByUser = entry.stoppedByUser || byUser
        if (entry.stopRequested) return false
        entry.stopRequested = true
        return true
    }

    fun isStopRequested(runId: String): Boolean = entries[runId]?.stopRequested == true

    fun wasStoppedByUser(runId: String): Boolean = entries[runId]?.stoppedByUser == true

    fun beginSettlement(runId: String): Boolean {
        val entry = entries[runId] ?: return false
        if (entry.settling) return false
        entry.settling = true
        entry.guiStatus = null
        return true
    }

    fun guiStatus(conversationId: String): GuiControlStatus? =
        activeRunId(conversationId)?.let(entries::get)?.guiStatus

    fun updateGuiStatus(runId: String, status: GuiControlStatus) {
        entries[runId]?.guiStatus = status
    }

    fun enqueue(runId: String, event: AgentEvent) {
        val entry = entries[runId] ?: return
        if (entry.settling) return
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return
            coalescer.append(runId, event)?.let { onEvent(runId, it) }
            if (entry.flushJob?.isActive != true) {
                entry.flushJob = scope.launch {
                    delay(STREAM_UI_UPDATE_INTERVAL_MS)
                    entry.flushJob = null
                    flush(runId)
                }
            }
        } else {
            flush(runId)
            onEvent(runId, event)
        }
    }

    fun flush(runId: String) {
        val entry = entries[runId] ?: return
        entry.flushJob?.cancel()
        entry.flushJob = null
        coalescer.flush(runId)?.let { onEvent(runId, it) }
    }

    fun remove(runId: String) {
        entries.remove(runId)?.flushJob?.cancel()
        coalescer.flush(runId)
    }

    private companion object {
        const val STREAM_UI_UPDATE_INTERVAL_MS = 80L
    }
}
