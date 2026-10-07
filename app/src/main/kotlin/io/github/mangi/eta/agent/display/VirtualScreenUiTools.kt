package io.github.mangi.eta.agent.display

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.ScrollAmount
import io.github.mangi.eta.agent.device.ScrollDirection
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Normal GUI tool names keep their contract, but are bound to one owned secondary display. */
internal class VirtualScreenUiTools(
    private val context: Context,
    private val owner: String,
    private val isCancelled: () -> Boolean,
    private val launchApp: (JSONObject) -> AgentModelClient.ToolResult,
    private val openUri: (JSONObject) -> AgentModelClient.ToolResult,
    private val runId: String = owner,
) {
    private var snapshot: AgentAccessibilityService.NodeSnapshot? = null
    private var observedSession: String? = null
    private var observedManualGeneration = -1L
    private var clipboard = ""
    private val uiTreeAvailability = VirtualScreenUiTreeAvailability(SystemClock::elapsedRealtime)
    val uiTreeUnavailable: Boolean get() =
        uiTreeAvailability.unavailableFor(VirtualScreenSession.state.value.display)

    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult = try {
        if (name in VirtualScreenRoutingPolicy.unavailableTools) {
            failure(
                "VIRTUAL_ACTION_UNSUPPORTED",
                "此 UI 工具不能在虚拟屏执行；请使用虚拟屏内的应用，不会操作主屏。"
            )
        } else when (name) {
            "launch_app" -> launchApp(args)
            "open_uri" -> openUri(args)
            "wait" -> {
                waitCancellable(args.optInt("duration_ms", 1000).coerceIn(100, 30000))
                success(name)
            }

            "wait_for_text", "wait_for_package" -> waitFor(name, args)
            else -> withDisplay { info ->
                val before = snapshot
                val result = dispatch(name, args, info)
                if (name in AFTER_ACTION_TOOLS) afterAction(result, before) else result
            }
        }
    } catch (error: Exception) {
        failure(
            if (error is IllegalArgumentException || error is IllegalStateException) error.message
                ?: "VIRTUAL_UI_FAILED" else "VIRTUAL_UI_FAILED",
            "虚拟屏操作未完成；请重新观察，不会回退到主屏。"
        )
    }

    fun launchComponent(component: String, uri: String = ""): AgentModelClient.ToolResult =
        withDisplay {
            snapshot = null
            VirtualScreenSession.execute(
                context, owner, JSONObject().put("action", "launch")
                    .put("component", component).put("uri", uri), isCancelled
            )
        }

    private fun withDisplay(block: (VirtualDisplayInfo) -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        check(!isCancelled()) { "DISPLAY_CANCELLED" }
        val settings = runBlocking { SettingsDataStore.settings() }
        require(settings.virtualScreenEnabled) { "VIRTUAL_SCREEN_DISABLED" }
        require(VirtualScreenSession.prepareForRun(owner, runId)) { "DISPLAY_BUSY" }
        if (!VirtualScreenSession.isActive()) {
            val created = VirtualScreenSession.execute(
                context, owner, JSONObject().put("action", "create")
                    .put("allowScreenOff", settings.virtualScreenOffEnabled), isCancelled, runId
            )
            if (!JSONObject(created.content).optBoolean("ok")) return created
        }
        return VirtualScreenSession.withDisplay(context, owner, isCancelled) { info ->
            uiTreeAvailability.updateScope(info)
            block(info)
        }
    }

    private fun dispatch(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo
    ): AgentModelClient.ToolResult = when (name) {
        "observe_screen" -> observe(args, info)
        "tap", "tap_area", "long_press", "swipe", "scroll" -> coordinateAction(name, args, info)
        "tap_element", "long_press_element", "scroll_element" -> nodeAction(name, args, info)
        "type_text" -> typeText(args, info)
        "input_text", "replace_text", "clear_text", "paste_text" -> textAction(name, args, info)
        "set_clipboard" -> {
            val text = args.getString("text")
            require(text.length <= 20000) { "TEXT_TOO_LONG" }
            clipboard = text
            AgentModelClient.ToolResult(
                JSONObject().put("ok", true).put("tool", name).put("scope", "virtual_run")
                    .toString()
            )
        }

        "get_clipboard" -> AgentModelClient.ToolResult(
            JSONObject().put("ok", true).put("text", clipboard)
                .put("scope", "virtual_run").toString(), sensitive = true
        )

        "press_key" -> {
            if (args.optString("button").equals("PASTE", true)) textAction(
                "paste_text",
                JSONObject().put("text", clipboard),
                info
            )
            else VirtualScreenSession.execute(
                context, owner, JSONObject().put("action", "key")
                    .put("button", args.getString("button").uppercase()), isCancelled
            )
        }

        else -> failure("VIRTUAL_ACTION_UNSUPPORTED", "此 UI 操作不支持虚拟屏")
    }

    private fun observe(args: JSONObject, info: VirtualDisplayInfo): AgentModelClient.ToolResult {
        snapshot = null
        observedSession = null
        val options = AgentScreenObservationContract.resolve(args)
        val service = AgentAccessibilityService.current()
        fun captureTree() = service?.captureNodeSnapshot(options.maxNodes, info.displayId)?.takeIf {
            it.displayId == info.displayId && it.packageName == info.focusedPackage
        }
        var candidate = if (options.includeUiTree) {
            captureTree()
        } else null
        var nodes = candidate?.takeIf { it.nodes.isNotEmpty() }
        // Every requested observation probes again, including a previously empty window. Startup
        // retries count as one observation; only spaced empty observations hide node tools.
        var retry = 0
        while (options.includeUiTree && nodes == null && candidate != null &&
            !uiTreeAvailability.unavailableFor(info) && retry++ < 2) {
            waitCancellable(250)
            candidate = captureTree()
            nodes = candidate?.takeIf { it.nodes.isNotEmpty() }
        }
        if (options.includeUiTree) uiTreeAvailability.record(info, candidate?.windowId, nodes != null)
        val treeUnavailable = uiTreeAvailability.unavailableFor(info)
        val screen = JSONObject().put("width", info.width).put("height", info.height)
            .put("display_id", info.displayId)
        val json = JSONObject().put("ok", true).put("tool", "observe_screen")
            .put("display_id", info.displayId)
            .put("screen", screen).put(
                "focus",
                JSONObject().put("package", info.focusedPackage).put("display_id", info.displayId)
            )
            .put(
                "execution_scope",
                JSONObject().put("type", "virtual_display").put("display_id", info.displayId)
                    .put("main_screen_locked", context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
                    .put("note", "观察和输入仅针对这个虚拟屏。主屏的锁屏、指纹窗口（如 UDfinger）和全局 mCurrentFocus 不代表此虚拟屏被遮挡；依据本次观察和此虚拟屏工具的实际结果判断。")
            )
            .put("observation_id", nodes?.id ?: JSONObject.NULL)
            .put("observation_source", if (nodes != null) "accessibility" else JSONObject.NULL)
            .put("window_id", nodes?.windowId ?: JSONObject.NULL)
            .put("ui_tree_truncated", nodes?.truncated ?: false)
            .put("node_limit", options.maxNodes.coerceIn(1, 120))
            .put("ui_nodes", JSONArray(nodes?.nodes.orEmpty().map(::nodeJson)))
            .put(
                "accessibility", JSONObject().put("available", nodes != null)
                    .put("ui_tree_disabled", treeUnavailable)
                    .put("ui_tree_pending", options.includeUiTree && nodes == null && !treeUnavailable)
                    .put("note", if (!options.includeUiTree)
                        "本次未请求 UI 树。"
                        else if (treeUnavailable)
                            "当前应用窗口多次返回空 UI 树，暂时停用节点工具；后续观察仍会探测，节点恢复或切换应用窗口后解除限制。使用截图与坐标工具；焦点文本可使用 Root 输入。"
                        else if (nodes == null)
                            "当前窗口尚无有效 UI 树，可能仍在加载；后续观察会重新探测。"
                        else "仅查询此虚拟屏窗口，不读取主屏窗口。")
            )
            .put(
                "coordinate_contract",
                JSONObject().put("coordinate_space_required", true)
                    .put("supported_coordinate_spaces", JSONArray(listOf("normalized", "screen", "screenshot")))
                    .put("screen", screen)
                    .put("note", "screen 与截图均为虚拟屏原始像素；所有 GUI 工具均针对该 display。")
            )
        val captureScreenshot = options.includeScreenshot ||
            (nodes == null && options.includeUiTree && !args.has("include_screenshot"))
        val capture = if (captureScreenshot) VirtualScreenSession.execute(
            context,
            owner,
            JSONObject().put("action", "observe"),
            isCancelled
        ) else null
        val images = capture?.images.orEmpty()
        json.put(
            "screenshot",
            JSONObject().put("attached", images.isNotEmpty()).put("width", info.width)
                .put("height", info.height)
        )
        if (capture != null && !JSONObject(capture.content).optBoolean("ok")) {
            json.put("screenshot_error", JSONObject(capture.content).optString("code"))
            if (nodes == null) return capture
        }
        snapshot = nodes
        observedSession = info.sessionId
        observedManualGeneration = info.manualInputGeneration
        return AgentModelClient.ToolResult(json.toString(), images, sensitive = true)
    }

    private fun coordinateAction(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo
    ): AgentModelClient.ToolResult {
        require(
            info.manualInputGeneration == 0L ||
                    (observedSession == info.sessionId && observedManualGeneration == info.manualInputGeneration)
        ) { "STALE_OBSERVATION" }
        fun point(x: String, y: String): Pair<Int, Int> {
            return coordinatePoint(integer(args, x), integer(args, y), args.optString("coordinate_space"), info.width, info.height)
        }

        val action = JSONObject().put(
            "action", when (name) {
                "scroll" -> "swipe"; "tap_area" -> "tap"; else -> name
            }
        )
        when (name) {
            "tap", "long_press" -> point("x", "y").let { (x, y) -> action.put("x", x).put("y", y) }
            "tap_area" -> {
                val first = point("x1", "y1");
                val second = point("x2", "y2")
                action.put("x", (first.first + second.first) / 2)
                    .put("y", (first.second + second.second) / 2)
            }

            "swipe" -> {
                val first = point("x1", "y1");
                val second = point("x2", "y2")
                action.put("x1", first.first).put("y1", first.second).put("x2", second.first)
                    .put("y2", second.second)
            }

            "scroll" -> {
                val direction =
                    requireNotNull(ScrollDirection.parse(args.optString("direction"))) { "INVALID_DIRECTION" }
                val gesture = requireNotNull(
                    direction.gestureWithin(
                        Rect(
                            0,
                            0,
                            info.width,
                            info.height
                        ),
                        requireNotNull(ScrollAmount.parse(args.optString("amount"))) { "INVALID_AMOUNT" }
                    )
                ) { "INVALID_COORDINATES" }
                action.put("x1", gesture.start.x).put("y1", gesture.start.y)
                    .put("x2", gesture.end.x).put("y2", gesture.end.y)
            }
        }
        action.put(
            "durationMs", args.optInt("duration_ms", if (name == "long_press") 800 else 500)
                .coerceIn(
                    if (name == "long_press") 300 else 100,
                    if (name == "long_press") 3000 else 2000
                )
        )
        return VirtualScreenSession.execute(context, owner, action, isCancelled)
    }

    private fun requiredSnapshot(
        args: JSONObject,
        info: VirtualDisplayInfo
    ): AgentAccessibilityService.NodeSnapshot {
        if (uiTreeAvailability.unavailableFor(info)) error("VIRTUAL_UI_TREE_UNAVAILABLE")
        val current = snapshot ?: error("NO_OBSERVATION")
        require(
            observedSession == info.sessionId && observedManualGeneration == info.manualInputGeneration &&
                    current.displayId == info.displayId && current.packageName == info.focusedPackage &&
                    current.id == args.optString("observation_id")
        ) { "STALE_OBSERVATION" }
        return current
    }

    private fun nodeAction(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo
    ): AgentModelClient.ToolResult {
        val nodes = requiredSnapshot(args, info)
        val index = integer(args, "index")
        val node = nodes.nodes.firstOrNull { it.index == index } ?: error("INVALID_NODE_INDEX")
        val service = AgentAccessibilityService.current() ?: error("ACCESSIBILITY_UNAVAILABLE")
        val duration = args.optInt("duration_ms", 800).coerceIn(300, 3000)
        if (name == "scroll_element") {
            val direction =
                requireNotNull(ScrollDirection.parse(args.optString("direction"))) { "INVALID_DIRECTION" }
            val amount = requireNotNull(ScrollAmount.parse(args.optString("amount"))) { "INVALID_AMOUNT" }
            direction.gestureWithin(node.bounds, amount)?.let { gesture ->
                VirtualScreenSession.showNodeGesture(
                    info, "swipe", gesture.start.x, gesture.start.y, 500,
                    gesture.end.x, gesture.end.y
                )
            }
            val result = service.scrollNode(nodes, index, direction, amount)
            return AgentModelClient.ToolResult(
                JSONObject().put("ok", result.ok).put("code", result.code)
                    .put("message", result.message).put("display_id", info.displayId)
                    .put("at_boundary", result.atBoundary)
                    .put("moved", result.moved).put("verified_by", result.verifiedBy).toString()
            )
        }
        VirtualScreenSession.showNodeGesture(
            info, if (name == "long_press_element") "long_press" else "tap",
            node.bounds.centerX(), node.bounds.centerY(), duration
        )
        val result =
            if (name == "tap_element") service.clickNode(nodes, index) else service.longClickNode(
                nodes,
                index,
                duration.toLong()
            )
        return actionResult(result, info)
    }

    private fun typeText(args: JSONObject, info: VirtualDisplayInfo): AgentModelClient.ToolResult {
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        require(mode in setOf("replace", "append")) { "INVALID_ARGUMENT" }
        if (mode == "append") {
            require(args.getString("text").isNotEmpty() && (!args.has("index") || args.isNull("index"))) { "INVALID_ARGUMENT" }
        }
        val written = textAction(if (mode == "replace") "replace_text" else "paste_text", args, info)
        val json = JSONObject(written.content).put("tool", "type_text").put("mode", mode)
        if (json.optBoolean("ok") && args.optBoolean("submit")) {
            val submitted = VirtualScreenSession.execute(context, owner,
                JSONObject().put("action", "key").put("button", "ENTER"), isCancelled)
            val response = JSONObject(submitted.content)
            json.put("submitted", response.optBoolean("ok"))
            if (!response.optBoolean("ok")) json.put("submit_error", response.optString("code", "SUBMIT_FAILED"))
        }
        return written.copy(content = json.toString())
    }

    private fun afterAction(
        result: AgentModelClient.ToolResult,
        before: AgentAccessibilityService.NodeSnapshot?,
    ): AgentModelClient.ToolResult {
        val json = JSONObject(result.content)
        if (!json.optBoolean("ok")) return result
        snapshot = null
        val after = runCatching {
            val observation = withDisplay { info -> observe(JSONObject().put("include_screenshot", false)
                .put("include_ui_tree", true).put("max_nodes", 30), info) }
            snapshot.takeIf { JSONObject(observation.content).optBoolean("ok") }
        }.getOrNull() ?: return result
        val changed = before == null || before.nodes != after.nodes || before.packageName != after.packageName
        json.put("after", JSONObject().put("observation_id", after.id).put("display_id", after.displayId)
            .put("package", after.packageName).put("screen_changed", changed)
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated).put("ui_nodes", JSONArray(after.nodes.map(::nodeJson))))
        return result.copy(content = json.toString(), sensitive = true)
    }

    private fun textAction(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo
    ): AgentModelClient.ToolResult {
        val text = if (name == "clear_text") "" else args.getString("text")
        require(
            text.length <= when (name) {
                "input_text" -> 1000; "paste_text" -> 20000; else -> 4000
            }
        ) { "TEXT_TOO_LONG" }
        val replace = name in setOf(
            "replace_text",
            "clear_text"
        ) || (name == "input_text" && args.optString("mode") == "replace")
        val index = if (replace && args.has("index") && !args.isNull("index")) integer(
            args,
            "index"
        ) else null
        val nodes = if (index != null) requiredSnapshot(args, info) else null
        // Some ROMs expose virtual nodes but time out SET_TEXT; focused input uses display-specific keys.
        if (index == null) {
            return VirtualScreenSession.execute(context, owner, JSONObject().put("action", "text")
                .put("text", text).put("replace", replace), isCancelled)
        }
        val service = AgentAccessibilityService.current()
            ?: return failure("ACCESSIBILITY_UNAVAILABLE", "节点输入不可用，请重新观察截图并定位输入框")
        return actionResult(service.setTextNode(nodes, index, text, info.displayId), info)
    }

    private fun waitFor(name: String, args: JSONObject): AgentModelClient.ToolResult {
        val timeout = args.optInt("timeout_ms", 10000).coerceIn(500, 60000)
        val deadline = SystemClock.elapsedRealtime() + timeout
        val needle = args.optString(if (name == "wait_for_package") "package_name" else "text")
        require(needle.isNotBlank()) { "INVALID_ARGUMENT" }
        val regex = if (args.optString("match") == "regex") Regex(needle) else null
        do {
            val result = withDisplay { info ->
                val service = AgentAccessibilityService.current()
                val matched = if (name == "wait_for_package") {
                    val probe = VirtualScreenSession.execute(
                        context,
                        owner,
                        JSONObject().put("action", "probe"),
                        isCancelled
                    )
                    val json = JSONObject(probe.content)
                    if (!json.optBoolean("ok")) return@withDisplay probe
                    json.optString("packageName") == needle
                } else {
                    val candidate = requireNotNull(service) { "ACCESSIBILITY_UNAVAILABLE" }
                        .captureNodeSnapshot(120, info.displayId)?.takeIf {
                            it.displayId == info.displayId && it.packageName == info.focusedPackage
                        }
                    uiTreeAvailability.record(info, candidate?.windowId, candidate?.nodes?.isNotEmpty() == true)
                    require(!uiTreeAvailability.unavailableFor(info)) { "VIRTUAL_UI_TREE_UNAVAILABLE" }
                    candidate?.nodes.orEmpty().any { node ->
                        (listOf(node.text) + if (args.optBoolean(
                                "include_desc",
                                true
                            )
                        ) listOf(node.desc) else emptyList()).any {
                            when (args.optString("match", "contains")) {
                                "exact" -> it == needle
                                "prefix" -> it.startsWith(needle)
                                "regex" -> regex!!.containsMatchIn(it)
                                "contains" -> it.contains(needle)
                                else -> error("INVALID_MATCH_MODE")
                            }
                        }
                    }
                }
                AgentModelClient.ToolResult(
                    JSONObject().put("ok", true).put("matched", matched).toString()
                )
            }
            val json = JSONObject(result.content)
            if (!json.optBoolean("ok") || json.optBoolean("matched")) return result
            waitCancellable(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        return failure("TIMEOUT", "等待虚拟屏目标超时")
    }

    private fun waitCancellable(duration: Int) {
        var remaining = duration
        while (remaining > 0) {
            check(!isCancelled()) { "DISPLAY_CANCELLED" }
            Thread.sleep(minOf(remaining, 100).toLong())
            remaining -= 100
        }
    }

    private fun nodeJson(node: AgentAccessibilityService.UiNode): JSONObject = JSONObject()
        .put("index", node.index).put("text", node.text).put("desc", node.desc)
        .put("class", node.className)
        .put("package", node.packageName).put("view_id", node.viewId)
        .put(
            "bounds",
            JSONArray(
                listOf(
                    node.bounds.left,
                    node.bounds.top,
                    node.bounds.right,
                    node.bounds.bottom
                )
            )
        )
        .put("center", JSONObject().put("x", node.bounds.centerX()).put("y", node.bounds.centerY()))
        .put("clickable", node.clickable).put("long_clickable", node.longClickable)
        .put("scrollable", node.scrollable)
        .put("focused", node.focused).put("editable", node.editable).put("password", node.password)
        .put("enabled", node.enabled)
        .put("checked", node.checked ?: JSONObject.NULL).put("selected", node.selected).put("hint", node.hint)

    private fun actionResult(
        result: AgentAccessibilityService.NodeActionResult,
        info: VirtualDisplayInfo
    ) =
        AgentModelClient.ToolResult(
            JSONObject().put("ok", result.ok).put("code", result.code)
                .put("message", result.message)
                .put("method", result.method).put("verified", result.verified)
                .put("display_id", info.displayId).toString(), sensitive = true
        )

    private fun integer(args: JSONObject, key: String): Int {
        val value = args.get(key)
        require(
            value is Number && value.toDouble() == value.toInt().toDouble()
        ) { "INVALID_ARGUMENT" }
        return value.toInt()
    }

    companion object {
        private val AFTER_ACTION_TOOLS = setOf("tap", "tap_area", "tap_element", "long_press", "long_press_element",
            "swipe", "scroll", "scroll_element", "type_text", "input_text", "replace_text", "clear_text", "paste_text", "press_key")

        internal fun coordinatePoint(x: Int, y: Int, coordinateSpace: String, width: Int, height: Int): Pair<Int, Int> {
            require(width > 0 && height > 0) { "INVALID_COORDINATES" }
            return when (coordinateSpace.trim().lowercase(Locale.ROOT)) {
                "normalized" -> {
                    require(x in 0..999 && y in 0..999) { "INVALID_COORDINATES" }
                    (x.toLong() * (width - 1) / 999).toInt() to (y.toLong() * (height - 1) / 999).toInt()
                }
                "screen", "screenshot" -> {
                    require(x in 0 until width && y in 0 until height) { "INVALID_COORDINATES" }
                    x to y
                }
                else -> error("INVALID_COORDINATE_SPACE")
            }
        }
    }

    private fun success(name: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", true).put("tool", name)
            .put("scope", "virtual_screen").toString()
    )

    private fun failure(code: String, message: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false)
            .put("code", code).put("message", message).toString(), sensitive = true
    )
}
