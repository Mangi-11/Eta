package fuck.andes.agent.overlay

import androidx.compose.runtime.Immutable
import fuck.andes.agent.runtime.AgentEvent

/** Agent 浮窗所处的阶段。 */
internal enum class AgentOverlayPhase { RUNNING, FINISHED, FAILED }

/**
 * Agent 浮窗的渲染状态。由 [AgentEvent] 流累积而来，[AgentOverlayContent] 直接消费。
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val statusText: String = "准备中…",
    val detailText: String = "",
) {
    companion object {
        val Initial = AgentOverlayState(statusText = "收到指令，准备调用模型")
    }
}

/**
 * 将一个 [AgentEvent] 折叠进当前渲染状态。
 *
 * 文案逻辑只保留面向用户的一句话状态，
 * 工具名经 [toToolLabel] 中文化。详细 trace 流作为后续任务，此处不展开。
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState = when (event) {
    is AgentEvent.RunStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        statusText = "准备工具：${event.toolCount} 个",
        detailText = "",
    )

    is AgentEvent.RoundStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "第 ${event.round} 轮思考",
    )

    is AgentEvent.ProviderRequestStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "正在请求模型",
    )

    is AgentEvent.ProviderResponseStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "模型已响应",
    )

    is AgentEvent.AssistantTextDelta -> appendStreamingText(event)

    is AgentEvent.ProviderToolCallDelta -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "正在生成工具参数",
    )

    is AgentEvent.AssistantReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = if (event.toolNames.isEmpty()) {
            "正在整理回答"
        } else {
            "计划执行：${event.toolNames.joinToString("、") { it.toToolLabel() }}"
        },
    )

    is AgentEvent.ToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "执行工具：${event.name.toToolLabel()}",
    )

    is AgentEvent.ToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "工具完成：${event.name.toToolLabel()}",
    )

    is AgentEvent.ToolImagesAttached -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "已读取图片：${event.imageCount} 张",
    )

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        statusText = "已返回结果",
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        statusText = "调用失败",
        detailText = event.reason,
    )
}

/**
 * 工具原始名 -> 中文标签，供渲染层共用。
 */
private fun String.toToolLabel(): String = when (this) {
    "observe_screen" -> "观察屏幕"
    "tap" -> "点击"
    "tap_element" -> "点击元素"
    "long_press" -> "长按"
    "long_press_element" -> "长按元素"
    "swipe" -> "滑动"
    "scroll" -> "滚动"
    "scroll_element" -> "滚动元素"
    "input_text" -> "输入文字"
    "replace_text" -> "替换文字"
    "clear_text" -> "清空文字"
    "set_clipboard" -> "写剪贴板"
    "get_clipboard" -> "读剪贴板"
    "paste_text" -> "粘贴文字"
    "press_key" -> "按键"
    "wait" -> "等待"
    "wait_for_text" -> "等待文本"
    "wait_for_package" -> "等待应用"
    "open_system_panel" -> "系统面板"
    "search_apps" -> "搜索应用"
    "launch_app" -> "打开应用"
    "open_uri" -> "打开链接"
    "terminal" -> "终端"
    "run_command" -> "执行命令"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "list_directory" -> "列出目录"
    else -> this
}

private const val MaxStreamingPreviewChars = 320

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantTextDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .takeLast(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "正在生成回答",
        detailText = nextPreview,
    )
}
