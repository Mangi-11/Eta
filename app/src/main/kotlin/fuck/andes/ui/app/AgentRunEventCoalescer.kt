package fuck.andes.ui.app

import fuck.andes.agent.runtime.AgentEvent

/**
 * 合并相邻的文本增量，避免模型每个小分片都触发一次 Compose 状态更新。
 *
 * 这里只合并同一运行、轮次、内容块和类型的事件；块边界仍由调用方立即刷新，
 * 因而不会改变 Runtime 事件的先后语义。
 */
internal class AgentRunEventCoalescer {
    private val pendingByRun = mutableMapOf<String, PendingDelta>()

    fun append(
        runId: String,
        event: AgentEvent.AssistantBlockDelta,
    ): AgentEvent.AssistantBlockDelta? {
        val pending = pendingByRun[runId]
        if (pending?.matches(event) == true) {
            pending.delta.append(event.delta)
            pending.deltaChars += event.deltaChars
            return null
        }

        val ready = pending?.toEvent()
        pendingByRun[runId] = PendingDelta(event)
        return ready
    }

    fun flush(runId: String): AgentEvent.AssistantBlockDelta? =
        pendingByRun.remove(runId)?.toEvent()

    private class PendingDelta(event: AgentEvent.AssistantBlockDelta) {
        val round = event.round
        val kind = event.kind
        val index = event.index
        var deltaChars = event.deltaChars
        val delta = StringBuilder(event.delta)

        fun matches(event: AgentEvent.AssistantBlockDelta): Boolean =
            round == event.round && kind == event.kind && index == event.index

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind,
                index = index,
                deltaChars = deltaChars,
                delta = delta.toString(),
            )
    }
}
