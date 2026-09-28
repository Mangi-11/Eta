package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.icu.text.ListFormatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R

/** 浮窗只保存语义状态，文案在渲染时根据当前系统语言解析。 */
internal sealed interface AgentOverlayStatus {
    data object Preparing : AgentOverlayStatus
    data object Received : AgentOverlayStatus
    data class PreparingTools(val count: Int) : AgentOverlayStatus
    data class ReasoningRound(val round: Int) : AgentOverlayStatus
    data object RequestingModel : AgentOverlayStatus
    data object ModelResponded : AgentOverlayStatus
    data object GeneratingToolArguments : AgentOverlayStatus
    data object Reasoning : AgentOverlayStatus
    data object PreparingAnswer : AgentOverlayStatus
    data class PlanningTools(val names: List<String>) : AgentOverlayStatus
    data object SupplementReceived : AgentOverlayStatus
    data class RunningTool(val name: String) : AgentOverlayStatus
    data class ToolCompleted(val name: String) : AgentOverlayStatus
    data class HostedToolRunning(val name: String) : AgentOverlayStatus
    data class HostedToolFinished(val name: String, val success: Boolean) : AgentOverlayStatus
    data class ImagesRead(val count: Int) : AgentOverlayStatus
    data object ResultReady : AgentOverlayStatus
    data object RunFailed : AgentOverlayStatus
    data object GeneratingAnswer : AgentOverlayStatus
    data object Stopping : AgentOverlayStatus
    data object Paused : AgentOverlayStatus
    data object Continuing : AgentOverlayStatus
    data object Finishing : AgentOverlayStatus
    data object ContinuationUnavailable : AgentOverlayStatus
    data object Stopped : AgentOverlayStatus
}

/**
 * 非 Composable 的状态文案解析。
 *
 * Android 16 实况通知在 Composition 之外组装，需要同一套文案；这里作为唯一事实源，
 * Composable 侧只负责建立语言/区域的重组依赖后转发。
 */
internal fun Context.agentOverlayStatusText(status: AgentOverlayStatus): String = when (status) {
    AgentOverlayStatus.Preparing -> getString(R.string.overlay_preparing)
    AgentOverlayStatus.Received -> getString(R.string.overlay_received)
    is AgentOverlayStatus.PreparingTools -> resources.getQuantityString(
        R.plurals.overlay_preparing_tools,
        status.count,
        status.count,
    )
    is AgentOverlayStatus.ReasoningRound ->
        getString(R.string.overlay_reasoning_round, status.round)
    AgentOverlayStatus.RequestingModel -> getString(R.string.overlay_requesting_model)
    AgentOverlayStatus.ModelResponded -> getString(R.string.overlay_model_responded)
    AgentOverlayStatus.GeneratingToolArguments -> getString(R.string.overlay_generating_tool_arguments)
    AgentOverlayStatus.Reasoning -> getString(R.string.overlay_reasoning)
    AgentOverlayStatus.PreparingAnswer -> getString(R.string.overlay_preparing_answer)
    is AgentOverlayStatus.PlanningTools -> {
        val locale = resources.configuration.locales[0]
        val labels = status.names.map { toolDisplayText(it) }
        getString(R.string.overlay_planning_tools, ListFormatter.getInstance(locale).format(labels))
    }
    AgentOverlayStatus.SupplementReceived -> getString(R.string.overlay_supplement_received)
    is AgentOverlayStatus.RunningTool ->
        getString(R.string.overlay_running_tool, toolDisplayText(status.name))
    is AgentOverlayStatus.ToolCompleted ->
        getString(R.string.overlay_tool_completed, toolDisplayText(status.name))
    is AgentOverlayStatus.HostedToolRunning ->
        getString(R.string.overlay_hosted_tool_running, status.name)
    is AgentOverlayStatus.HostedToolFinished -> getString(
        if (status.success) R.string.overlay_hosted_tool_completed
        else R.string.overlay_hosted_tool_failed,
        status.name,
    )
    is AgentOverlayStatus.ImagesRead -> resources.getQuantityString(
        R.plurals.overlay_images_read,
        status.count,
        status.count,
    )
    AgentOverlayStatus.ResultReady -> getString(R.string.overlay_result_ready)
    AgentOverlayStatus.RunFailed -> getString(R.string.overlay_run_failed)
    AgentOverlayStatus.GeneratingAnswer -> getString(R.string.overlay_generating_answer)
    AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
    AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
    AgentOverlayStatus.Continuing -> getString(R.string.overlay_continuing)
    AgentOverlayStatus.Finishing -> getString(R.string.overlay_finishing)
    AgentOverlayStatus.ContinuationUnavailable -> getString(R.string.overlay_continuation_unavailable)
    AgentOverlayStatus.Stopped -> getString(R.string.overlay_stopped)
}

