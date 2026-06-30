package fuck.andes.agent.device

import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AgentLogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal class RootShellDeviceController(
    private val logger: AgentLogger
) {
    data class Observation(
        val content: String,
        val image: AgentModelClient.ModelImage?,
        val nodes: List<UiNode>,
        val coordinateSpace: CoordinateSpace?
    )

    data class CoordinateSpace(
        val screenWidth: Int,
        val screenHeight: Int,
        val screenshotWidth: Int,
        val screenshotHeight: Int
    ) {
        fun fromScreenshot(x: Int, y: Int): ScreenPoint {
            require(x in 0 until screenshotWidth && y in 0 until screenshotHeight) {
                "截图坐标超出范围：($x,$y) not in ${screenshotWidth}x$screenshotHeight"
            }
            return ScreenPoint(
                x = (x.toFloat() * screenWidth / screenshotWidth).toInt(),
                y = (y.toFloat() * screenHeight / screenshotHeight).toInt()
            )
        }

        fun summary(): String =
            "screen=${screenWidth}x$screenHeight,screenshot=${screenshotWidth}x$screenshotHeight"
    }

    data class ScreenPoint(val x: Int, val y: Int)

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val enabled: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    fun observe(includeScreenshot: Boolean, includeUiTree: Boolean, maxNodes: Int): Observation {
        val display = screenSize()
        val focus = focusedWindow()
        val accessibility = AgentAccessibilityService.current()
        val nodes = if (includeUiTree) {
            accessibility?.observe(maxNodes.coerceIn(1, 120))?.map { it.toUiNode() }
                ?.takeIf { it.isNotEmpty() }
                ?: dumpUiNodes(maxNodes.coerceIn(1, 120))
        } else {
            emptyList()
        }
        val image = if (includeScreenshot) captureScreenshot() else null
        val coordinateSpace = if (image?.width != null && image.height != null) {
            CoordinateSpace(
                screenWidth = display.first,
                screenHeight = display.second,
                screenshotWidth = image.width,
                screenshotHeight = image.height
            )
        } else {
            null
        }
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("screen", JSONObject().put("width", display.first).put("height", display.second))
            .put(
                "accessibility",
                JSONObject()
                    .put("available", accessibility != null)
                    .put("package", accessibility?.currentPackageName().orEmpty())
                    .put(
                        "note",
                        if (accessibility != null) {
                            "节点来自无障碍服务，支持 tap_element、replace_text、clear_text、scroll_element 等稳定节点动作"
                        } else {
                            "无障碍服务未启用，节点来自 uiautomator；坐标工具会回退到 Root Shell"
                        }
                    )
            )
            .put(
                "coordinate_contract",
                if (coordinateSpace == null) {
                    JSONObject()
                        .put("default_coordinate_space", "screen")
                        .put("note", "未附加截图，坐标工具使用真实设备屏幕坐标")
                } else {
                    JSONObject()
                        .put("default_coordinate_space", "screenshot")
                        .put(
                            "screenshot",
                            JSONObject()
                                .put("width", coordinateSpace.screenshotWidth)
                                .put("height", coordinateSpace.screenshotHeight)
                        )
                        .put(
                            "screen",
                            JSONObject()
                                .put("width", coordinateSpace.screenWidth)
                                .put("height", coordinateSpace.screenHeight)
                        )
                        .put(
                            "scale_to_screen",
                            JSONObject()
                                .put("x", coordinateSpace.screenWidth.toDouble() / coordinateSpace.screenshotWidth)
                                .put("y", coordinateSpace.screenHeight.toDouble() / coordinateSpace.screenshotHeight)
                        )
                        .put("note", "tap、tap_area、long_press、swipe 默认接收截图像素坐标；ui_nodes.center 是 screen 坐标")
                }
            )
            .put("focus", focus)
            .put("ui_nodes", nodes.toJsonArray())
            .put(
                "screenshot",
                if (image == null) {
                    JSONObject().put("attached", false)
                } else {
                    JSONObject()
                        .put("attached", true)
                        .put("mime_type", image.mimeType)
                        .put("bytes", image.bytes)
                        .put("width", image.width)
                        .put("height", image.height)
                }
            )
        return Observation(json.toString(), image, nodes, coordinateSpace)
    }

    fun tap(x: Int, y: Int): String {
        validatePoint(x, y)
        AgentAccessibilityService.current()?.let { service ->
            if (service.gestureTap(x.toFloat(), y.toFloat())) {
                waitForUiSettle("tap")
                return okJson("tap", "accessibility")
            }
        }
        return inputCommand("input tap $x $y", "tap")
    }

    fun longPress(x: Int, y: Int, durationMs: Int): String {
        validatePoint(x, y)
        val duration = durationMs.coerceIn(300, 3_000)
        AgentAccessibilityService.current()?.let { service ->
            if (service.gestureTap(x.toFloat(), y.toFloat(), duration.toLong())) {
                waitForUiSettle("long_press")
                return okJson("long_press", "accessibility")
            }
        }
        return inputCommand("input swipe $x $y $x $y $duration", "long_press")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        validatePoint(x1, y1)
        validatePoint(x2, y2)
        val duration = durationMs.coerceIn(100, 2_000)
        AgentAccessibilityService.current()?.let { service ->
            if (service.gestureSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())) {
                waitForUiSettle("swipe")
                return okJson("swipe", "accessibility")
            }
        }
        return inputCommand("input swipe $x1 $y1 $x2 $y2 $duration", "swipe")
    }

    fun scroll(direction: String): String {
        val (width, height) = screenSize()
        val cx = width / 2
        val cy = height / 2
        val horizontal = (width * 0.32f).toInt()
        val vertical = (height * 0.28f).toInt()
        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy + vertical, cx, cy - vertical, 450)
            "down" -> swipe(cx, cy - vertical, cx, cy + vertical, 450)
            "left" -> swipe(cx + horizontal, cy, cx - horizontal, cy, 450)
            "right" -> swipe(cx - horizontal, cy, cx + horizontal, cy, 450)
            else -> errorJson("INVALID_ARGUMENT", "direction 仅支持 up/down/left/right")
        }
    }

    fun inputText(text: String): String {
        val clipped = text.take(1_000)
        if (clipped.isBlank()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        AgentAccessibilityService.current()?.let { service ->
            if (service.inputTextFocused(clipped, append = true)) {
                waitForUiSettle("input_text")
                return okJson("input_text", "accessibility")
            }
        }
        val encoded = clipped
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("'", "'\\''")
        return inputCommand("input text '$encoded'", "input_text")
    }

    fun replaceText(text: String, index: Int?): String {
        val clipped = text.take(4_000)
        AgentAccessibilityService.current()?.let { service ->
            if (service.setTextNode(index, clipped)) {
                waitForUiSettle("replace_text")
                return okJson("replace_text", "accessibility")
            }
            return errorJson("ACCESSIBILITY_ACTION_FAILED", "未找到可编辑节点或文本替换失败")
        }
        return errorJson("ACCESSIBILITY_UNAVAILABLE", "replace_text 需要先启用 FuckAndes Agent 增强无障碍服务")
    }

    fun clearText(index: Int?): String =
        replaceText("", index).let { result ->
            val json = JSONObject(result)
            if (json.optBoolean("ok")) json.put("tool", "clear_text").toString() else result
        }

    fun tapElement(index: Int): String {
        AgentAccessibilityService.current()?.let { service ->
            if (service.clickNode(index)) {
                waitForUiSettle("tap")
                return okJson("tap_element", "accessibility")
            }
        }
        return errorJson("ACCESSIBILITY_ACTION_FAILED", "无障碍点击节点失败")
    }

    fun longPressElement(index: Int): String {
        AgentAccessibilityService.current()?.let { service ->
            if (service.longClickNode(index)) {
                waitForUiSettle("long_press")
                return okJson("long_press_element", "accessibility")
            }
        }
        return errorJson("ACCESSIBILITY_ACTION_FAILED", "无障碍长按节点失败")
    }

    fun scrollElement(index: Int, direction: String): String {
        val normalized = direction.lowercase()
        val forward = when (normalized) {
            "down", "right", "forward" -> true
            "up", "left", "backward" -> false
            else -> return errorJson("INVALID_ARGUMENT", "direction 仅支持 up/down/left/right/forward/backward")
        }
        AgentAccessibilityService.current()?.let { service ->
            if (service.scrollNode(index, forward)) {
                waitForUiSettle("swipe")
                return okJson("scroll_element", "accessibility")
            }
        }
        return errorJson("ACCESSIBILITY_ACTION_FAILED", "无障碍滚动节点失败")
    }

    fun pressKey(button: String): String {
        val normalized = button.uppercase()
        AgentAccessibilityService.current()?.let { service ->
            when (normalized) {
                "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS" -> {
                    if (service.globalAction(normalized)) {
                        waitForUiSettle("press_key")
                        return okJson("press_key", "accessibility").let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                }
                "ENTER" -> {
                    if (service.imeEnter()) {
                        waitForUiSettle("press_key")
                        return okJson("press_key", "accessibility").let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                }
            }
        }
        val keyCode = when (normalized) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER" -> 66
            "RECENTS" -> 187
            "PASTE" -> 279
            else -> return errorJson("INVALID_ARGUMENT", "button 仅支持 BACK/HOME/ENTER/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS")
        }
        return inputCommand("input keyevent $keyCode", "press_key")
    }

    fun waitMs(durationMs: Int): String {
        val duration = durationMs.coerceIn(100, 30_000)
        Thread.sleep(duration.toLong())
        return JSONObject()
            .put("ok", true)
            .put("tool", "wait")
            .put("duration_ms", duration)
            .toString()
    }

    fun waitForText(text: String, timeoutMs: Int, includeDesc: Boolean, matchMode: String): String {
        val needle = text.trim()
        if (needle.isBlank()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val nodes = AgentAccessibilityService.current()
                ?.observe(120)
                ?.map { it.toUiNode() }
                ?.takeIf { it.isNotEmpty() }
                ?: dumpUiNodes(120)
            val match = nodes.firstOrNull { node ->
                val haystacks = if (includeDesc) listOf(node.text, node.desc) else listOf(node.text)
                haystacks.any { value -> matches(value, needle, matchMode) }
            }
            if (match != null) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_text")
                    .put("attempts", attempts)
                    .put("matched_node", match.toJson())
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_text")
            .put("code", "TIMEOUT")
            .put("message", "等待文本超时：$needle")
            .put("attempts", attempts)
            .toString()
    }

    fun waitForPackage(packageName: String, timeoutMs: Int): String {
        val target = packageName.trim()
        if (target.isBlank()) return errorJson("INVALID_ARGUMENT", "package_name 不能为空")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        var lastPackage = ""
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            lastPackage = AgentAccessibilityService.current()?.currentPackageName().orEmpty()
            if (lastPackage == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .toString()
            }
            val focus = focusedWindow()
            if (focus.optString("component").contains(target)) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .put("focus", focus)
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_package")
            .put("code", "TIMEOUT")
            .put("message", "等待应用前台超时：$target")
            .put("last_package", lastPackage)
            .put("attempts", attempts)
            .toString()
    }

    fun clipboardSet(context: Context, text: String): String {
        val clipped = text.take(20_000)
        val ok = AgentAccessibilityService.current()?.copyToClipboard(clipped) ?: runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("fuck_andes_agent", clipped))
            clipboard.hasPrimaryClip()
        }.getOrDefault(false)
        return JSONObject()
            .put("ok", ok)
            .put("tool", "set_clipboard")
            .put("chars", clipped.length)
            .toString()
    }

    fun clipboardGet(context: Context): String {
        val text = AgentAccessibilityService.current()?.getClipboardText() ?: runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull().orEmpty()
        return JSONObject()
            .put("ok", text.isNotBlank())
            .put("tool", "get_clipboard")
            .put("text", text.take(8_000))
            .put("truncated", text.length > 8_000)
            .toString()
    }

    fun pasteText(context: Context, text: String): String {
        val setResult = JSONObject(clipboardSet(context, text))
        if (!setResult.optBoolean("ok")) return setResult.put("tool", "paste_text").toString()
        AgentAccessibilityService.current()?.let { service ->
            if (service.pasteFocused()) {
                waitForUiSettle("input_text")
                return okJson("paste_text", "accessibility")
            }
        }
        return inputCommand("input keyevent 279", "paste_text")
    }

    fun openSystemPanel(panel: String): String {
        val normalized = panel.lowercase()
        val accessibilityAction = when (normalized) {
            "notifications", "notification" -> "NOTIFICATIONS"
            "quick_settings", "quicksettings", "settings" -> "QUICK_SETTINGS"
            else -> return errorJson("INVALID_ARGUMENT", "panel 仅支持 notifications/quick_settings")
        }
        AgentAccessibilityService.current()?.let { service ->
            if (service.globalAction(accessibilityAction)) {
                waitForUiSettle("press_key")
                return okJson("open_system_panel", "accessibility").let {
                    JSONObject(it).put("panel", normalized).toString()
                }
            }
        }
        val command = when (accessibilityAction) {
            "NOTIFICATIONS" -> "cmd statusbar expand-notifications"
            else -> "cmd statusbar expand-settings"
        }
        return inputCommand(command, "open_system_panel")
    }

    private fun captureScreenshot(): AgentModelClient.ModelImage? {
        val result = runSuBytes("screencap -p", timeoutSeconds = 8)
        if (result.exitCode != 0 || result.output.isEmpty()) {
            logger.warn("Agent root screenshot failed: exit=${result.exitCode}, ${result.stderr.take(160)}")
            return null
        }
        return AgentImageCodec.fromBytes(result.output, source = "screen")
    }

    private fun dumpUiNodes(maxNodes: Int): List<UiNode> {
        val result = runSuText(
            "uiautomator dump --compressed /data/local/tmp/fuck_andes_window.xml >/dev/null && " +
                "cat /data/local/tmp/fuck_andes_window.xml && rm -f /data/local/tmp/fuck_andes_window.xml",
            timeoutSeconds = 10
        )
        if (result.exitCode != 0 || result.output.isBlank()) {
            logger.warn("Agent root uiautomator failed: exit=${result.exitCode}, ${result.output.take(160)}")
            return emptyList()
        }
        return parseUiNodes(result.output, maxNodes)
    }

    private fun parseUiNodes(xml: String, maxNodes: Int): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && nodes.size < maxNodes) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val visible = parser.attr("visible-to-user") != "false"
                val bounds = parser.attr("bounds").toRectOrNull()
                if (visible && bounds != null && bounds.width() > 2 && bounds.height() > 2) {
                    val text = parser.attr("text").take(120)
                    val desc = parser.attr("content-desc").take(120)
                    val clickable = parser.attr("clickable").toBoolean()
                    val scrollable = parser.attr("scrollable").toBoolean()
                    val focused = parser.attr("focused").toBoolean()
                    val enabled = parser.attr("enabled") != "false"
                    if (text.isNotBlank() || desc.isNotBlank() || clickable || scrollable || focused) {
                        nodes += UiNode(
                            index = nodes.size,
                            text = text,
                            desc = desc,
                            className = parser.attr("class"),
                            packageName = parser.attr("package"),
                            bounds = bounds,
                            clickable = clickable,
                            longClickable = parser.attr("long-clickable").toBoolean(),
                            scrollable = scrollable,
                            focused = focused,
                            editable = parser.attr("class").contains("EditText", ignoreCase = true),
                            enabled = enabled
                        )
                    }
                }
            }
            event = parser.next()
        }
        return nodes
    }

    private fun focusedWindow(): JSONObject {
        val result = runSuText("dumpsys window", timeoutSeconds = 8)
        val focusLine = result.output.lineSequence().firstOrNull {
            it.contains("mCurrentFocus=") || it.contains("mFocusedApp=")
        }.orEmpty().trim()
        val component = focusLine.substringAfter(" ", "").substringBefore("}").trim()
        return JSONObject()
            .put("raw", focusLine.take(240))
            .put("component", component)
    }

    private fun screenSize(): Pair<Int, Int> {
        val result = runSuText("wm size", timeoutSeconds = 5)
        val match = Regex("""Physical size:\s*(\d+)x(\d+)""").find(result.output)
        require(match != null) { "无法读取屏幕尺寸：${result.output.take(160)}" }
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun inputCommand(command: String, tool: String): String {
        val result = runSuText(command, timeoutSeconds = 8)
        return if (result.exitCode == 0) {
            waitForUiSettle(tool)
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .toString()
        } else {
            errorJson("COMMAND_FAILED", result.output.ifBlank { "exit=${result.exitCode}" })
        }
    }

    private fun waitForUiSettle(tool: String) {
        val delayMs = when (tool) {
            "tap", "long_press", "press_key" -> 350L
            "swipe" -> 650L
            "input_text" -> 500L
            else -> 250L
        }
        Thread.sleep(delayMs)
    }

    private fun validatePoint(x: Int, y: Int) {
        val (width, height) = screenSize()
        require(x in 0 until width && y in 0 until height) {
            "坐标超出屏幕范围：($x,$y) not in ${width}x$height"
        }
    }

    private fun List<UiNode>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { node -> array.put(node.toJson()) }
        }

    private fun UiNode.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("desc", desc)
            .put("class", className)
            .put("package", packageName)
            .put("bounds", bounds.toShortString())
            .put("center", JSONObject().put("x", centerX).put("y", centerY))
            .put("clickable", clickable)
            .put("long_clickable", longClickable)
            .put("scrollable", scrollable)
            .put("focused", focused)
            .put("editable", editable)
            .put("enabled", enabled)

    private fun XmlPullParser.attr(name: String): String =
        getAttributeValue(null, name).orEmpty()

    private fun String.toRectOrNull(): Rect? {
        val match = Regex("""\[(\-?\d+),(\-?\d+)]\[(\-?\d+),(\-?\d+)]""").find(this) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(timeoutSeconds, text = true, "su", "-c", command)
        return ShellTextResult(result.exitCode, result.output.decodeToString().trim())
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        val result = runProcess(timeoutSeconds, text = false, "su", "-c", command)
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString())
    }

    private fun runProcess(
        timeoutSeconds: Long,
        text: Boolean,
        vararg command: String
    ): ProcessBytesResult {
        val process = runCatching {
            ProcessBuilder(*command)
                .redirectErrorStream(text)
                .start()
        }.getOrElse {
            return ProcessBytesResult(-1, ByteArray(0), it.message.orEmpty().toByteArray())
        }

        val output = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val outputThread = thread(name = "agent-root-stdout") {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val stderrThread = if (text) null else {
            thread(name = "agent-root-stderr") {
                process.errorStream.use { input -> stderr.readFrom(input) }
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outputThread.join(500)
            stderrThread?.join(500)
            return ProcessBytesResult(-2, output.bytes(), "命令执行超时".toByteArray())
        }

        outputThread.join(500)
        stderrThread?.join(500)
        return ProcessBytesResult(process.exitValue(), output.bytes(), stderr.bytes())
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(240))
            .toString()

    private fun okJson(tool: String, executor: String): String =
        JSONObject()
            .put("ok", true)
            .put("tool", tool)
            .put("executor", executor)
            .toString()

    private fun matches(value: String, needle: String, matchMode: String): Boolean =
        when (matchMode.lowercase()) {
            "exact" -> value == needle
            "prefix" -> value.startsWith(needle)
            "regex" -> runCatching { Regex(needle).containsMatchIn(value) }.getOrDefault(false)
            else -> value.contains(needle, ignoreCase = true)
        }

    private fun AgentAccessibilityService.UiNode.toUiNode(): UiNode =
        UiNode(
            index = index,
            text = text,
            desc = desc,
            className = className,
            packageName = packageName,
            bounds = bounds,
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            focused = focused,
            editable = editable,
            enabled = enabled
        )

    private data class ShellTextResult(val exitCode: Int, val output: String)
    private data class ShellBytesResult(val exitCode: Int, val output: ByteArray, val stderr: String)
    private data class ProcessBytesResult(val exitCode: Int, val output: ByteArray, val stderr: ByteArray)

    private class ByteArrayOutputCollector {
        private val output = java.io.ByteArrayOutputStream()

        fun readFrom(input: java.io.InputStream) {
            runCatching {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }.onFailure { throwable ->
                if (throwable !is IOException) throw throwable
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
