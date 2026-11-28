package io.github.mangi.eta.agent.runtime.archive

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent

/** 工具参数增量不用于展示，不进入回放与恢复投影。 */
internal fun AgentEvent.replayProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}

/** GUI 所有权与 Subagent 中间进度只在当前进程有效；保留活动 run 的重连回放。 */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.GuiControlChanged,
    is AgentEvent.SubagentProgress,
    -> null
    else -> replayProjection()
}