@Composable
internal fun AgentOverlayStatus.localizedText(): String {
    // 建立语言/区域的重组依赖，再复用同一套 Context 解析
    LocalConfiguration.current
    return LocalContext.current.agentOverlayStatusText(this)
}

internal fun Context.toolDisplayText(name: String): String {
    val resource = toolDisplayNameResource(name) ?: return name
    return getString(resource)
}

@Composable
internal fun toolDisplayName(name: String): String {
    val resource = toolDisplayNameResource(name) ?: return name
    return stringResource(resource)
}

@StringRes
internal fun toolDisplayNameResource(name: String): Int? = when (name) {
    "observe_screen" -> R.string.tool_observe_screen
    "tap" -> R.string.tool_tap
    "tap_element" -> R.string.tool_tap_element
    "tap_area" -> R.string.tool_tap_area
    "long_press" -> R.string.tool_long_press
    "long_press_element" -> R.string.tool_long_press_element
    "swipe" -> R.string.tool_swipe
    "scroll" -> R.string.tool_scroll
    "scroll_element" -> R.string.tool_scroll_element
    "input_text" -> R.string.tool_input_text
    "replace_text" -> R.string.tool_replace_text
    "clear_text" -> R.string.tool_clear_text
    "set_clipboard" -> R.string.tool_set_clipboard
    "get_clipboard" -> R.string.tool_get_clipboard
    "paste_text" -> R.string.tool_paste_text
    "press_key" -> R.string.tool_press_key
    "wait" -> R.string.tool_wait
    "wait_for_text" -> R.string.tool_wait_for_text
    "wait_for_package" -> R.string.tool_wait_for_package
    "get_current_context" -> R.string.tool_current_context
    "open_system_panel" -> R.string.tool_open_system_panel
    "search_apps" -> R.string.tool_search_apps
    "launch_app" -> R.string.tool_launch_app
    "open_uri" -> R.string.tool_open_uri
    "browser_use" -> R.string.tool_browser_use
    "terminal" -> R.string.tool_terminal
    "run_command" -> R.string.tool_run_command
    "read_file" -> R.string.tool_read_file
    "write_file" -> R.string.tool_write_file
    "list_directory" -> R.string.tool_list_directory
    "memory_get" -> R.string.tool_memory_get
    "memory_write" -> R.string.tool_memory_write
    "set_alarm" -> R.string.tool_set_alarm
    "set_timer" -> R.string.tool_set_timer
    "device_status" -> R.string.tool_device_status
    "network_info" -> R.string.tool_network_info
    "media_control" -> R.string.tool_media_control
    "set_volume" -> R.string.tool_set_volume
    "top_memory_apps" -> R.string.tool_top_memory_apps
    "top_storage_apps" -> R.string.tool_top_storage_apps
    "read_sms_code" -> R.string.tool_read_sms_code
    "recent_notifications" -> R.string.tool_recent_notifications
    "wifi_credentials" -> R.string.tool_wifi_credentials
    "get_setting" -> R.string.tool_get_setting
    "set_setting" -> R.string.tool_set_setting
    "set_device_state" -> R.string.tool_set_device_state
    "app_state_control" -> R.string.tool_app_state_control
    "get_logcat" -> R.string.tool_get_logcat
    else -> null
}
