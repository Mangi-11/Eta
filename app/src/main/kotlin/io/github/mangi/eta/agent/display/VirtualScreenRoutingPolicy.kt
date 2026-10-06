package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicBoolean

/** Once a run uses virtual coordinates, revoking permission must not redirect it to the main screen. */
internal class VirtualScreenRoutingPolicy {
    private val routed = AtomicBoolean(false)

    fun shouldRoute(name: String, enabled: Boolean): Boolean {
        if (name !in uiTools) return false
        if (enabled) routed.set(true)
        return routed.get()
    }

    companion object {
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
            "wait_for_package"
        )
    }
}
