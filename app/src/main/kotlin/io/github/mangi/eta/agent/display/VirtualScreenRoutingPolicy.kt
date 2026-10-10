package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicBoolean

/** Once a run uses virtual coordinates, revoking permission must not redirect it to the main screen. */
internal class VirtualScreenRoutingPolicy(initiallyRouted: Boolean = false) {
    private val routed = AtomicBoolean(initiallyRouted)
    private val primaryApproved = AtomicBoolean(false)
    val usesPrimary: Boolean get() = primaryApproved.get()

    fun shouldRoute(name: String, enabled: Boolean): Boolean {
        if (name !in uiTools || usesPrimary) return false
        if (enabled) routed.set(true)
        return routed.get()
    }

    fun approvePrimary() { primaryApproved.set(true) }

    companion object {
        val fallbackErrors = setOf(
            "ROOT_REQUIRED", "DEVICE_UNSUPPORTED", "ROOT_DISPLAY_UNAVAILABLE", "VIRTUAL_SCREEN_FAILED",
            "DISPLAY_LIFECYCLE_UNAVAILABLE",
            "DISPLAY_GONE", "NO_VIRTUAL_SCREEN", "ROOT_DISPLAY_DISCONNECTED", "DISPLAY_FRAME_PENDING",
            "APP_ALREADY_RUNNING", "DISPLAY_APP_UNSUPPORTED", "DISPLAY_LAUNCH_REJECTED", "DISPLAY_LAUNCH_MISMATCH",
            "VIRTUAL_ACTION_UNSUPPORTED", "ACCESSIBILITY_UNAVAILABLE",
        )
        val allowedBeforePrimaryObservation = setOf("launch_app", "open_uri", "observe_screen", "wait", "wait_for_text", "wait_for_package")
        val uiTools = setOf(
            "launch_app",
            "open_uri",
            "observe_screen",
            "tap",
            "tap_area",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "set_clipboard",
            "get_clipboard",
            "paste_text",
            "press_key",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "open_system_panel",
            "set_alarm",
            "set_timer",
            "browser_use",
        )
        val unavailableTools = setOf("open_system_panel", "set_alarm", "set_timer", "browser_use")
        val coordinateTools = setOf(
            "observe_screen",
            "tap",
            "tap_area",
            "long_press",
            "swipe",
            "scroll",
            "press_key",
            "wait_for_package",
            "type_text", "input_text", "replace_text", "clear_text", "paste_text", "set_clipboard", "get_clipboard",
        )
        val nodeTools = setOf("tap_element", "long_press_element", "scroll_element", "wait_for_text")
    }
}
