package io.github.mangi.eta.agent.overlay

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.mangi.eta.R

/** 浮窗只保存语义状态，文案在渲染时根据当前系统语言解析。 */
internal sealed interface AgentOverlayStatus {
    data object Reasoning : AgentOverlayStatus
    data object SupplementReceived : AgentOverlayStatus
    data class RunningTool(val name: String) : AgentOverlayStatus
    data class HostedToolRunning(val name: String) : AgentOverlayStatus
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
internal fun AgentOverlayStatus.localizedText(): String = localizedText(LocalResources.current)

/** 通知等非 Compose 表面与浮层共用同一套状态文案。 */
internal fun AgentOverlayStatus.localizedText(resources: Resources): String = when (this) {
    AgentOverlayStatus.Reasoning -> resources.getString(R.string.overlay_reasoning)
    AgentOverlayStatus.SupplementReceived -> resources.getString(R.string.overlay_supplement_received)
    // 工具名本身就是动作（点击元素、读取文件），不再加"执行："前缀。
    is AgentOverlayStatus.RunningTool -> toolDisplayName(resources, name)
    is AgentOverlayStatus.HostedToolRunning -> resources.getString(R.string.overlay_hosted_tool_running, name)
    AgentOverlayStatus.ResultReady -> resources.getString(R.string.overlay_result_ready)
    AgentOverlayStatus.RunFailed -> resources.getString(R.string.overlay_run_failed)
    AgentOverlayStatus.GeneratingAnswer -> resources.getString(R.string.overlay_generating_answer)
    AgentOverlayStatus.Stopping -> resources.getString(R.string.overlay_stopping)
    AgentOverlayStatus.Paused -> resources.getString(R.string.overlay_paused)
    AgentOverlayStatus.Continuing -> resources.getString(R.string.overlay_continuing)
    AgentOverlayStatus.Finishing -> resources.getString(R.string.overlay_finishing)
    AgentOverlayStatus.ContinuationUnavailable -> resources.getString(R.string.overlay_continuation_unavailable)
    AgentOverlayStatus.Stopped -> resources.getString(R.string.overlay_stopped)
}

@Composable
internal fun toolDisplayName(name: String): String = toolDisplayName(LocalResources.current, name)

internal fun toolDisplayName(resources: Resources, name: String): String {
    val resource = toolDisplayNameResource(name) ?: return io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.firstOrNull { it.name == name }?.title
        ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == name }?.title ?: name
    return resources.getString(resource)
}

@StringRes
internal fun toolDisplayNameResource(name: String): Int? = when (name) {
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
    "type_text", "input_text" -> R.string.tool_input_text
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
