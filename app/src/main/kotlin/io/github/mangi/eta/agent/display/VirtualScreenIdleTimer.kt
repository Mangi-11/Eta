package io.github.mangi.eta.agent.display

import io.github.mangi.eta.data.model.VirtualScreenIdleTimeout

/** Viewer frame polling does not prolong an idle session; running tasks never expire. */
internal class VirtualScreenIdleTimer(
    minutes: Int,
    private val now: () -> Long,
    active: Boolean = false,
) {
    private var timeoutMinutes = VirtualScreenIdleTimeout.normalize(minutes)
    private var running = active
    private var lastActivity = now()

    @Synchronized
    fun update(minutes: Int, active: Boolean) {
        timeoutMinutes = VirtualScreenIdleTimeout.normalize(minutes)
        if (running != active) lastActivity = now()
        running = active
    }

    @Synchronized
    fun touch() { lastActivity = now() }

    @Synchronized
    fun expired(): Boolean = !running && timeoutMinutes > 0 &&
        now() - lastActivity >= timeoutMinutes * 60_000L
}
