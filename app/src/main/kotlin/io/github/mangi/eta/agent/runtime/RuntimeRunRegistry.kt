package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import io.github.mangi.eta.agent.runtime.archive.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ipc.AgentUiHandoffPayload

/** 请求摄取前占位；同一会话直到终态提交完成都只有一个写入者。 */
internal class RuntimeRunRegistry(private val capacity: Int = MAX_ACTIVE_RUNS) {
    init {
        require(capacity > 0)
    }
    sealed interface Admission {
        data class Accepted(val run: Run, val replacedRuns: List<Run> = emptyList()) : Admission
        data class Duplicate(val run: Run) : Admission
        data class Rejected(val code: String, val message: String) : Admission
    }

    class Run(
        initialRequest: AgentRuntimeWire.RunRequest,
        val session: AgentRuntimeSession,
        incoming: AgentRuntimeWire.IncomingRunRequest? = null,
        private val onRequestIngested: () -> Unit = {},
    ) {
        val runId: String get() = session.runId
        val conversationId: String = AgentConversationIdentity.fromHandoff(initialRequest.handoff, initialRequest.runId)
        val startedAtEpochMs: Long = System.currentTimeMillis()
        @Volatile var request: AgentRuntimeWire.RunRequest = initialRequest
            private set
        @Volatile var preparing: Boolean = true
            private set
        @Volatile var guiStatus: GuiControlStatus? = null
        var overlayState: AgentOverlayState = AgentOverlayState.Initial
        var hasExecutedForegroundTool: Boolean = false
        var completedContext: CompletedContext? = null
        internal var predecessors: List<Run> = emptyList()
        private val terminal = CountDownLatch(1)

        private val ingested = AtomicBoolean(false)
        private var pendingIncoming = incoming
        private val supplementsLock = Any()
        private val supplements = initialRequest.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).supplements }
            .orEmpty().toMutableList()
        private var nextSupplementIndex = initialRequest.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).lastSupplementIndex + 1 }
            ?: 1

        fun prepared(request: AgentRuntimeWire.RunRequest) {
            check(request.runId == runId)
            check(AgentConversationIdentity.fromHandoff(request.handoff, request.runId) == conversationId)
            this.request = request
            preparing = false
            releaseIncoming()
        }

        /** 外部助手替换自己的请求时，先等已取消前任排空，不能与其工具清理并行写会话。 */
        fun awaitPredecessors() {
            if (predecessors.isEmpty()) return
            val worker = Thread.currentThread()
            val cancellation = session.controller.register(worker::interrupt)
            try {
                predecessors.forEach { previous -> previous.terminal.await() }
            } catch (_: InterruptedException) {
                worker.interrupt()
                throw AgentRunCancelledException()
            } finally {
                cancellation.close()
                predecessors = emptyList()
            }
            session.controller.throwIfCancelled()
        }

        internal fun finished() = terminal.countDown()

        fun releaseIncoming() {
            if (!ingested.compareAndSet(false, true)) return
            pendingIncoming?.close()
            pendingIncoming = null
            onRequestIngested()
        }

        fun supplement(text: String): AgentEvent.UserSupplementReceived? = session.steer(text) {
            synchronized(supplementsLock) {
                val supplement = AgentUiHandoffPayload.Supplement(
                    index = nextSupplementIndex++,
                    text = text,
                    createdAt = System.currentTimeMillis(),
                )
                supplements += supplement
                AgentEvent.UserSupplementReceived(supplement.index, supplement.text)
            }
        }

        fun snapshotRequest(request: AgentRuntimeWire.RunRequest = this.request): AgentRuntimeWire.RunRequest {
            val handoff = request.handoff ?: return request
            if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return request
            val snapshot = synchronized(supplementsLock) { supplements.toList() }
            if (snapshot.isEmpty()) return request
            return request.copy(handoff = handoff.copy(
                payload = AgentUiHandoffPayload.from(handoff.payload).copy(supplements = snapshot).toJson(),
            ))
        }

        fun snapshot(): AgentRuntimeWire.ActiveRunSnapshot = AgentRuntimeWire.ActiveRunSnapshot(
            runId = runId,
            conversationId = conversationId,
            operation = request.operation,
            handoffSource = request.handoff?.source.orEmpty(),
            phase = when {
                session.isCommitting -> AgentRuntimeWire.RunPhase.Committing
                preparing -> AgentRuntimeWire.RunPhase.Preparing
                guiStatus == GuiControlStatus.Waiting -> AgentRuntimeWire.RunPhase.WaitingForGui
                session.controller.isPaused -> AgentRuntimeWire.RunPhase.Paused
                else -> AgentRuntimeWire.RunPhase.Running
            },
        )
    }

    data class CompletedContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private val lock = Any()
    private val runs = linkedMapOf<String, Run>()

    fun admit(request: AgentRuntimeWire.RunRequest, create: () -> Run): Admission = synchronized(lock) {
        val conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
        runs[request.runId]?.let { existing ->
            return if (existing.conversationId == conversationId) Admission.Duplicate(existing)
            else Admission.Rejected("RUN_ID_CONFLICT", "运行标识与已有会话不一致，请重新发起任务")
        }
        val previous = runs.values.filter { it.conversationId == conversationId }
        val source = request.handoff?.source
        val replacesExternalRun = source != null && source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE &&
            AgentExternalArchivePayload.from(request.handoff.payload) != null &&
            previous.all { it.request.handoff?.source == source }
        if (previous.isNotEmpty() && !replacesExternalRun) {
            return Admission.Rejected("CONVERSATION_BUSY", "当前会话仍有任务在运行，请等待完成或停止后再试")
        }
        if (runs.size >= capacity) {
            return Admission.Rejected("RUN_CAPACITY_REACHED", "同时运行的任务已达上限，请等待任务完成后再试")
        }
        val run = create()
        check(run.runId == request.runId && run.conversationId == conversationId)
        run.predecessors = previous
        runs[run.runId] = run
        Admission.Accepted(run, previous)
    }

    fun get(runId: String): Run? = synchronized(lock) { runs[runId] }

    fun all(): List<Run> = synchronized(lock) { runs.values.toList() }

    fun contains(run: Run): Boolean = synchronized(lock) { runs[run.runId] === run }

    fun finish(runId: String, session: AgentRuntimeSession): Run? = synchronized(lock) {
        runs[runId]?.takeIf { it.session === session }?.also {
            runs.remove(runId)
            it.finished()
        }
    }

    // 不持登记锁读取 Session 状态，终态发布回调会反向移除登记。
    fun snapshots(): List<AgentRuntimeWire.ActiveRunSnapshot> = all()
        .filterNot { it.session.isTerminal }
        .map(Run::snapshot)

    companion object {
        const val MAX_ACTIVE_RUNS = 8
    }
}
