package io.github.mangi.eta.agent.runtime.archive

import android.content.Context
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire

/** 将高频文本增量合并后写入 checkpoint，结构化边界则同步落盘。 */
internal class AgentRunCheckpointRecorder private constructor(
    context: Context,
    private val runId: String,
    private val nanoTime: () -> Long,
) {
    private val appContext = context.applicationContext
    private var nextSortIndex = 0
    private var pendingDelta: AgentEvent.AssistantBlockDelta? = null
    private var lastFlushNanos = nanoTime()

    @Synchronized
    fun accept(event: AgentEvent) {
        val checkpointEvent = event.recoveryProjection() ?: return
        if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
            val pending = pendingDelta
            if (
                pending != null &&
                pending.round == checkpointEvent.round &&
                pending.kind == checkpointEvent.kind &&
                pending.index == checkpointEvent.index
            ) {
                pendingDelta = pending.copy(
                    deltaChars = pending.deltaChars + checkpointEvent.deltaChars,
                    delta = pending.delta + checkpointEvent.delta,
                )
            } else {
                flushPendingDelta()
                pendingDelta = checkpointEvent
            }
            val elapsed = nanoTime() - lastFlushNanos
            if (
                pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||
                elapsed >= MAX_BUFFERED_DELTA_NANOS
            ) {
                flushPendingDelta()
            }
            return
        }

        flushPendingDelta()
        append(checkpointEvent)
    }

    /** 把最后一段增量提交到日志；日志由结果 ACK 或中断恢复负责删除。 */
    @Synchronized
    fun seal() {
        flushPendingDelta()
    }

    @Synchronized
    fun discard() {
        pendingDelta = null
        AgentRunCheckpointStore.remove(appContext, runId)
    }

    private fun flushPendingDelta() {
        val event = pendingDelta ?: return
        pendingDelta = null
        append(event)
        lastFlushNanos = nanoTime()
    }

    private fun append(event: AgentEvent) {
        AgentRunCheckpointStore.append(
            context = appContext,
            runId = runId,
            sortIndex = nextSortIndex++,
            event = event,
        )
    }

    private fun AgentEvent.AssistantBlockDelta?.orEmptyChars(): Int = this?.deltaChars ?: 0

    companion object {
        private const val MAX_BUFFERED_DELTA_CHARS = 512
        private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L

        fun create(
            context: Context,
            request: AgentRuntimeWire.RunRequest,
            nanoTime: () -> Long = System::nanoTime,
        ): AgentRunCheckpointRecorder? {
            if (!AgentRunCheckpointStore.start(context, request)) return null
            return AgentRunCheckpointRecorder(
                context = context,
                runId = request.runId,
                nanoTime = nanoTime,
            )
        }
    }
}
