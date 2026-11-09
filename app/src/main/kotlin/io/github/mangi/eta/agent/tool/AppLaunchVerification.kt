package io.github.mangi.eta.agent.tool

import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshotObservation
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshotStatus
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.ObservedWindow
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import java.util.Locale
import org.json.JSONObject

/** 启动请求被系统接收不代表目标窗口已经出现；确认只读取目标显示屏，不要求抢占输入焦点。 */
internal object AppLaunchVerification {
    private const val TIMEOUT_MILLIS = 1_500L
    private const val RETRY_INTERVAL_MILLIS = 120L

    data class Result(
        val confirmed: Boolean,
        val status: NodeSnapshotStatus,
        val window: ObservedWindow?,
    ) {
        val launchStatus: String get() = if (confirmed) "window_observed" else "unconfirmed"

        fun toJson(): JSONObject = JSONObject()
            .put("status", status.name.lowercase(Locale.ROOT))
            .put("package", window?.packageName ?: JSONObject.NULL)
            .put("window_id", window?.windowId ?: JSONObject.NULL)
            .put("display_id", window?.displayId ?: JSONObject.NULL)
            .put("focused", window?.focused ?: JSONObject.NULL)
    }

    fun awaitWindow(
        packageName: String,
        displayId: Int,
        query: (remainingMillis: Long) -> NodeSnapshotObservation,
        checkCancellation: () -> Unit,
        elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Result {
        val deadline = elapsedRealtime() + TIMEOUT_MILLIS
        var last = Result(false, NodeSnapshotStatus.NO_WINDOW, null)
        while (true) {
            checkCancellation()
            val remaining = deadline - elapsedRealtime()
            if (remaining <= 0L) return last
            val observation = query(remaining)
            checkCancellation()
            val window = observation.window
            val confirmed = window?.displayId == displayId && window.packageName == packageName &&
                observation.status == NodeSnapshotStatus.AVAILABLE
            last = Result(confirmed, observation.status, window)
            if (confirmed || observation.status !in RETRYABLE_STATUSES) return last
            val pause = (deadline - elapsedRealtime()).coerceAtMost(RETRY_INTERVAL_MILLIS)
            if (pause <= 0L) return last
            try {
                sleep(pause)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AgentRunCancelledException()
            }
        }
    }

    private val RETRYABLE_STATUSES = setOf(
        NodeSnapshotStatus.AVAILABLE,
        NodeSnapshotStatus.NO_WINDOW,
        NodeSnapshotStatus.ROOT_UNAVAILABLE,
    )
}
