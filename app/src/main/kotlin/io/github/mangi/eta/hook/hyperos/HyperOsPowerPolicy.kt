package io.github.mangi.eta.hook.hyperos

import io.github.mangi.eta.core.ModuleConfig

internal object HyperOsPowerPolicy {
    fun isAssistantShortcut(function: String?, source: String?): Boolean =
        function == "launch_voice_assistant" &&
            (source == "long_press_power_key" || source == "imperceptible_press_power_key")
}

internal class HyperOsPowerDispatchGate(
    private val dedupWindowMs: Long = ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS,
) {
    private var lastLaunchUptimeMs: Long? = null

    @Synchronized
    fun shouldSuppressDuplicate(nowUptimeMs: Long): Boolean {
        val last = lastLaunchUptimeMs ?: return false
        return nowUptimeMs - last in 0L..dedupWindowMs
    }

    @Synchronized
    fun markLaunched(nowUptimeMs: Long) {
        lastLaunchUptimeMs = nowUptimeMs
    }

    @Synchronized
    fun reset() {
        lastLaunchUptimeMs = null
    }
}
