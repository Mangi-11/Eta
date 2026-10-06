package io.github.mangi.eta.agent.display

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.ScrollDirection
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Normal GUI tool names keep their contract, but are bound to one owned secondary display. */
internal class VirtualScreenUiTools(
    private val context: Context,
    private val owner: String,
    private val isCancelled: () -> Boolean,
    private val launchApp: (JSONObject) -> AgentModelClient.ToolResult,
    private val openUri: (JSONObject) -> AgentModelClient.ToolResult,
) {
    private var snapshot: AgentAccessibilityService.NodeSnapshot? = null
    private var observedSession: String? = null
    private var observedManualGeneration = -1L
    private var clipboard = ""

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
            else -> withDisplay { info -> dispatch(name, args, info) }
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
        if (!VirtualScreenSession.isActive()) {
            val created = VirtualScreenSession.execute(
                context, owner, JSONObject().put("action", "create")
                    .put("allowScreenOff", settings.virtualScreenOffEnabled), isCancelled
            )
            if (!JSONObject(created.content).optBoolean("ok")) return created
        }
        return VirtualScreenSession.withDisplay(context, owner, isCancelled, block)
    }

    private fun dispatch(
        name: String,
        args: JSONObject,
        info: VirtualDisplayInfo
    ): AgentModelClient.ToolResult = when (name) {
        "observe_screen" -> observe(args, info)
        "tap", "tap_area", "long_press", "swipe", "scroll" -> coordinateAction(name, args, info)
        "tap_element", "long_press_element", "scroll_element" -> nodeAction(name, args, info)
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
        val nodes = if (options.includeUiTree) service?.captureNodeSnapshot(
            options.maxNodes,
            info.displayId
        ) else null
        val screen = JSONObject().put("width", info.width).put("height", info.height)
            .put("display_id", info.displayId)
        val json = JSONObject().put("ok", true).put("tool", "observe_screen")
            .put("display_id", info.displayId)
            .put("screen", screen).put(
                "focus",
                JSONObject().put("package", service?.currentPackageName(info.displayId).orEmpty())
            )
            .put("observation_id", nodes?.id ?: JSONObject.NULL)
            .put("observation_source", if (nodes != null) "accessibility" else JSONObject.NULL)
            .put("window_id", nodes?.windowId ?: JSONObject.NULL)
            .put("ui_tree_truncated", nodes?.truncated ?: false)
            .put("node_limit", options.maxNodes.coerceIn(1, 120))
            .put("ui_nodes", JSONArray(nodes?.nodes.orEmpty().map(::nodeJson)))
            .put(
                "accessibility", JSONObject().put("available", service != null)
                    .put("note", "仅查询虚拟屏窗口；没有节点时请观察截图并使用坐标工具。")
            )
            .put(
                "coordinate_contract",
                JSONObject().put("default_coordinate_space", "screen").put("screen", screen)
                    .put("note", "screen 与截图均为虚拟屏原始像素；所有 GUI 工具均针对该 display。")
            )
        val capture = if (options.includeScreenshot) VirtualScreenSession.execute(
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
        require(
            args.optString("coordinate_space", "screen") in setOf(
                "",
                "screen",
                "screenshot"
            )
        ) { "INVALID_COORDINATE_SPACE" }
        fun point(x: String, y: String): Pair<Int, Int> {
            val px = integer(args, x);
            val py = integer(args, y)
            require(px in 0 until info.width && py in 0 until info.height) { "INVALID_COORDINATES" }
            return px to py
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
                        )
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
        val current = snapshot ?: error("NO_OBSERVATION")
        require(
            observedSession == info.sessionId && observedManualGeneration == info.manualInputGeneration &&
                    current.displayId == info.displayId && current.id == args.optString("observation_id")
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
            direction.gestureWithin(node.bounds)?.let { gesture ->
                VirtualScreenSession.showNodeGesture(
                    info, "swipe", gesture.start.x, gesture.start.y, 500,
                    gesture.end.x, gesture.end.y
                )
            }
            val result = service.scrollNode(nodes, index, direction)
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
        val service = AgentAccessibilityService.current() ?: error("ACCESSIBILITY_UNAVAILABLE")
        val replace = name in setOf(
            "replace_text",
            "clear_text"
        ) || (name == "input_text" && args.optString("mode") == "replace")
        val index = if (replace && args.has("index") && !args.isNull("index")) integer(
            args,
            "index"
        ) else null
        val nodes = if (index != null) requiredSnapshot(args, info) else null
        // Direct SET_TEXT supports Unicode without changing the user's global clipboard or IME.
        val result = if (replace) service.setTextNode(nodes, index, text, info.displayId)
        else service.inputTextFocused(text, info.displayId)
        return actionResult(result, info)
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
                    requireNotNull(service) { "ACCESSIBILITY_UNAVAILABLE" }.queryNodes(
                        120,
                        info.displayId
                    ).any { node ->
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

    private fun success(name: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", true).put("tool", name)
            .put("scope", "virtual_screen").toString()
    )

    private fun failure(code: String, message: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false)
            .put("code", code).put("message", message).toString(), sensitive = true
    )
}
