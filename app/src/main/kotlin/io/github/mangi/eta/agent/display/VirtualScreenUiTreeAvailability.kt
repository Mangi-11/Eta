package io.github.mangi.eta.agent.display

/** Empty trees are evidence about one window, never a permanent display capability. */
internal class VirtualScreenUiTreeAvailability(private val elapsedRealtime: () -> Long) {
    private data class Scope(
        val sessionId: String,
        val displayId: Int,
        val packageName: String,
        val manualInputGeneration: Long,
    )

    private var scope: Scope? = null
    private var windowId: Int? = null
    private var firstEmptyAt: Long? = null
    private var lastEmptyAt: Long? = null
    private var emptyAttempts = 0
    private var unavailable = false

    @Synchronized
    fun updateScope(info: VirtualDisplayInfo) {
        val next = info.scope()
        if (scope == next) return
        scope = next
        windowId = null
        clearEvidence()
    }

    @Synchronized
    fun unavailableFor(info: VirtualDisplayInfo?): Boolean =
        info != null && scope == info.scope() && unavailable

    @Synchronized
    fun record(info: VirtualDisplayInfo, windowId: Int?, hasNodes: Boolean) {
        updateScope(info)
        if (windowId == null || info.focusedPackage.isBlank()) {
            this.windowId = null
            clearEvidence()
            return
        }
        if (this.windowId != windowId) {
            this.windowId = windowId
            clearEvidence()
        }
        if (hasNodes) {
            clearEvidence()
            return
        }
        val now = elapsedRealtime()
        val first = firstEmptyAt ?: now.also { firstEmptyAt = it }
        val previous = lastEmptyAt
        if (previous == null || now - previous >= MIN_ATTEMPT_INTERVAL_MS) {
            lastEmptyAt = now
            emptyAttempts++
        }
        unavailable = emptyAttempts >= MIN_EMPTY_ATTEMPTS && now - first >= EMPTY_GRACE_MS
    }

    private fun clearEvidence() {
        firstEmptyAt = null
        lastEmptyAt = null
        emptyAttempts = 0
        unavailable = false
    }

    private fun VirtualDisplayInfo.scope() =
        Scope(sessionId, displayId, focusedPackage, manualInputGeneration)

    private companion object {
        const val MIN_EMPTY_ATTEMPTS = 3
        const val MIN_ATTEMPT_INTERVAL_MS = 500L
        const val EMPTY_GRACE_MS = 1500L
    }
}
