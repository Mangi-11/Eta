package io.github.mangi.eta.validation

import android.app.Activity
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
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
    private var restoreTimeout: Int? = null
    private lateinit var automation: UiAutomation

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "core"
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
                virtualScreenIdleTimeoutMinutes = backup.getInt("idle_timeout"),
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
            connectEnabledAccessibilityService()
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenEnabled = true,
                virtualScreenFallbackEnabled = false, virtualScreenAutoRestartApps = false,
                virtualScreenIdleTimeoutMinutes = 0) } }
            when (mode) {
                "wechat" -> validateWechat()
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
