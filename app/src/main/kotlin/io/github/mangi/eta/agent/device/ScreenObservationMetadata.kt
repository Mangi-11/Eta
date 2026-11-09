package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshotStatus
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.ObservedWindow
import java.util.Locale
import org.json.JSONObject

internal object ScreenObservationMetadata {
    fun mayUseUiAutomator(
        requested: Boolean,
        accessibilityConnected: Boolean,
        rootAvailable: Boolean,
        virtualDisplay: Boolean,
    ): Boolean = requested && !accessibilityConnected && rootAvailable && !virtualDisplay

    fun uiTreeStatus(
        requested: Boolean,
        accessibilityStatus: NodeSnapshotStatus?,
        dumpOutcome: ShellActionOutcomePolicy.Outcome?,
        nodeCount: Int,
    ): String = when {
        !requested -> "not_requested"
        accessibilityStatus != null -> accessibilityStatus.name.lowercase(Locale.ROOT)
        dumpOutcome == ShellActionOutcomePolicy.Outcome.TIMED_OUT -> "timeout"
        dumpOutcome == ShellActionOutcomePolicy.Outcome.FAILED -> "query_failed"
        dumpOutcome == ShellActionOutcomePolicy.Outcome.SUCCEEDED -> if (nodeCount > 0) "available" else "empty"
        else -> "service_unavailable"
    }

    fun focus(window: ObservedWindow?, uiAutomatorPackageName: String?): JSONObject {
        val packageName = if (window != null) window.packageName else uiAutomatorPackageName
        val observedPackageName = packageName?.takeIf(String::isNotBlank)
        return JSONObject()
            .put("package", observedPackageName.orEmpty())
            .put("component", "")
            .put(
                "source",
                when {
                    window != null -> "accessibility"
                    observedPackageName != null -> "uiautomator"
                    else -> "unavailable"
                },
            )
            .put("window_id", window?.windowId ?: JSONObject.NULL)
            .put("display_id", window?.displayId ?: JSONObject.NULL)
            .put("focused", window?.focused ?: JSONObject.NULL)
            .put("active", window?.active ?: JSONObject.NULL)
    }

    fun uiTreeNote(status: String, screenshotAttached: Boolean): String {
        val description = when (status) {
            "not_requested" -> "本次未请求节点树"
            "available" -> "节点树可用"
            "empty" -> "已读取窗口，但没有可用节点；不能据此判断应用不支持当前显示屏"
            "no_window" -> "目标显示屏未返回可观察窗口"
            "root_unavailable" -> "已找到目标窗口，但暂时无法读取根节点"
            "timeout" -> "节点查询超时"
            "busy" -> "上一次节点查询尚未结束"
            "query_failed" -> "节点查询失败"
            else -> "无障碍服务不可用，当前没有节点读取通道"
        }
        if (status == "not_requested" || status == "available") return description
        return description + if (screenshotAttached) {
            "；可结合本次截图观察界面"
        } else {
            "；可请求截图确认当前界面"
        }
    }
}
