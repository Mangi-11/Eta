package io.github.mangi.eta.agent.runtime.archive

import io.github.mangi.eta.agent.runtime.ipc.AgentEvent

/** 工具参数增量不用于展示；搜索建议只允许活动 run 的内存重连回放。 */
internal fun AgentEvent.replayProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}

/** 网页建议不落盘，GUI 所有权与 Subagent 中间进度只在当前进程有效；三者保留活动 run 的重连回放。 */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.SearchSuggestions,
    is AgentEvent.GuiControlChanged,
    is AgentEvent.SubagentProgress,
    -> null
    else -> replayProjection()
}
