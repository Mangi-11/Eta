package io.github.mangi.eta.validation

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.MainScreenFallbackDecision
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only counts and routing metadata leave memory; no UI text, descriptions, or images are saved. */
internal class DeviceUiTreeValidation(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val report: (String) -> Unit,
) {
    private val context = instrumentation.targetContext
    private val results = JSONArray()
    private val owner = "ui-tree-validation"

    fun runRecovery() {
        check(RootAccess.isGranted)
        AgentLocalTools(context, NoOpLogger, browserRunId = owner,
            deviceDirectToolsEnabled = { true }, browserToolsEnabled = { false },
            fallbackApproval = { _, _ -> MainScreenFallbackDecision.RESTART_VIRTUAL },
            virtualScreenOwner = owner).use { tools ->
            fun verify(name: String, condition: Boolean) {
                check(condition) { name }
                report("PASS: $name")
            }
            fun observe() = call(tools, "observe_screen", JSONObject().put("include_screenshot", false))
            fun summarize(stage: String, result: JSONObject) {
                val service = AgentAccessibilityService.current()
                val snapshot = service?.captureNodeSnapshot(120, result.optInt("display_id"))
                val metadata = JSONObject().put("stage", stage).put("display_id", result.optInt("display_id"))
                    .put("ok", result.optBoolean("ok")).put("nodes", result.optJSONArray("ui_nodes")?.length() ?: 0)
                    .put("disabled", result.optJSONObject("accessibility")?.optBoolean("ui_tree_disabled") ?: false)
                    .put("pending", result.optJSONObject("accessibility")?.optBoolean("ui_tree_pending") ?: false)
                    .put("eta_service_connected", service != null).put("eta_root", snapshot != null)
                    .put("window_id", snapshot?.windowId ?: JSONObject.NULL)
                results.put(metadata)
                report("RECOVERY_SAMPLE $metadata")
            }
            fun confirmWechatEmpty(stage: String) {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var observed = observe()
                while (observed.optBoolean("ok") &&
                    !observed.getJSONObject("accessibility").optBoolean("ui_tree_disabled") &&
                    SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(750)
                    observed = observe()
                }
                summarize(stage, observed)
                verify("$stage confirms empty WeChat window", observed.optBoolean("ok") &&
                    observed.getJSONObject("focus").optString("package") == "com.tencent.mm" &&
                    observed.getJSONArray("ui_nodes").length() == 0 &&
                    observed.getJSONObject("accessibility").optBoolean("ui_tree_disabled"))
                val capabilities = tools.capabilitiesForRun(AgentToolCapabilities.capture(context))
                verify("$stage hides only node tools", capabilities.unavailableCode("tap_element") == "VIRTUAL_UI_TREE_UNAVAILABLE" &&
                    capabilities.unavailableCode("observe_screen") == null && capabilities.unavailableCode("tap") == null &&
                    capabilities.unavailableCode("input_text") == null)
                verify("$stage honors screenshot opt-out", !observed.getJSONObject("screenshot").optBoolean("attached"))
            }
            fun observeQq(stage: String): JSONObject {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var observed = observe()
                while (observed.optBoolean("ok") && observed.getJSONArray("ui_nodes").length() == 0 &&
                    SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(500)
                    observed = observe()
                }
                summarize(stage, observed)
                verify("$stage restores QQ tree", observed.optBoolean("ok") &&
                    observed.getJSONObject("focus").optString("package") == "com.tencent.mobileqq" &&
                    observed.getJSONArray("ui_nodes").length() > 0 &&
                    !observed.getJSONObject("accessibility").optBoolean("ui_tree_disabled"))
                verify("$stage restores node tool catalog", tools.capabilitiesForRun(AgentToolCapabilities.capture(context))
                    .unavailableCode("tap_element") == null)
                return observed
            }
            verify("launch WeChat", call(tools, "launch_app", JSONObject().put("package_name", "com.tencent.mm")).optBoolean("ok"))
            val display = checkNotNull(VirtualScreenSession.state.value.display)
            val first = observe()
            summarize("wechat_first", first)
            verify("first empty observation stays pending", first.optBoolean("ok") &&
                first.getJSONArray("ui_nodes").length() == 0 &&
                !first.getJSONObject("accessibility").optBoolean("ui_tree_disabled"))
            confirmWechatEmpty("wechat_confirmed")
            val screenshot = call(tools, "observe_screen", JSONObject())
            verify("known empty window keeps screenshot fallback", screenshot.optBoolean("ok") &&
                screenshot.getJSONObject("screenshot").optBoolean("attached"))
            verify("launch QQ in same tools", call(tools, "launch_app", JSONObject().put("package_name", "com.tencent.mobileqq")).optBoolean("ok"))
            verify("launch resets the restriction before observation", tools.capabilitiesForRun(AgentToolCapabilities.capture(context))
                .unavailableCode("tap_element") == null)
            val qq = observeQq("qq_after_wechat")
            val oldNode = qq.getJSONArray("ui_nodes").getJSONObject(0)
            val wechatComponent = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.component)
            verify("direct display launch back to WeChat", call(tools, "virtual_screen", JSONObject()
                .put("action", "launch").put("component", wechatComponent.flattenToString())).optBoolean("ok"))
            val stale = call(tools, "tap_element", JSONObject().put("index", oldNode.getInt("index"))
                .put("observation_id", qq.getString("observation_id")))
            verify("QQ node handles cannot act on WeChat", stale.optString("code") == "STALE_OBSERVATION")
            confirmWechatEmpty("wechat_again")
            val qqComponent = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.tencent.mobileqq")?.component)
            verify("direct display launch to QQ", call(tools, "virtual_screen", JSONObject()
                .put("action", "launch").put("component", qqComponent.flattenToString())).optBoolean("ok"))
            verify("direct launch also resets the restriction", tools.capabilitiesForRun(AgentToolCapabilities.capture(context))
                .unavailableCode("tap_element") == null)
            observeQq("qq_again")
            verify("all recovery steps retain the same virtual display", VirtualScreenSession.state.value.display?.let {
                it.sessionId == display.sessionId && it.displayId == display.displayId
            } == true)
        }
        val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
        File(directory, "ui-tree-recovery.json").writeText(JSONObject().put("results", results).toString(2))
    }

    fun run() {
        check(RootAccess.isGranted)
        val packages = listOf(
            instrumentation.context.packageName,
            "com.android.settings",
            "com.android.bbkcalculator",
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "mark.via",
            "ru.zdevs.zarchiver.pro",
            "com.coolapk.market",
            "com.moonshot.kimichat",
        )
        for (packageName in packages) {
            val result = JSONObject().put("package", packageName)
            results.put(result)
            try { inspectVirtual(packageName, result) }
            catch (error: Exception) { result.put("error_type", error.javaClass.simpleName) }
            finally { VirtualScreenSession.closeOwner(owner) }
            report("TREE_RESULT " + result)
        }
        for (packageName in listOf(instrumentation.context.packageName, "com.tencent.mm")) {
            val result = JSONObject().put("package", packageName).put("display_id", 0)
            results.put(result)
            try {
                val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
                if (component == null) result.put("code", "NO_LAUNCHER")
                else {
                    shell("am start -W --display 0 -n ${component.flattenToString()}")
                    SystemClock.sleep(if (packageName == "com.tencent.mm") 10_000 else 1500)
                    result.put("sample", sample(packageName, 0))
                }
            } catch (error: Exception) { result.put("error_type", error.javaClass.simpleName) }
            report("PRIMARY_RESULT " + result)
        }
        val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
        File(directory, "ui-tree-matrix.json").writeText(JSONObject().put("results", results).toString(2))
    }

    private fun inspectVirtual(packageName: String, result: JSONObject) {
        val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
        if (component == null) { result.put("code", "NO_LAUNCHER"); return }
        result.put("version", context.packageManager.getPackageInfo(packageName, 0).versionName)
        AgentLocalTools(context, NoOpLogger, browserRunId = owner,
            deviceDirectToolsEnabled = { true }, browserToolsEnabled = { false },
            fallbackApproval = { _, _ -> MainScreenFallbackDecision.RESTART_VIRTUAL },
            virtualScreenOwner = owner).use { tools ->
            val created = call(tools, "virtual_screen", JSONObject().put("action", "create"))
            if (!created.optBoolean("ok")) { result.put("code", created.optString("code")); return }
            val displayId = checkNotNull(VirtualScreenSession.state.value.display).displayId
            result.put("display_id", displayId)
            val launched = call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component.flattenToString()))
            result.put("launch_ok", launched.optBoolean("ok")).put("launch_code", launched.optString("code"))
            if (!launched.optBoolean("ok")) return
            val samples = JSONArray()
            result.put("samples", samples)
            val start = SystemClock.elapsedRealtime()
            val delays = if (packageName == "com.tencent.mm") listOf(0L, 500L, 1500L, 4000L, 10_000L)
                else listOf(0L, 500L, 1500L, 4000L)
            for (delayMs in delays) {
                val remaining = start + delayMs - SystemClock.elapsedRealtime()
                if (remaining > 0) SystemClock.sleep(remaining)
                val sample = sample(packageName, displayId)
                sample.put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                val probe = JSONObject(VirtualScreenSession.execute(context, owner, JSONObject().put("action", "probe")).content)
                sample.put("focus_matches", probe.optString("packageName") == packageName)
                check(sample.optBoolean("focus_matches")) { "TARGET_NOT_ON_DISPLAY" }
                samples.put(sample)
                report("TREE_SAMPLE " + JSONObject().put("package", packageName).put("display_id", displayId).put("sample", sample))
                if (delayMs == delays.first() || delayMs == delays.last()) {
                    val observed = call(tools, "observe_screen", JSONObject().put("include_screenshot", false))
                    sample.put("tool_ok", observed.optBoolean("ok"))
                        .put("tool_nodes", observed.optJSONArray("ui_nodes")?.length() ?: 0)
                        .put("tool_disabled", observed.optJSONObject("accessibility")?.optBoolean("ui_tree_disabled") ?: false)
                    if (delayMs == delays.last() && sample.optInt("eta_nodes") > 0) {
                        check(sample.optInt("tool_nodes") > 0 && !sample.optBoolean("tool_disabled")) { "TREE_RECOVERED_BUT_TOOL_DISABLED" }
                    }
                }
            }
        }
    }

    private fun sample(packageName: String, displayId: Int): JSONObject {
        val result = JSONObject()
        val service = AgentAccessibilityService.current()
        var windows = emptyList<AccessibilityWindowInfo>()
        instrumentation.runOnMainSync {
            windows = service?.windowsOnAllDisplays?.get(displayId).orEmpty()
                .filter { it.displayId == displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        }
        result.put("eta_service_connected", service != null).put("eta_windows", windows.size)
        val snapshot = service?.captureNodeSnapshot(120, displayId)
        result.put("eta_root", snapshot != null).put("eta_nodes", snapshot?.nodes?.size ?: 0)
            .put("eta_package_matches", snapshot?.packageName == packageName)
        val automationWindows = automation.windowsOnAllDisplays.get(displayId).orEmpty()
            .filter { it.displayId == displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        result.put("automation_windows", automationWindows.size)
        val counts = JSONArray()
        for (window in automationWindows) {
            val root = window.root
            val count = JSONObject().put("root", root != null)
                .put("package_matches", root?.packageName?.toString() == packageName)
                .put("focused", window.isFocused)
            if (root != null) {
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                var useful = 0
                var labeled = 0
                val deadline = SystemClock.elapsedRealtime() + 2500
                while (queue.isNotEmpty() && visited < 240 && SystemClock.elapsedRealtime() < deadline) {
                    val node = queue.removeFirst()
                    visited++
                    if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) labeled++
                    if (node.isClickable || node.isScrollable || node.isEditable) useful++
                    for (index in 0 until node.childCount.coerceAtMost(240)) node.getChild(index)?.let(queue::add)
                }
                count.put("nodes", visited).put("labeled_nodes", labeled).put("action_nodes", useful)
                    .put("truncated", queue.isNotEmpty())
            }
            counts.put(count)
        }
        return result.put("automation_roots", counts)
    }

    private fun call(tools: AgentLocalTools, name: String, args: JSONObject): JSONObject =
        JSONObject(tools.execute(AgentModelClient.ToolCall("tree-validation", name, args.toString())).content)

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
