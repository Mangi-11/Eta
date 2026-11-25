package io.github.mangi.eta.ui.state

import io.github.mangi.eta.agent.runtime.archive.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
/** 用持久 checkpoint、终态 outbox 与 Runtime 活跃状态共同判定恢复动作。 */
internal object AgentRunRecoveryCoordinator {
    data class Completed(
        val result: AgentRuntimeWire.CompletedRun,
        val checkpoint: AgentRunCheckpointStore.Checkpoint?,
    )

    data class Plan(
        val completed: List<Completed>,
        val reattach: List<AgentRunCheckpointStore.Checkpoint>,
        val interrupted: List<AgentRunCheckpointStore.Checkpoint>,
    )

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunIds: Set<String>,
        locallyObservedRunIds: Set<String>,
    ): Plan {
        val uiCheckpoints = checkpoints
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .associateBy { it.runId }
        val completed = completedRuns
            .asSequence()
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .filterNot { it.stableRunId in locallyObservedRunIds }
            .sortedBy { it.createdAt }
            .map { run -> Completed(run, uiCheckpoints[run.stableRunId]) }
            .toList()
        val completedRunIds = completed.mapTo(mutableSetOf()) { it.result.stableRunId }
        val unresolved = uiCheckpoints.values
            .filterNot { it.runId in locallyObservedRunIds || it.runId in completedRunIds }
            .sortedBy { it.createdAt }
        val active = if (activeStateKnown) unresolved.filter { it.runId in activeRunIds } else emptyList()

        return Plan(
            completed = completed,
            reattach = active,
            interrupted = if (activeStateKnown && terminalStateKnown) {
                unresolved.filterNot { it.runId in activeRunIds }
            } else {
                emptyList()
            },
        )
    }

    internal val AgentRuntimeWire.CompletedRun.stableRunId: String
        get() = result.runId.ifBlank { handoff.id }
}
