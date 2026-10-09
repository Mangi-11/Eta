package io.github.mangi.eta.agent.overlay

import android.icu.text.ListFormatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
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

@Composable
internal fun AgentOverlayStatus.localizedText(): String = when (this) {
    AgentOverlayStatus.Preparing -> stringResource(R.string.overlay_preparing)
    AgentOverlayStatus.Received -> stringResource(R.string.overlay_received)
    is AgentOverlayStatus.PreparingTools -> pluralStringResource(
        R.plurals.overlay_preparing_tools,
        count,
        count,
    )
    is AgentOverlayStatus.ReasoningRound -> stringResource(R.string.overlay_reasoning_round, round)
    AgentOverlayStatus.RequestingModel -> stringResource(R.string.overlay_requesting_model)
    AgentOverlayStatus.ModelResponded -> stringResource(R.string.overlay_model_responded)
    AgentOverlayStatus.GeneratingToolArguments -> stringResource(R.string.overlay_generating_tool_arguments)
    AgentOverlayStatus.Reasoning -> stringResource(R.string.overlay_reasoning)
    AgentOverlayStatus.PreparingAnswer -> stringResource(R.string.overlay_preparing_answer)
    is AgentOverlayStatus.PlanningTools -> {
        val locale = LocalConfiguration.current.locales[0]
        val labels = names.map { toolDisplayName(it) }
        stringResource(R.string.overlay_planning_tools, ListFormatter.getInstance(locale).format(labels))
    }
    AgentOverlayStatus.SupplementReceived -> stringResource(R.string.overlay_supplement_received)
    is AgentOverlayStatus.RunningTool -> stringResource(R.string.overlay_running_tool, toolDisplayName(name))
    is AgentOverlayStatus.ToolCompleted -> stringResource(R.string.overlay_tool_completed, toolDisplayName(name))
    is AgentOverlayStatus.HostedToolRunning -> stringResource(R.string.overlay_hosted_tool_running, name)
    is AgentOverlayStatus.HostedToolFinished -> stringResource(
        if (success) R.string.overlay_hosted_tool_completed else R.string.overlay_hosted_tool_failed,
        name,
    )
    is AgentOverlayStatus.ImagesRead -> pluralStringResource(R.plurals.overlay_images_read, count, count)
    AgentOverlayStatus.ResultReady -> stringResource(R.string.overlay_result_ready)
    AgentOverlayStatus.RunFailed -> stringResource(R.string.overlay_run_failed)
    AgentOverlayStatus.GeneratingAnswer -> stringResource(R.string.overlay_generating_answer)
    AgentOverlayStatus.Stopping -> stringResource(R.string.overlay_stopping)
    AgentOverlayStatus.Paused -> stringResource(R.string.overlay_paused)
    AgentOverlayStatus.Continuing -> stringResource(R.string.overlay_continuing)
    AgentOverlayStatus.Finishing -> stringResource(R.string.overlay_finishing)
    AgentOverlayStatus.ContinuationUnavailable -> stringResource(R.string.overlay_continuation_unavailable)
    AgentOverlayStatus.Stopped -> stringResource(R.string.overlay_stopped)
}

@Composable
internal fun toolDisplayName(name: String): String {
    val resource = toolDisplayNameResource(name) ?: return io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.firstOrNull { it.name == name }?.title
        ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == name }?.title ?: name
    return stringResource(resource)
}

@StringRes
internal fun toolDisplayNameResource(name: String): Int? = when (name) {
    "batch" -> R.string.tool_batch
    "virtual_screen" -> R.string.tool_virtual_screen
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
    "web_search" -> R.string.tool_web_search
    "fetch_url" -> R.string.tool_fetch_url
    "terminal" -> R.string.tool_terminal
    "run_command" -> R.string.tool_run_command
    "inspect_app" -> R.string.tool_inspect_app
    "edit_file" -> R.string.tool_edit_file
    "stat_file" -> R.string.tool_stat_file
    "glob_files" -> R.string.tool_glob_files
    "grep_files" -> R.string.tool_grep_files
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
