package fuck.andes.agent.overlay

import fuck.andes.agent.runtime.AgentEvent

/**
 * Decides when the system-level operation overlay should become visible.
 *
 * Chat, reasoning, shell diagnostics, file reads, skill reads, app search and
 * screen observation all have good homes in the main conversation UI. The
 * global overlay is reserved for tools that actively drive the foreground
 * Android interface.
 */
internal object AgentOverlayVisibilityPolicy {
    fun shouldRevealFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.ProviderToolCallDelta -> event.name.isForegroundOperationTool()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.isForegroundOperationTool() }
        is AgentEvent.ToolStarted -> event.name.isForegroundOperationTool()
        is AgentEvent.ToolFinished -> event.name.isForegroundOperationTool()
        is AgentEvent.ToolImagesAttached -> event.toolName.isForegroundOperationTool()
        else -> false
    }

    private fun String?.isForegroundOperationTool(): Boolean =
        this?.trim()?.lowercase() in foregroundOperationTools

    private val foregroundOperationTools = setOf(
        "launch_app",
        "open_uri",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "input_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "open_system_panel",
    )
}
