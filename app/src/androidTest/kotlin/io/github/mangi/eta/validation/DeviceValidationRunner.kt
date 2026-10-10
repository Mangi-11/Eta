package io.github.mangi.eta.validation

import android.app.Activity
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import android.view.Display
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.MainScreenFallbackDecision
import io.github.mangi.eta.agent.display.MainScreenFallbackApproval
import io.github.mangi.eta.agent.display.VirtualScreenAppConflictDialog
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.screens.tasks.VirtualScreenSettingsScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** On-device tests use real tools and a separate synthetic app, never a paid model. */
class DeviceValidationRunner : Instrumentation() {
    private val checks = mutableListOf<String>()
    private var mode = "core"
    private var screenOffValidation = false
    private var restoreTimeout: Int? = null
    private lateinit var automation: UiAutomation

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "core"
        screenOffValidation = arguments?.getString("screen_off") == "true"
        restoreTimeout = arguments?.getString("idle_timeout")?.toIntOrNull()
        start()
    }

    override fun onStart() {
        waitForIdleSync()
        SettingsDataStore.init(targetContext)
        val settingsBackup = File(targetContext.filesDir, "device-validation-settings.json")
        if (settingsBackup.isFile) {
            val backup = JSONObject(settingsBackup.readText())
            runBlocking { SettingsDataStore.updateSettings { it.copy(
                virtualScreenEnabled = backup.getBoolean("enabled"),
                virtualScreenFallbackEnabled = backup.getBoolean("fallback"),
                virtualScreenAutoRestartApps = backup.getBoolean("auto_restart"),
                virtualScreenOffEnabled = backup.optBoolean("screen_off", it.virtualScreenOffEnabled),
                virtualScreenIdleTimeoutMinutes = backup.getInt("idle_timeout"),
                virtualScreenFloatingPreviewEnabled = backup.optBoolean("floating", it.virtualScreenFloatingPreviewEnabled),
                defaultAssistantSystemPrompt = backup.optString("default_prompt", it.defaultAssistantSystemPrompt),
                memoryEnabled = backup.optBoolean("memory", it.memoryEnabled),
                autoMemoryEnabled = backup.optBoolean("auto_memory", it.autoMemoryEnabled),
                autoSkillsEnabled = backup.optBoolean("auto_skills", it.autoSkillsEnabled),
            ) } }
            settingsBackup.delete()
        }
        if (mode == "restore") {
            val minutes = restoreTimeout
            if (minutes == null || minutes !in listOf(10, 20, 60, 0)) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "INVALID_IDLE_TIMEOUT\n") })
            } else {
                runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenIdleTimeoutMinutes = minutes) } }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Restored idle timeout: $minutes\n") })
            }
            return
        }
        val original = runBlocking { SettingsDataStore.settings() }
        settingsBackup.writeText(JSONObject().put("enabled", original.virtualScreenEnabled)
            .put("fallback", original.virtualScreenFallbackEnabled)
            .put("auto_restart", original.virtualScreenAutoRestartApps)
            .put("screen_off", original.virtualScreenOffEnabled)
            .put("floating", original.virtualScreenFloatingPreviewEnabled)
            .put("default_prompt", original.defaultAssistantSystemPrompt)
            .put("memory", original.memoryEnabled)
            .put("auto_memory", original.autoMemoryEnabled)
            .put("auto_skills", original.autoSkillsEnabled)
            .put("idle_timeout", original.virtualScreenIdleTimeoutMinutes).toString())
        val output = Bundle()
        var resultCode = Activity.RESULT_OK
        try {
            runBlocking { RootAccess.request(targetContext).join() }
            verify("root permission", RootAccess.isGranted)
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            automation.adoptShellPermissionIdentity("android.permission.QUERY_ALL_PACKAGES")
            if (mode !in setOf("recovery", "landscape", "frames", "frame_recovery", "stop_resume", "stop_resume_baseline",
                "floating", "feature_ui", "interjection")) connectEnabledAccessibilityService()
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenEnabled = true,
                virtualScreenFallbackEnabled = false, virtualScreenAutoRestartApps = false,
                virtualScreenOffEnabled = mode in setOf("recovery", "landscape", "frames", "frame_recovery") || original.virtualScreenOffEnabled,
                virtualScreenIdleTimeoutMinutes = 0) } }
            when (mode) {
                "wechat" -> validateWechat()
                "stall" -> validateStallProtection()
                "recovery", "frame_recovery" -> validateUiRecovery()
                "landscape" -> validateLandscape()
                "frames" -> validateFrameCapture()
                "stop_resume", "stop_resume_baseline" -> validateStopResume(mode == "stop_resume")
                "gui_audit_baseline", "gui_audit" -> validateGuiAudit(mode == "gui_audit")
                "gui_edge" -> validateGuiAfterAction()
                "gui_no_tree" -> validateGuiWithoutTree()
                "floating" -> validateFloatingPreview()
                "feature_ui" -> DeviceFeatureUiValidation(this, automation) { record -> checks += record }.run()
                "interjection" -> DeviceInterjectionValidation(targetContext) { record -> checks += record }.run()
                "tree" -> DeviceUiTreeValidation(this, automation) { record ->
                    checks += record
                    sendStatus(0, Bundle().apply { putString("stream", record + "\n") })
                }.run()
                "tree_recovery" -> DeviceUiTreeValidation(this, automation) { record ->
                    checks += record
                    sendStatus(0, Bundle().apply { putString("stream", record + "\n") })
                }.runRecovery()
                else -> validateCore()
            }
            output.putString("stream", "\nPASS ${checks.size} checks\n" + checks.joinToString("\n"))
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            output.putString("stream", "\nFAIL after ${checks.size} checks: ${error.javaClass.simpleName}: ${error.message}\n" +
                error.stackTrace.take(8).joinToString("\n") + "\n" + checks.joinToString("\n"))
            output.putString("operations", VirtualScreenSession.state.value.operations.takeLast(5)
                .joinToString { "${it.name}:ok=${it.success},manual=${it.manual}" })
            if (mode in setOf("recovery", "landscape", "frames", "frame_recovery")) {
                output.putString("display_states", targetContext.getSystemService(DisplayManager::class.java)
                    .displays.joinToString { "${it.displayId}:${it.state}" })
                output.putBoolean("keyguard_locked", targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            }
        } finally {
            VirtualScreenSession.revokePermission()
            runBlocking { SettingsDataStore.updateSettings { original } }
            settingsBackup.delete()
            if (::automation.isInitialized) automation.dropShellPermissionIdentity()
        }
        finish(resultCode, output)
    }

    private fun tools(run: String = "device-validation", approval: MainScreenFallbackDecision = MainScreenFallbackDecision.RESTART_VIRTUAL) =
        AgentLocalTools(targetContext, NoOpLogger, browserRunId = run,
            deviceDirectToolsEnabled = { true }, browserToolsEnabled = { true },
            fallbackApproval = { _, _ -> approval }, virtualScreenOwner = "device-validation")

    private fun validateStopResume(expectRetained: Boolean) {
        val controller = AgentRunController()
        val first = AgentLocalTools(targetContext, NoOpLogger, browserRunId = "retention-first",
            virtualScreenOwner = "device-validation", deviceDirectToolsEnabled = { true },
            isRunCancelled = { controller.isCancelled },
            fallbackApproval = { _, _ -> MainScreenFallbackDecision.RESTART_VIRTUAL })
        controller.register(first::close)
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        ok("create cancellation fixture", call(first, "virtual_screen", JSONObject().put("action", "create")))
        ok("launch cancellation fixture", call(first, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
        ok("observe before cancellation", call(first, "observe_screen", JSONObject().put("include_screenshot", true)))
        val sessionId = VirtualScreenSession.state.value.display!!.sessionId
        val startedAt = SystemClock.elapsedRealtime()
        controller.cancel()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        verify("stop retains display=$expectRetained", VirtualScreenSession.isActive() == expectRetained)
        if (!expectRetained) {
            sendStatus(0, Bundle().apply { putString("stream", "BASELINE: stop destroyed virtual display; cancellation_ms=$elapsed\n") })
            return
        }
        verify("cancellation returns within 500 ms", elapsed < 500)
        verify("stop keeps same session", VirtualScreenSession.state.value.display?.sessionId == sessionId)
        tools("retention-resume").use { resumed ->
            val stale = call(resumed, "tap", JSONObject().put("x", 1).put("y", 1).put("coordinate_space", "screen"))
            verify("resumed run requires fresh observation", !stale.optBoolean("ok") && stale.optString("code") == "NO_OBSERVATION")
            val observed = ok("resume observes retained app", call(resumed, "observe_screen", JSONObject().put("include_screenshot", true)))
            verify("resume preserves display and application", VirtualScreenSession.state.value.display?.sessionId == sessionId &&
                observed.getJSONObject("focus").optString("package") == context.packageName)
            ok("resume controls same virtual display", call(resumed, "tap", JSONObject().put("x", 1).put("y", 1).put("coordinate_space", "screen")))
            resumed.retainVirtualScreenOnSuccess()
        }
        tools("retention-failure").use { failed ->
            ok("failure run observes preserved app", call(failed, "observe_screen"))
            // Model/transport failure closes tools without retainVirtualScreenOnSuccess().
        }
        verify("failed run retains same session", VirtualScreenSession.state.value.display?.sessionId == sessionId)
        verify("failed run is shown as failed", VirtualScreenSession.state.value.taskPhase.name == "FAILED")
    }

    private fun validateGuiAudit(optimized: Boolean) {
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        tools("gui-audit").use { tools ->
            ok("create audit display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("launch audit fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
            eventually("audit fixture ready", { probe().optInt("buttonY") > 0 &&
                probe().optInt("display") == VirtualScreenSession.state.value.display?.displayId })
            ok("initial audit observation", call(tools, "observe_screen"))
            var toolCalls = 0
            var images = 0
            val durations = mutableListOf<Long>()
            repeat(6) { index ->
                val position = probe()
                val start = SystemClock.elapsedRealtime()
                val tap = tools.execute(AgentModelClient.ToolCall("audit-$index", "tap", JSONObject()
                    .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))
                    .put("coordinate_space", "screen").toString()))
                toolCalls++
                images += tap.images.size
                val result = ok("audit tap ${index + 1}", JSONObject(tap.content))
                if (optimized) {
                    val after = result.getJSONObject("after")
                    verify("audit action supplies fresh scoped observation ${index + 1}",
                        after.optInt("display_id") == VirtualScreenSession.state.value.display?.displayId &&
                            (after.getJSONArray("ui_nodes").length() > 0 || tap.images.isNotEmpty()))
                    if (after.getJSONArray("ui_nodes").length() > 0) verify("audit observation contains confirmed effect ${index + 1}",
                        (0 until after.getJSONArray("ui_nodes").length()).any { node ->
                            after.getJSONArray("ui_nodes").getJSONObject(node).optString("text")
                                .equals("Counter: ${index + 1}", ignoreCase = true)
                        })
                } else {
                    verify("baseline has no automatic virtual observation", !result.has("after"))
                    ok("baseline wait ${index + 1}", call(tools, "wait", JSONObject().put("duration_ms", 200)))
                    toolCalls++
                    val observed = tools.execute(AgentModelClient.ToolCall("audit-observe-$index", "observe_screen", "{}"))
                    toolCalls++
                    images += observed.images.size
                    ok("baseline observation ${index + 1}", JSONObject(observed.content))
                }
                durations += SystemClock.elapsedRealtime() - start
                eventually("audit effect confirmed ${index + 1}", { probe().optInt("taps") == index + 1 })
            }
            val metrics = JSONObject().put("mode", mode).put("steps", 6).put("tool_calls", toolCalls)
                .put("attached_images", images).put("tool_elapsed_ms", durations.sum())
                .put("step_elapsed_ms", org.json.JSONArray(durations))
                .put("confirmed_actions", probe().optInt("taps"))
            val directory = File(targetContext.cacheDir, "validation").apply { mkdirs() }
            File(directory, "$mode.json").writeText(metrics.toString(2))
            sendStatus(0, Bundle().apply { putString("stream", "GUI_AUDIT: $metrics\n") })
            tools.retainVirtualScreenOnSuccess()
        }
    }

    private fun validateGuiAfterAction() {
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        tools("gui-edge").use { tools ->
            ok("create edge display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("launch edge fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch")
                .put("component", component).put("uri", "eta-validation://audit")))
            eventually("edge fixture ready", { probe().optInt("toggleY") > 0 &&
                probe().optInt("display") == VirtualScreenSession.state.value.display?.displayId })
            val observation = ok("edge observation", call(tools, "observe_screen"))
            var after = observation
            repeat(2) { index ->
                val position = probe()
                val tap = ok("toggle action ${index + 1}", call(tools, "tap", JSONObject()
                    .put("x", position.getInt("toggleX")).put("y", position.getInt("toggleY"))
                    .put("coordinate_space", "screen")))
                after = tap.getJSONObject("after")
                eventually("toggle effect ${index + 1}", { probe().optBoolean("toggle") == (index == 0) })
                if (after.getJSONArray("ui_nodes").length() > 0) {
                    val nodes = after.getJSONArray("ui_nodes")
                    val toggle = (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == "Audit toggle" }
                    verify("checked-only change is observed ${index + 1} (changed=${after.opt("screen_changed")},checked=${toggle.opt("checked")})",
                        after.optBoolean("screen_changed") && toggle.getBoolean("checked") == (index == 0))
                }
            }
            val position = probe()
            val missed = ok("missed tap is delivered", call(tools, "tap", JSONObject()
                .put("x", position.getInt("titleX")).put("y", position.getInt("titleY"))
                .put("coordinate_space", "screen")))
            after = missed.getJSONObject("after")
            if (after.getJSONArray("ui_nodes").length() > 0) verify("missed tap is not reported as progress", !after.getBoolean("screen_changed"))
            val focused = ok("focus editor with after observation", call(tools, "tap", JSONObject()
                .put("x", position.getInt("editorX")).put("y", position.getInt("editorY"))
                .put("coordinate_space", "screen")))
            after = focused.getJSONObject("after")
            val text = "Eta 中文 😀"
            val input = ok("unified Unicode replacement", call(tools, "type_text", JSONObject().put("text", text)))
            verify("text action carries observation", input.has("after"))
            eventually("Unicode replacement is exact", { probe().optString("text") == text })
            ok("unified append", call(tools, "type_text", JSONObject().put("text", " test").put("mode", "append")))
            eventually("append preserves existing text", { probe().optString("text") == "$text test" })
            tools.retainVirtualScreenOnSuccess()
        }
    }

    private fun validateGuiWithoutTree() {
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        tools("gui-no-tree").use { tools ->
            ok("create no-tree display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("launch no-tree fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch")
                .put("component", component).put("uri", "eta-validation://empty-tree")))
            eventually("no-tree fixture ready", { probe().optInt("buttonY") > 0 &&
                probe().optInt("display") == VirtualScreenSession.state.value.display?.displayId })
            val observed = ok("no-tree observation", call(tools, "observe_screen"))
            verify("fixture exposes no usable nodes", observed.getJSONArray("ui_nodes").length() == 0)
            val position = probe()
            val result = tools.execute(AgentModelClient.ToolCall("no-tree-tap", "tap", JSONObject()
                .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))
                .put("coordinate_space", "screen").toString()))
            val after = ok("no-tree tap", JSONObject(result.content)).getJSONObject("after")
            verify("automatic fallback contains one image", result.images.size == 1 &&
                after.getJSONObject("screenshot").optBoolean("attached"))
            verify("empty tree does not claim unchanged screen", after.isNull("screen_changed"))
            verify("fallback remains on owned display", after.optInt("display_id") == VirtualScreenSession.state.value.display?.displayId)
            eventually("no-tree tap has one effect", { probe().optInt("taps") == 1 })
            tools.retainVirtualScreenOnSuccess()
        }
    }

    private fun call(tools: AgentLocalTools, name: String, args: JSONObject = JSONObject()): JSONObject {
        if (name in setOf("tap", "tap_area", "long_press", "swipe") && !args.has("coordinate_space")) {
            args.put("coordinate_space", "screen")
        }
        return JSONObject(tools.execute(AgentModelClient.ToolCall("validation", name, args.toString())).content)
    }

    private fun ok(name: String, result: JSONObject): JSONObject {
        verify(name + " (" + result.optString("code") + ":" + result.optString("error_type") + ":" + result.optString("error_stage") +
            ":" + result.optString("helper_uid") + ":" + result.optString("clipboard_signature") + ")", result.optBoolean("ok"))
        return result
    }

    private fun verify(name: String, condition: Boolean) {
        check(condition) { name }
        checks += name
        sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
    }

    private fun probe(): JSONObject {
        val result = shell("content query --uri content://io.github.mangi.eta.test.display_probe/state").trim()
        val prefix = "Row: 0 state="
        return if (result.startsWith(prefix)) JSONObject(result.removePrefix(prefix)) else JSONObject()
    }

    private fun eventually(name: String, test: () -> Boolean, timeoutMs: Long = 5000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        verify(name, test())
    }

    private fun connectEnabledAccessibilityService() {
        if (AgentAccessibilityService.current() != null) return
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabled = android.provider.Settings.Secure.getString(targetContext.contentResolver, key).orEmpty()
        val component = ComponentName(targetContext, AgentAccessibilityService::class.java)
        val services = enabled.split(':').filter { it.isNotBlank() }
        if (services.none { ComponentName.unflattenFromString(it) == component }) return
        val withoutEta = services.filter { ComponentName.unflattenFromString(it) != component }.joinToString(":")
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        // Instrumentation restarts the target process; some ROMs leave its enabled service unbound.
        try {
            shell("settings put secure $key ${quote(withoutEta)}")
            SystemClock.sleep(200)
        } finally {
            shell("settings put secure $key ${quote(enabled)}")
        }
        eventually("enabled Eta accessibility service reconnects", { AgentAccessibilityService.current() != null }, 10_000)
    }

    private fun validateCore() {
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        shell("am start -n $component")
        eventually("fixture starts on primary", { probe().optInt("display") == 0 })
        tools().use { tools ->
            ok("create virtual display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            val display = VirtualScreenSession.state.value.display!!
            verify("display is secondary", display.displayId > 0)
            val denied = VirtualScreenSession.execute(targetContext, "device-validation", JSONObject()
                .put("action", "launch").put("component", component).put("restartApp", true))
            verify("unapproved stop rejected", JSONObject(denied.content).optString("code") == "APP_RESTART_PERMISSION_REQUIRED")
            ok("conflict restart stays virtual", call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
            eventually("fixture restarted on owned display", { probe().optInt("display") == display.displayId })
            SystemClock.sleep(500)
            val observed = ok("observe virtual screen", call(tools, "observe_screen", JSONObject().put("include_screenshot", true)))
            verify("screenshot attached", observed.getJSONObject("screenshot").optBoolean("attached"))
            verify("observation keeps display", observed.optInt("display_id") == display.displayId)
            checks += "tree nodes: ${observed.getJSONArray("ui_nodes").length()}, disabled: ${observed.getJSONObject("accessibility").optBoolean("ui_tree_disabled") }"
            if (observed.getJSONObject("accessibility").optBoolean("ui_tree_disabled")) {
                verify("missing tree removes node tools", tools.capabilitiesForRun(AgentToolCapabilities.capture(targetContext))
                    .unavailableCode("tap_element") == "VIRTUAL_UI_TREE_UNAVAILABLE")
            }
            val position = probe()
            ok("AI coordinate tap", call(tools, "tap", JSONObject().put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
            eventually("AI tap changes counter", { probe().optInt("taps") == 1 })
            ok("AI long press", call(tools, "long_press", JSONObject().put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
            eventually("long press reaches fixture", { probe().optInt("longPresses") == 1 })
            ok("focus text editor", call(tools, "tap", JSONObject().put("x", position.getInt("editorX")).put("y", position.getInt("editorY"))))
            ok("Unicode tool input", call(tools, "input_text", JSONObject().put("text", "Eta 中文測試\nemoji 😀")))
            eventually("Unicode text reaches editor", { probe().optString("text") == "Eta 中文測試\nemoji 😀" })
            ok("replace focused text", call(tools, "replace_text", JSONObject().put("text", "替换文本")))
            eventually("replacement has no old text", { probe().optString("text") == "替换文本" })
            ok("clear focused text", call(tools, "clear_text"))
            eventually("clear empties editor", { probe().optString("text").isEmpty() })
            val focused = ok("fixture keeps focus before viewer", call(tools, "observe_screen"))
            verify("fixture is still foreground on virtual display", focused.getJSONObject("focus").optString("package") == context.packageName)
            if (focused.getJSONObject("accessibility").optBoolean("ui_tree_disabled")) {
                verify("default observation attaches image without tree", focused.getJSONObject("screenshot").optBoolean("attached"))
            }
            validateViewer()
            ok("fresh observation after host text", call(tools, "observe_screen"))
            ok("AI swipe", call(tools, "swipe", JSONObject().put("x1", display.width / 2).put("y1", display.height * 3 / 4)
                .put("x2", display.width / 2).put("y2", display.height / 4)))
            eventually("swipe moves fixture content", { probe().optInt("scrollY") > 0 })
            val manual = VirtualScreenSession.inputForViewer(targetContext, display.sessionId, JSONObject().put("action", "key").put("button", "HOME"))
            ok("manual home is isolated", JSONObject(manual.content))
            verify("old AI coordinates rejected after manual input", call(tools, "tap", JSONObject().put("x", 100).put("y", 100)).optString("code") == "STALE_OBSERVATION")
            ok("fresh observation after manual input", call(tools, "observe_screen"))
            verify("virtual global system panel rejected", call(tools, "open_system_panel", JSONObject().put("panel", "notifications")).optString("code") == "VIRTUAL_ACTION_UNSUPPORTED")
            tools.retainVirtualScreenOnSuccess()
        }
        verify("successful run retains display", VirtualScreenSession.isActive())
        tools("next-validation-turn").use { tools ->
            ok("follow-up observes retained display", call(tools, "observe_screen"))
            validateSearch(tools)
            ok("explicit close releases display", call(tools, "virtual_screen", JSONObject().put("action", "close")))
        }
        verify("display closed", !VirtualScreenSession.isActive())
        runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenAutoRestartApps = true) } }
        shell("am start -n $component")
        eventually("auto-restart fixture starts on primary", { probe().optInt("display") == 0 })
        tools("auto-restart-validation", MainScreenFallbackDecision.UNAVAILABLE).use { tools ->
            ok("create display for automatic restart", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("enabled auto restart bypasses approval", call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
            val displayId = VirtualScreenSession.state.value.display!!.displayId
            eventually("automatic restart places app on virtual display", { probe().optInt("display") == displayId })
            ok("automatic restart display closes", call(tools, "virtual_screen", JSONObject().put("action", "close")))
        }
    }

    private fun validateStallProtection() {
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        shell("am start -n $component")
        tools("stall-first").use { tools ->
            ok("create stall display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("launch stall fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
            eventually("stall fixture is virtual", { probe().optInt("display") == VirtualScreenSession.state.value.display?.displayId })
            val observed = ok("stall observation", call(tools, "observe_screen", JSONObject().put("include_screenshot", true)))
            verify("frame id is exposed", observed.getJSONObject("screenshot").optLong("frame_id") > 0)
            verify("frame age is exposed", observed.getJSONObject("screenshot").optLong("frame_age_ms", -1) >= 0)
            val health = VirtualScreenSession.execute(targetContext, "device-validation", JSONObject()
                .put("action", "probe").put("includeInputHealth", true))
            verify("virtual input connection is responsive", JSONObject(health.content).getJSONObject("input_health").optString("status") == "responsive")
            repeat(5) { index ->
                val position = probe()
                val tap = ok("progressing tap ${index + 1}", call(tools, "tap", JSONObject()
                    .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
                verify("framework confirms input completion", tap.optBoolean("input_finished"))
                eventually("counter progress ${index + 1}", { probe().optInt("taps") == index + 1 })
            }
            repeat(3) { index ->
                val position = probe()
                ok("missed tap retry ${index + 1}", call(tools, "tap", JSONObject()
                    .put("x", position.getInt("titleX") + index * 3).put("y", position.getInt("titleY"))))
                ok("read does not reset budget ${index + 1}", call(tools, "observe_screen"))
            }
            val position = probe()
            val paused = tools.execute(AgentModelClient.ToolCall("pause", "tap", JSONObject()
                .put("x", position.getInt("titleX")).put("y", position.getInt("titleY")).put("coordinate_space", "screen").toString()))
            verify("fourth missed tap pauses", paused.stop?.code == "UI_NO_PROGRESS")
            verify("missed tap is not business progress", probe().optInt("taps") == 5)
        }
        verify("paused display remains owned", VirtualScreenSession.isOwnedBy("device-validation"))
        verify("paused indicator is distinct", VirtualScreenSession.state.value.taskPhase ==
            io.github.mangi.eta.agent.display.VirtualScreenTaskPhase.PAUSED)
        verify("paused control border stops", !VirtualScreenSession.state.value.isAgentControlling)
        val viewer = startActivitySync(Intent(targetContext, VirtualScreenViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        try {
            runOnMainSync { viewer.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            eventually("paused label is visible", { findPrimary { it.text?.toString() == targetContext.getString(R.string.virtual_screen_paused) } != null })
            screenshot("viewer-paused.png")
        } finally {
            runOnMainSync { viewer.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); viewer.finish() }
        }
        tools("stall-resumed").use { tools ->
            ok("new run can inspect retained screen", call(tools, "observe_screen"))
            val position = probe()
            ok("new run can continue", call(tools, "tap", JSONObject().put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
            eventually("resumed counter increments", { probe().optInt("taps") == 6 })
            tools.retainVirtualScreenOnSuccess()
        }
        val display = checkNotNull(VirtualScreenSession.state.value.display)
        val launchOutput = shell("am start --display ${display.displayId} -f 0x18000000 -n $component --ez stall_validation true")
        verify("freeze fixture launch accepted", !launchOutput.contains("Error:") && !launchOutput.contains("Warning: Activity not started"))
        eventually("freeze fixture enabled", { probe().optInt("freezeY") > 0 })
        tools("stall-unresponsive").use { tools ->
            ok("observe freeze fixture", call(tools, "observe_screen"))
            val position = probe()
            val freeze = tools.execute(AgentModelClient.ToolCall("freeze", "tap", JSONObject()
                .put("x", position.getInt("freezeX")).put("y", position.getInt("freezeY")).put("coordinate_space", "screen").toString()))
            val result = if (freeze.stop != null) freeze else {
                ok("freeze input dispatch completes", JSONObject(freeze.content))
                eventually("fixture main thread is blocked", { probe().optBoolean("blocked") })
                tools.execute(AgentModelClient.ToolCall("blocked-input", "tap", JSONObject()
                    .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY")).put("coordinate_space", "screen").toString()))
            }
            verify("blocked UI pauses from system evidence or input timeout (${result.stop?.code ?: JSONObject(result.content).optString("code")})", result.stop?.code in
                setOf("UI_APP_UNRESPONSIVE", "UI_INPUT_TIMEOUT"))
        }
        verify("blocked UI retains display", VirtualScreenSession.isOwnedBy("device-validation"))
        shell("am force-stop io.github.mangi.eta.test")
    }

    private fun validateUiRecovery() {
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        if (mode == "frame_recovery") shell("am force-stop io.github.mangi.eta.test")
        else shell("am start -n $component")
        tools("recovery-run").use { tools ->
            ok("create recovery display", call(tools, "virtual_screen", JSONObject().put("action", "create").put("allowScreenOff", true)))
            ok("launch recovery fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
            val info = checkNotNull(VirtualScreenSession.state.value.display)
            eventually("fixture is on recovery display", { probe().optInt("display") == info.displayId })
            ok("observe recovery fixture", call(tools, "observe_screen"))
            val position = probe()
            ok("seed counter before restart", call(tools, "tap", JSONObject()
                .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
            eventually("counter has state before restart", { probe().optInt("taps") == 1 })
            repeat(3) { index ->
                ok("missed recovery tap ${index + 1}", call(tools, "tap", JSONObject()
                    .put("x", position.getInt("titleX")).put("y", position.getInt("titleY"))))
                ok("observe missed recovery tap ${index + 1}", call(tools, "observe_screen"))
            }
            val paused = tools.execute(AgentModelClient.ToolCall("pause", "tap", JSONObject()
                .put("x", position.getInt("titleX")).put("y", position.getInt("titleY")).put("coordinate_space", "screen").toString()))
            verify("recovery fixture pauses after missed taps", paused.stop?.code == "UI_NO_PROGRESS")
            if (screenOffValidation) lockPrimaryForRecovery("missed taps")
            val previousFrameId = JSONObject(VirtualScreenSession.observeForViewer(targetContext).content).getLong("frameId")
            val restarted = tools.recoverVirtualUi(checkNotNull(paused.stop))
            ok("paused application restarts", JSONObject(restarted.content))
            eventually("application was recreated on same display", { probe().optInt("taps", -1) == 0 && probe().optInt("display") == info.displayId })
            verify("display session remains owned", VirtualScreenSession.state.value.display?.sessionId == info.sessionId)
            verify("restart invalidates previous observation generation", VirtualScreenSession.state.value.display?.manualInputGeneration == info.manualInputGeneration + 1)
            val observed = tools.execute(AgentModelClient.ToolCall("observe-after-restart", "observe_screen",
                JSONObject().put("include_screenshot", true).toString()))
            val observation = ok("observation resumes after restart", JSONObject(observed.content))
            verify("recovery screenshot is newer than stalled frame", observation.getJSONObject("screenshot").getLong("frame_id") > previousFrameId)
            verifyRecoveryScreenshot("missed tap recovery", observed)
            val resumedPosition = probe()
            ok("input works after recovery", call(tools, "tap", JSONObject()
                .put("x", resumedPosition.getInt("buttonX")).put("y", resumedPosition.getInt("buttonY"))))
            eventually("recovered counter increments", { probe().optInt("taps") == 1 })
            if (screenOffValidation) verifyPrimaryRemainsLocked("missed tap recovery")
            val again = tools.recoverVirtualUi(checkNotNull(paused.stop))
            verify("stale pause cannot restart again", !JSONObject(again.content).optBoolean("ok"))
            verify("stale recovery preserves counter", probe().optInt("taps") == 1)
            tools.retainVirtualScreenOnSuccess()
        }
        verify("successful recovery finishes as completed", VirtualScreenSession.state.value.taskPhase ==
            io.github.mangi.eta.agent.display.VirtualScreenTaskPhase.COMPLETED)
        val info = checkNotNull(VirtualScreenSession.state.value.display)
        val launch = shell("am start --display ${info.displayId} -f 0x18000000 -n $component --ez stall_validation true")
        verify("blocking recovery fixture launched", !launch.contains("Error:") && !launch.contains("Warning: Activity not started"))
        eventually("blocking recovery fixture is ready", { probe().optInt("freezeY") > 0 })
        tools("blocked-recovery-run").use { tools ->
            ok("observe blocking recovery fixture", call(tools, "observe_screen"))
            val position = probe()
            val freeze = tools.execute(AgentModelClient.ToolCall("freeze", "tap", JSONObject()
                .put("x", position.getInt("freezeX")).put("y", position.getInt("freezeY")).put("coordinate_space", "screen").toString()))
            val paused = if (freeze.stop != null) freeze else {
                eventually("recovery fixture main thread is blocked", { probe().optBoolean("blocked") })
                if (screenOffValidation) lockPrimaryForRecovery("blocked application")
                tools.execute(AgentModelClient.ToolCall("blocked-tap", "tap", JSONObject()
                    .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY")).put("coordinate_space", "screen").toString()))
            }
            verify("blocked application produces trusted pause", paused.stop?.code in setOf("UI_INPUT_TIMEOUT", "UI_APP_UNRESPONSIVE"))
            val previousFrameId = JSONObject(VirtualScreenSession.observeForViewer(targetContext).content).getLong("frameId")
            ok("blocked application restarts", JSONObject(tools.recoverVirtualUi(checkNotNull(paused.stop)).content))
            eventually("blocked application is recreated", { !probe().optBoolean("blocked") && probe().optInt("display") == info.displayId })
            val observed = tools.execute(AgentModelClient.ToolCall("blocked-observe-after-restart", "observe_screen",
                JSONObject().put("include_screenshot", true).toString()))
            val observation = ok("blocked recovery can observe", JSONObject(observed.content))
            verify("blocked recovery screenshot is newer than stalled frame", observation.getJSONObject("screenshot").getLong("frame_id") > previousFrameId)
            verifyRecoveryScreenshot("blocked recovery", observed)
            val recovered = probe()
            ok("blocked recovery can tap", call(tools, "tap", JSONObject()
                .put("x", recovered.getInt("buttonX")).put("y", recovered.getInt("buttonY"))))
            eventually("blocked recovery counter increments", { probe().optInt("taps") == 1 })
            if (screenOffValidation) verifyPrimaryRemainsLocked("blocked application recovery")
            tools.retainVirtualScreenOnSuccess()
        }
        shell("am force-stop io.github.mangi.eta.test")
    }

    private fun validateFloatingPreview() {
        verify("floating overlay permission already granted", android.provider.Settings.canDrawOverlays(targetContext))
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        fun previewWindow(): android.view.View? {
            val serviceField = io.github.mangi.eta.agent.runtime.AgentExecutionService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            val service = serviceField.get(null) ?: return null
            val host = service.javaClass.getDeclaredField("floatingPreview").apply { isAccessible = true }.get(service) ?: return null
            val rootField = host.javaClass.getDeclaredField("root").apply { isAccessible = true }
            val root = java.util.concurrent.atomic.AtomicReference<android.view.View?>()
            runOnMainSync { root.set((rootField.get(host) as? android.view.View)?.takeIf { it.isAttachedToWindow && it.isShown }) }
            return root.get()
        }
        fun bounds(): android.graphics.Rect {
            val rect = android.graphics.Rect()
            val view = checkNotNull(previewWindow())
            runOnMainSync {
                val location = IntArray(2).also(view::getLocationOnScreen)
                rect.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            return rect
        }
        fun captureState() = JSONObject(VirtualScreenSession.execute(targetContext, "device-validation",
            JSONObject().put("action", "probe"), manual = true).content).getJSONObject("capture")
        runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenFloatingPreviewEnabled = true) } }
        tools("floating-preview-run").use { tools ->
            ok("create floating fixture display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("launch changing floating fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch")
                .put("component", component).put("uri", "eta-validation://frames")))
            val viewer = VirtualScreenSession.state.value
            checks += "Floating state: controlling=${viewer.isAgentControlling}, phase=${viewer.taskPhase}, full=${viewer.fullViewerVisible}, run=${viewer.activeRunId != null}"
            val serviceField = io.github.mangi.eta.agent.runtime.AgentExecutionService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            val service = serviceField.get(null)
            checks += "Floating service: present=${service != null}"
            if (service != null) {
                val hostField = service.javaClass.getDeclaredField("floatingPreview").apply { isAccessible = true }
                val host = hostField.get(service)
                checks += "Floating host: present=${host != null}"
            }
            eventually("floating preview visible on primary display", { previewWindow() != null })
            eventually("floating preview activates capture", { captureState().optBoolean("viewer_visible") })
            val before = captureState().getLong("encoded_frames")
            val start = SystemClock.elapsedRealtime()
            SystemClock.sleep(1_000)
            val count = captureState().getLong("encoded_frames") - before
            verify("floating preview supplies live frames", count >= 3)
            verify("floating preview stays within ten fps", count <= (SystemClock.elapsedRealtime() - start) / 100 + 1)
            val executionService = io.github.mangi.eta.agent.runtime.AgentExecutionService::class.java
                .getDeclaredField("instance").apply { isAccessible = true }.get(null)!!
            val previewHost = executionService.javaClass.getDeclaredField("floatingPreview").apply { isAccessible = true }.get(executionService)!!
            fun previewScreenshot(name: String) {
                // Only the synthetic fixture is captured. Production previews retain FLAG_SECURE.
                val view = checkNotNull(previewWindow())
                val params = previewHost.javaClass.getDeclaredField("params").apply { isAccessible = true }
                    .get(previewHost) as WindowManager.LayoutParams
                val manager = previewHost.javaClass.getDeclaredField("manager").apply { isAccessible = true }
                    .get(previewHost) as WindowManager
                val flags = params.flags
                try {
                    runOnMainSync {
                        params.flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                        manager.updateViewLayout(view, params)
                    }
                    SystemClock.sleep(200)
                    val area = bounds()
                    val image = checkNotNull(automation.takeScreenshot())
                    try {
                        val crop = Bitmap.createBitmap(image, area.left, area.top, area.width(), area.height())
                        try {
                            val directory = File(targetContext.getExternalFilesDir(null), "validation").apply { mkdirs() }
                            File(directory, name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { crop.recycle() }
                    } finally { image.recycle() }
                } finally {
                    runOnMainSync { params.flags = flags; manager.updateViewLayout(view, params) }
                }
            }
            verify("floating preview decodes and displays frames", previewHost.javaClass.getDeclaredField("lastFrame")
                .apply { isAccessible = true }.getLong(previewHost) > 0)
            val fullBounds = bounds()
            verify("floating preview is a portrait rectangle", fullBounds.height() > fullBounds.width())
            previewScreenshot("floating-preview.png")
            verify("floating preview can be dragged to left edge", dragPrimary(fullBounds.centerX().toFloat(), 16f,
                fullBounds.centerY().toFloat()))
            eventually("edge folds floating preview", {
                previewWindow() != null && bounds().width() < fullBounds.width() / 2
            })
            eventually("folded preview releases capture", { !captureState().optBoolean("viewer_visible") })
            previewScreenshot("floating-preview-folded.png")
            val foldedFrames = captureState().getLong("encoded_frames")
            SystemClock.sleep(500)
            verify("folded preview does not encode frames", captureState().getLong("encoded_frames") == foldedFrames)
            val foldedBounds = bounds()
            verify("folded bubble opens virtual screen", tapPrimary(foldedBounds.centerX().toFloat(), foldedBounds.centerY().toFloat()))
            eventually("tap opens full virtual viewer", { VirtualScreenSession.state.value.fullViewerVisible })
            eventually("full viewer hides floating window", { previewWindow() == null })
            eventually("full viewer keeps its own capture lease", { captureState().optBoolean("viewer_visible") })
            verify("viewer back action succeeds", automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            eventually("floating preview returns after full viewer", { !VirtualScreenSession.state.value.fullViewerVisible && previewWindow() != null })
            val bubble = bounds()
            val primaryWidth = targetContext.getSystemService(DisplayManager::class.java).getDisplay(0)
                .let { display -> android.graphics.Point().also(display::getRealSize).x }
            verify("dragging bubble away from edge succeeds", dragPrimary(bubble.centerX().toFloat(), primaryWidth / 2f, bubble.centerY().toFloat()))
            eventually("dragging away expands preview", { bounds().width() >= fullBounds.width() })
            eventually("expanded preview resumes capture", { captureState().optBoolean("viewer_visible") })
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenFloatingPreviewEnabled = false) } }
            eventually("disabling preference removes floating window", { previewWindow() == null })
            eventually("disabled preview releases capture", { !captureState().optBoolean("viewer_visible") })
            val hiddenFrames = captureState().getLong("encoded_frames")
            SystemClock.sleep(400)
            verify("disabled preview stays idle", captureState().getLong("encoded_frames") == hiddenFrames)
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenFloatingPreviewEnabled = true) } }
            eventually("preference can restore preview in same task", { previewWindow() != null })
            tools.retainVirtualScreenOnSuccess()
        }
        eventually("ending task hides floating preview", { previewWindow() == null })
        verify("task end still retains virtual display", VirtualScreenSession.isActive())
        eventually("retained idle display stops preview capture", { !captureState().optBoolean("viewer_visible") })
    }

    private fun validateFrameCapture() {
        val owner = "device-validation"
        val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
        val viewerId = "device-validation-preview"
        if (screenOffValidation) verifyPrimaryRemainsLocked("before frame capture validation")
        shell("am force-stop io.github.mangi.eta.test")
        tools("frame-capture-run").use { tools ->
            fun captureState(): JSONObject = ok("read capture diagnostics", JSONObject(
                VirtualScreenSession.execute(targetContext, owner, JSONObject().put("action", "probe"), manual = true).content))
                .getJSONObject("capture")
            fun observe(): JSONObject {
                val result = VirtualScreenSession.observeForViewer(targetContext)
                val json = ok("take on-demand frame", JSONObject(result.content))
                verify("on-demand frame has JPEG", result.images.singleOrNull()?.bytes?.let { it > 0 } == true)
                return json
            }
            try {
                ok("create frame validation display", call(tools, "virtual_screen", JSONObject()
                    .put("action", "create").put("allowScreenOff", true)))
                val initial = checkNotNull(VirtualScreenSession.state.value.display)
                ok("launch static fixture without preview", JSONObject(VirtualScreenSession.execute(targetContext, owner,
                    JSONObject().put("action", "launch").put("component", component), manual = true).content))
                eventually("static fixture is on virtual display", { probe().optInt("display") == initial.displayId })
                SystemClock.sleep(500)
                val hidden = captureState()
                verify("initial hidden display does not encode", hidden.getLong("encoded_frames") == 0L)
                verify("capture rate is capped at ten fps", hidden.getInt("max_fps") == 10)
                verify("viewer starts hidden", !hidden.getBoolean("viewer_visible"))
                val first = observe()
                val afterFirst = captureState().getLong("encoded_frames")
                verify("background observation encodes exactly one frame", afterFirst == 1L)
                SystemClock.sleep(400)
                verify("background capture does not continue after observation", captureState().getLong("encoded_frames") == afterFirst)
                val second = observe()
                verify("static page can be observed again without a new surface frame", second.getLong("frameId") > first.getLong("frameId"))
                verify("second static observation also encodes only once", captureState().getLong("encoded_frames") == afterFirst + 1L)

                ok("launch changing fixture", JSONObject(VirtualScreenSession.execute(targetContext, owner,
                    JSONObject().put("action", "launch").put("component", component).put("uri", "eta-validation://frames"),
                    manual = true).content))
                SystemClock.sleep(300)
                val beforePreview = captureState().getLong("encoded_frames")
                VirtualScreenSession.setViewerVisible(viewerId, true)
                val started = SystemClock.elapsedRealtime()
                val previewDeadline = started + 1_500L
                var previewImages = 0
                while (SystemClock.elapsedRealtime() < previewDeadline) {
                    val result = VirtualScreenSession.observeForViewer(targetContext, viewerId = viewerId)
                    verify("visible preview request succeeds", JSONObject(result.content).optBoolean("ok"))
                    if (result.images.isNotEmpty()) previewImages++
                    SystemClock.sleep(100)
                }
                val preview = captureState()
                val duration = SystemClock.elapsedRealtime() - started
                val captured = preview.getLong("encoded_frames") - beforePreview
                verify("visible changing screen supplies frames", captured >= 3 && previewImages >= 3)
                verify("preview cannot exceed ten fps", captured <= duration / 100L + 1L)
                checks += "Preview: $captured encoded frames in $duration ms"

                VirtualScreenSession.setViewerVisible(viewerId, false)
                eventually("leaving preview stops continuous capture", { !captureState().getBoolean("viewer_visible") })
                val stopped = captureState().getLong("encoded_frames")
                SystemClock.sleep(500)
                verify("hidden animation does not encode", captureState().getLong("encoded_frames") == stopped)
                val denied = VirtualScreenSession.observeForViewer(targetContext, viewerId = viewerId)
                verify("late viewer requests are rejected", JSONObject(denied.content).optString("code") == "VIEWER_HIDDEN")
                observe()
                val afterBackground = captureState().getLong("encoded_frames")
                verify("hidden animation observation captures exactly once", afterBackground == stopped + 1L)
                SystemClock.sleep(500)
                verify("hidden animated observation leaves capture stopped", captureState().getLong("encoded_frames") == afterBackground)

                // No Activity cleanup callback: root's heartbeat must expire on its own.
                VirtualScreenSession.setViewerVisible(viewerId, true)
                ok("refresh simulated viewer heartbeat", JSONObject(VirtualScreenSession.observeForViewer(
                    targetContext, viewerId = viewerId).content))
                SystemClock.sleep(1_700)
                verify("missing viewer heartbeat expires", !captureState().getBoolean("viewer_visible"))
                val expired = captureState().getLong("encoded_frames")
                SystemClock.sleep(400)
                verify("expired viewer does not encode", captureState().getLong("encoded_frames") == expired)

                ok("close display while viewer remains resumed", call(tools, "virtual_screen", JSONObject().put("action", "close")))
                ok("create replacement display while viewer remains resumed", call(tools, "virtual_screen", JSONObject()
                    .put("action", "create").put("allowScreenOff", true)))
                verify("replacement session is new", VirtualScreenSession.state.value.display?.sessionId != initial.sessionId)
                ok("launch replacement static fixture", JSONObject(VirtualScreenSession.execute(targetContext, owner,
                    JSONObject().put("action", "launch").put("component", component), manual = true).content))
                val replaced = VirtualScreenSession.observeForViewer(targetContext, viewerId = viewerId)
                ok("resumed viewer can see a newly created session", JSONObject(replaced.content))
                verify("replacement supplies preview image", replaced.images.isNotEmpty())
                if (screenOffValidation) verifyPrimaryRemainsLocked("after frame capture validation")
            } finally {
                VirtualScreenSession.setViewerVisible(viewerId, false)
            }
        }
        shell("am force-stop io.github.mangi.eta.test")
    }

    private fun validateLandscape() {
        if (screenOffValidation) lockPrimaryForRecovery("landscape rotation")
        val primaryRotation = targetContext.getSystemService(DisplayManager::class.java).getDisplay(0).rotation
        shell("am force-stop io.github.mangi.eta.test")
        val component = "io.github.mangi.eta.test/io.github.mangi.eta.validation.LandscapeProbeActivity"
        tools("landscape-run").use { tools ->
            ok("create portrait output for landscape app", call(tools, "virtual_screen", JSONObject()
                .put("action", "create").put("width", 720).put("height", 1600).put("density", 320).put("allowScreenOff", true)))
            ok("launch fixed landscape fixture", call(tools, "virtual_screen", JSONObject().put("action", "launch").put("component", component)))
            eventually("fixture requests landscape", { probe().optInt("orientation") == Configuration.ORIENTATION_LANDSCAPE })
            eventually("virtual display follows landscape request", {
                val geometry = JSONObject(VirtualScreenSession.execute(targetContext, "device-validation", JSONObject().put("action", "probe")).content)
                geometry.optInt("width") > geometry.optInt("height")
            })
            val observation = tools.execute(AgentModelClient.ToolCall("landscape-observe", "observe_screen",
                JSONObject().put("include_screenshot", true).toString()))
            val json = ok("observe landscape fixture", JSONObject(observation.content))
            verify("landscape logical dimensions are wide", json.getJSONObject("screen").getInt("width") > json.getJSONObject("screen").getInt("height"))
            val initialDisplay = checkNotNull(VirtualScreenSession.state.value.display)
            verify("landscape app fills logical width", probe().getInt("layoutWidth") == initialDisplay.width)
            verify("landscape app fills logical height", probe().getInt("layoutHeight") > initialDisplay.height * 3 / 4)
            verify("screenshot matches landscape coordinates", observation.images.single().width == initialDisplay.width &&
                observation.images.single().height == initialDisplay.height)
            val position = probe()
            verifyLandscapeScreenshot(observation, position)
            ok("AI landscape tap", call(tools, "tap", JSONObject().put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
            eventually("AI landscape tap increments counter", { probe().optInt("taps") == 1 })
            ok("switch fixture to portrait", call(tools, "tap", JSONObject().put("x", position.getInt("rotateX")).put("y", position.getInt("rotateY"))))
            eventually("fixture switches to portrait", { probe().optInt("orientation") == Configuration.ORIENTATION_PORTRAIT })
            eventually("virtual display follows portrait request", {
                val geometry = JSONObject(VirtualScreenSession.execute(targetContext, "device-validation", JSONObject().put("action", "probe")).content)
                geometry.optInt("width") < geometry.optInt("height")
            })
            val portrait = checkNotNull(VirtualScreenSession.state.value.display)
            verify("portrait dimensions update on same display", portrait.width < portrait.height && portrait.sessionId == initialDisplay.sessionId)
            verify("rotation invalidates old observation", portrait.manualInputGeneration > initialDisplay.manualInputGeneration)
            verify("old landscape coordinates are rejected", call(tools, "tap", JSONObject().put("x", position.getInt("buttonX"))
                .put("y", position.getInt("buttonY"))).optString("code") == "STALE_OBSERVATION")
            ok("observe portrait after rotation", call(tools, "observe_screen", JSONObject().put("include_screenshot", true)))
            val portraitPosition = probe()
            ok("portrait tap after rotation", call(tools, "tap", JSONObject().put("x", portraitPosition.getInt("buttonX"))
                .put("y", portraitPosition.getInt("buttonY"))))
            eventually("portrait input works", { probe().optInt("taps") == 2 })
            ok("switch fixture back to landscape", call(tools, "tap", JSONObject().put("x", portraitPosition.getInt("rotateX"))
                .put("y", portraitPosition.getInt("rotateY"))))
            eventually("fixture switches back to landscape", { probe().optInt("orientation") == Configuration.ORIENTATION_LANDSCAPE })
            eventually("virtual display switches back to landscape", {
                val geometry = JSONObject(VirtualScreenSession.execute(targetContext, "device-validation", JSONObject().put("action", "probe")).content)
                geometry.optInt("width") > geometry.optInt("height")
            })
            ok("observe landscape after rotation", call(tools, "observe_screen", JSONObject().put("include_screenshot", true)))
            verify("main display rotation is unchanged", targetContext.getSystemService(DisplayManager::class.java).getDisplay(0).rotation == primaryRotation)
            if (screenOffValidation) verifyPrimaryRemainsLocked("landscape rotation") else validateLandscapeViewer()
            tools.retainVirtualScreenOnSuccess()
        }
        shell("am force-stop io.github.mangi.eta.test")
    }

    private fun validateLandscapeViewer() {
        val viewer = startActivitySync(Intent(targetContext, VirtualScreenViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        try {
            runOnMainSync { viewer.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(1000)
            val surface = java.util.concurrent.atomic.AtomicReference<io.github.mangi.eta.agent.display.VirtualScreenSurfaceView?>()
            fun findSurface(view: android.view.View): io.github.mangi.eta.agent.display.VirtualScreenSurfaceView? {
                if (view is io.github.mangi.eta.agent.display.VirtualScreenSurfaceView) return view
                val group = view as? android.view.ViewGroup ?: return null
                for (index in 0 until group.childCount) findSurface(group.getChildAt(index))?.let { return it }
                return null
            }
            runOnMainSync { surface.set(findSurface(viewer.window.decorView)) }
            verify("landscape viewer has preview", surface.get() != null)
            val current = checkNotNull(VirtualScreenSession.state.value.display)
            val position = probe()
            val taps = position.getInt("taps")
            val view = checkNotNull(surface.get())
            runOnMainSync {
                val viewport = checkNotNull(io.github.mangi.eta.agent.display.VirtualScreenViewport.fit(
                    view.width, view.height, current.width, current.height))
                verify("landscape viewer rotates clockwise into portrait bounds", viewport.rotatesClockwise && view.height > view.width)
                verify("rotated preview fills viewer bounds", viewport.left < 2f && viewport.top < 2f)
                val point = viewport.toView(position.getInt("buttonX"), position.getInt("buttonY"))
                val x = point.x
                val y = point.y
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 100 else 0,
                        action, x, y, 0)
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
            }
            eventually("manual landscape preview tap hits button", { probe().optInt("taps") == taps + 1 })
            screenshot("viewer-landscape.png")
        } finally { runOnMainSync { viewer.finish() } }
    }

    private fun verifyLandscapeScreenshot(result: AgentModelClient.ToolResult, position: JSONObject) {
        val reference = result.images.single().reference
        val bytes = Base64.decode(reference.substringAfter("base64,"), Base64.DEFAULT)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val color = bitmap.getPixel(position.getInt("buttonX") + position.getInt("buttonWidth") / 4, position.getInt("buttonY"))
            verify("landscape pixels match logical button coordinates", Color.green(color) in 150..190 &&
                Color.red(color) in 15..55 && Color.blue(color) in 65..105)
        } finally { bitmap.recycle() }
    }

    private fun verifyRecoveryScreenshot(stage: String, result: AgentModelClient.ToolResult) {
        val reference = checkNotNull(result.images.firstOrNull()?.reference)
        val bytes = Base64.decode(reference.substringAfter("base64,"), Base64.DEFAULT)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val background = bitmap.getPixel(12, bitmap.height / 2)
            val colors = (0 until 32 * 64).map { index ->
                bitmap.getPixel((index % 32) * bitmap.width / 32, (index / 32) * bitmap.height / 64)
            }.toSet()
            verify("$stage screenshot contains fixture content", listOf(Color.red(background), Color.green(background),
                Color.blue(background)).all { it in 235..255 } && colors.size > 16)
        } finally { bitmap.recycle() }
    }

    private fun primaryIsOffAndLocked(): Boolean {
        val display = targetContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return display != null && display.state in setOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND) &&
            targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    private fun lockPrimaryForRecovery(stage: String) {
        shell("cmd power sleep")
        eventually("primary is off and locked before $stage recovery", { primaryIsOffAndLocked() })
    }

    private fun verifyPrimaryRemainsLocked(stage: String) {
        verify("primary stays off and locked after $stage", primaryIsOffAndLocked())
    }

    private fun validateViewer() {
        val viewer = startActivitySync(Intent(targetContext, VirtualScreenViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        var previousClip: ClipData? = null
        var seeded = false
        val clipboard = viewer.getSystemService(ClipboardManager::class.java)
        try {
            runOnMainSync { viewer.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(1000)
            verify("viewer activity uses primary display", viewer.display?.displayId == 0)
            screenshot("viewer-collapsed.png")
            eventually("viewer is on primary", { primaryRoot()?.packageName?.toString() == targetContext.packageName })
            verify("input field defaults folded", findPrimary { it.isEditable } == null)
            val toggle = targetContext.getString(R.string.virtual_screen_text_toggle)
            verify("keyboard toggle clickable", clickPrimary(toggle))
            eventually("host input field expands", { findPrimary { it.isEditable } != null })
            eventually("native host keyboard visible", { automation.windowsOnAllDisplays.get(0).orEmpty()
                .any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } })
            runOnMainSync {
                previousClip = clipboard.primaryClip
                clipboard.setPrimaryClip(ClipData.newPlainText("eta-validation", "eta-clipboard-sentinel"))
                seeded = true
            }
            val text = "宿主键盘 中文測試 😀"
            verify("host field accepts Unicode", findPrimary { it.isEditable }?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }) == true)
            eventually("host draft contains Unicode", { findPrimary { it.isEditable }?.text?.toString() == text })
            screenshot("viewer-keyboard.png")
            val send = targetContext.getString(R.string.virtual_screen_text_insert)
            eventually("send button enabled", { findPrimary { it.contentDescription?.toString() == send }?.isEnabled == true })
            verify("host text send clickable", clickPrimary(send))
            eventually("manual text request completes", { VirtualScreenSession.state.value.operations.any { it.manual && it.name == "text" } }, 15_000)
            verify("manual text request accepted", VirtualScreenSession.state.value.operations.last { it.manual && it.name == "text" }.success)
            eventually("host keyboard text reaches virtual editor", { probe().optString("text") == text })
            var restored = false
            runOnMainSync { restored = clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "eta-clipboard-sentinel" }
            verify("root input preserves clipboard", restored)
            eventually("sent draft clears", { findPrimary { it.isEditable }?.text?.isEmpty() == true })
            clickPrimary(toggle)
            eventually("input field folds again", { findPrimary { it.isEditable } == null })
            validateSettingsAndApproval(viewer as VirtualScreenViewerActivity)
        } finally {
            runOnMainSync {
                if (seeded && clipboard.primaryClip?.description?.label?.toString() == "eta-validation") {
                    if (previousClip != null) clipboard.setPrimaryClip(previousClip!!) else clipboard.clearPrimaryClip()
                }
                viewer.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                viewer.finish()
            }
        }
    }

    private fun primaryRoot(): AccessibilityNodeInfo? = automation.windowsOnAllDisplays.get(0).orEmpty()
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .firstNotNullOfOrNull { it.root?.takeIf { root -> root.packageName?.toString() == targetContext.packageName } }

    private fun findPrimary(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        primaryRoot()?.let(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1000) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun clickPrimary(description: String): Boolean {
        val node = findPrimary { it.contentDescription?.toString() == description } ?: return false
        var ancestor: AccessibilityNodeInfo? = node
        repeat(8) {
            val current = ancestor ?: return@repeat
            if (current.isClickable && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ancestor = current.parent
        }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        return tapPrimary(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun tapPrimary(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
        return try { automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true) }
        finally { down.recycle(); up.recycle() }
    }

    private fun dragPrimary(x1: Float, x2: Float, y: Float): Boolean {
        val start = SystemClock.uptimeMillis()
        var accepted = true
        for (step in 0..8) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 8 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x1 + (x2 - x1) * step / 8f, y, 0)
            try { accepted = automation.injectInputEvent(event, true) && accepted } finally { event.recycle() }
            SystemClock.sleep(30)
        }
        return accepted
    }

    private fun validateSettingsAndApproval(viewer: VirtualScreenViewerActivity) {
        runOnMainSync {
            viewer.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    VirtualScreenSettingsScreen(onBack = {})
                    VirtualScreenAppConflictDialog()
                }
            }
        }
        SystemClock.sleep(700)
        repeat(2) { findPrimary { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(300) }
        eventually("cleanup slider visible", { findPrimary { it.rangeInfo != null } != null })
        screenshot("virtual-settings.png")
        for ((index, minutes) in listOf(10, 20, 60, 0).withIndex()) {
            val slider = checkNotNull(findPrimary { it.rangeInfo != null })
            val bounds = android.graphics.Rect().also(slider::getBoundsInScreen)
            val inset = bounds.height() / 2f
            val track = bounds.width() - 2 * inset
            val current = slider.rangeInfo.current
            verify("cleanup slider selects $minutes", dragPrimary(bounds.left + inset + track * current / 3f,
                bounds.left + inset + track * index / 3f, bounds.centerY().toFloat()))
            eventually("cleanup setting persists $minutes", { runBlocking { SettingsDataStore.settings() }.virtualScreenIdleTimeoutMinutes == minutes })
        }
        verify("never-close warning visible", findPrimary { it.text?.toString() == targetContext.getString(R.string.virtual_screen_idle_warning) } != null)
        val choices = listOf(
            R.string.virtual_screen_conflict_restart to MainScreenFallbackDecision.RESTART_VIRTUAL,
            R.string.virtual_screen_conflict_primary to MainScreenFallbackDecision.ALLOWED,
            R.string.virtual_screen_conflict_cancel to MainScreenFallbackDecision.TASK_CANCELLED,
        )
        for ((index, choice) in choices.withIndex()) {
            val owner = "device-approval-$index"
            val answer = java.util.concurrent.atomic.AtomicReference<MainScreenFallbackDecision>()
            val worker = Thread {
                answer.set(MainScreenFallbackApproval.request(targetContext, owner, "launch_app", "APP_ALREADY_RUNNING", { false }, { true }))
            }.apply { start() }
            try {
                val label = targetContext.getString(choice.first)
                eventually("conflict dialog shows ${choice.second}", { findPrimary { it.text?.toString() == label } != null })
                if (index == 0) {
                    screenshot("app-conflict.png")
                    val notification = targetContext.getSystemService(NotificationManager::class.java).activeNotifications
                        .firstOrNull { it.tag == MainScreenFallbackApproval.state.value.firstOrNull()?.token }?.notification
                    verify("conflict notification has three actions", notification?.actions?.size == 3)
                    verify("restart and primary notification actions require unlock", notification!!.actions.take(2).all { it.isAuthenticationRequired })
                }
                val node = checkNotNull(findPrimary { it.text?.toString() == label })
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                verify("choose ${choice.second}", tapPrimary(bounds.centerX().toFloat(), bounds.centerY().toFloat()))
                eventually("conflict choice resolves ${choice.second}", { answer.get() == choice.second })
            } finally { MainScreenFallbackApproval.cancelOwner(owner); worker.join(2000) }
        }
        verify("approval prompts clear", MainScreenFallbackApproval.state.value.isEmpty())
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot()) { "SCREENSHOT_UNAVAILABLE" }
        try {
            val directory = java.io.File(targetContext.getExternalFilesDir(null), "validation").apply { mkdirs() }
            java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun validateSearch(tools: AgentLocalTools) {
        val result = ok("real network web search", call(tools, "web_search", JSONObject().put("query", "Android virtual display IME").put("max_results", 3)))
        verify("search returns source URLs", result.getJSONArray("results").length() > 0)
        checks += "search provider: " + result.optString("provider")
        ok("real network web fetch", call(tools, "fetch_url", JSONObject().put("url", "https://example.com/")))
    }

    private fun validateWechat() {
        tools().use { tools ->
            ok("launch WeChat virtual", call(tools, "launch_app", JSONObject().put("package_name", "com.tencent.mm")))
            SystemClock.sleep(2500)
            val observed = ok("observe WeChat virtual", call(tools, "observe_screen"))
            val display = VirtualScreenSession.state.value.display!!
            verify("WeChat remains on virtual display", observed.getJSONObject("focus").optString("package") == "com.tencent.mm")
            verify("WeChat default observation attaches image", observed.getJSONObject("screenshot").optBoolean("attached"))
            val before = virtualPixels()
            val nodes = observed.getJSONArray("ui_nodes")
            val me = (0 until nodes.length()).map(nodes::getJSONObject).firstOrNull { it.optString("text") == "我" }
            if (me != null) {
                val result = call(tools, "tap_element", JSONObject().put("index", me.getInt("index"))
                    .put("observation_id", observed.getString("observation_id")))
                SystemClock.sleep(700)
                checks += "WeChat node tap: ok=${result.optBoolean("ok")}, code=${result.optString("code")}, changed=${pixelsChanged(before, virtualPixels())}"
                call(tools, "observe_screen")
            } else {
                repeat(3) {
                    SystemClock.sleep(750)
                    call(tools, "observe_screen", JSONObject().put("include_screenshot", false))
                }
                verify("repeated WeChat empty trees temporarily hide node tools", tools.capabilitiesForRun(AgentToolCapabilities.capture(targetContext))
                    .unavailableCode("tap_element") == "VIRTUAL_UI_TREE_UNAVAILABLE")
            }
            ok("WeChat AI coordinate tab tap", call(tools, "tap", JSONObject().put("x", display.width * 7 / 8).put("y", display.height - 100)))
            SystemClock.sleep(800)
            verify("WeChat tab actually changes", pixelsChanged(before, virtualPixels()))
            call(tools, "observe_screen")
            ok("WeChat AI contacts tab", call(tools, "tap", JSONObject().put("x", display.width * 3 / 8).put("y", display.height - 100)))
            SystemClock.sleep(800)
            val contacts = virtualPixels()
            call(tools, "observe_screen")
            ok("WeChat AI swipe", call(tools, "swipe", JSONObject().put("x1", display.width / 2).put("y1", display.height * 3 / 4)
                .put("x2", display.width / 2).put("y2", display.height / 3).put("duration_ms", 600)))
            SystemClock.sleep(900)
            verify("WeChat swipe actually moves content", pixelsChanged(contacts, virtualPixels()))
            ok("WeChat manual swipe", JSONObject(VirtualScreenSession.inputForViewer(targetContext, display.sessionId,
                JSONObject().put("action", "swipe").put("x1", display.width / 2).put("y1", display.height / 3)
                    .put("x2", display.width / 2).put("y2", display.height * 3 / 4).put("durationMs", 600)).content))
            verify("WeChat stale AI coordinates rejected", call(tools, "tap", JSONObject().put("x", 100).put("y", 100)).optString("code") == "STALE_OBSERVATION")
            ok("WeChat observation recovers after manual input", call(tools, "observe_screen"))
            tools.retainVirtualScreenOnSuccess()
        }
    }

    private fun virtualPixels(): IntArray {
        val frame = VirtualScreenSession.execute(targetContext, "device-validation", JSONObject().put("action", "observe"))
        val reference = checkNotNull(frame.images.firstOrNull()?.reference)
        val bytes = Base64.decode(reference.substringAfter("base64,"), Base64.DEFAULT)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return try {
            IntArray(48 * 96) { index ->
                bitmap.getPixel((index % 48) * bitmap.width / 48, bitmap.height / 12 + (index / 48) * bitmap.height * 5 / (96 * 6))
            }
        } finally { bitmap.recycle() }
    }

    private fun pixelsChanged(before: IntArray, after: IntArray): Boolean = before.indices.count { index ->
        val a = before[index]
        val b = after[index]
        kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 60
    } > before.size / 30

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
