package io.github.mangi.eta.agent.display

/** Serialized by the frame store; expensive encoding happens outside its lock. */
internal class VirtualScreenFrameCapturePolicy {
    data class Capture(val generation: Long, val requestId: Long)

    private var generation = 0L
    private var previewUntilMs = 0L
    private var requestUntilMs = 0L
    private var requestId = 0L
    private var completedRequestId = 0L
    private var lastStartedMs: Long? = null
    var encodedFrames = 0L
        private set

    fun setViewerVisible(visible: Boolean, nowMs: Long) {
        previewUntilMs = if (visible) nowMs + PREVIEW_LEASE_MS else 0L
        if (!visible) invalidate()
    }

    fun isViewerVisible(nowMs: Long): Boolean = nowMs < previewUntilMs

    fun requestFrame(nowMs: Long, timeoutMs: Long): Long {
        requestUntilMs = nowMs + timeoutMs
        return ++requestId
    }

    fun hasPendingRequest(nowMs: Long): Boolean =
        requestId > completedRequestId && nowMs < requestUntilMs

    fun delayUntilCapture(nowMs: Long): Long? {
        if (!isViewerVisible(nowMs) && !hasPendingRequest(nowMs)) return null
        return lastStartedMs?.let { (it + FRAME_INTERVAL_MS - nowMs).coerceAtLeast(0L) } ?: 0L
    }

    fun beginCapture(nowMs: Long): Capture? {
        if (delayUntilCapture(nowMs) != 0L) return null
        lastStartedMs = nowMs
        return Capture(generation, requestId)
    }

    fun completeCapture(capture: Capture, nowMs: Long): Boolean {
        if (capture.generation != generation ||
            !isViewerVisible(nowMs) && !hasPendingRequest(nowMs)) return false
        completedRequestId = maxOf(completedRequestId, capture.requestId)
        encodedFrames++
        return true
    }

    fun invalidate() {
        generation++
        requestUntilMs = 0L
        completedRequestId = requestId
    }

    companion object {
        const val FRAME_INTERVAL_MS = 100L
        // A dead or disconnected viewer cannot leave an encoder running indefinitely.
        const val PREVIEW_LEASE_MS = 1_500L
    }
}
