package fuck.andes.agent.device

import fuck.andes.agent.media.BreenoImageCodec
import fuck.andes.agent.model.BreenoModelClient
import fuck.andes.core.ModuleLogger

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
    private val logger: ModuleLogger
) {
    data class Observation(
        val content: String,
        val image: BreenoModelClient.ModelImage?,
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
        val scrollable: Boolean,
        val focused: Boolean,
        val enabled: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    fun observe(includeScreenshot: Boolean, includeUiTree: Boolean, maxNodes: Int): Observation {
        val display = screenSize()
        val focus = focusedWindow()
        val nodes = if (includeUiTree) dumpUiNodes(maxNodes.coerceIn(1, 120)) else emptyList()
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
        return inputCommand("input tap $x $y", "tap")
    }

    fun longPress(x: Int, y: Int, durationMs: Int): String {
        validatePoint(x, y)
        val duration = durationMs.coerceIn(300, 3_000)
        return inputCommand("input swipe $x $y $x $y $duration", "long_press")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        validatePoint(x1, y1)
        validatePoint(x2, y2)
        val duration = durationMs.coerceIn(100, 2_000)
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
        val clipped = text.take(200)
        if (clipped.isBlank()) return errorJson("INVALID_ARGUMENT", "text 不能为空")
        val encoded = clipped
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("'", "'\\''")
        return inputCommand("input text '$encoded'", "input_text")
    }

    fun pressKey(button: String): String {
        val keyCode = when (button.uppercase()) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER" -> 66
            "RECENTS" -> 187
            else -> return errorJson("INVALID_ARGUMENT", "button 仅支持 BACK/HOME/ENTER/RECENTS")
        }
        return inputCommand("input keyevent $keyCode", "press_key")
    }

    private fun captureScreenshot(): BreenoModelClient.ModelImage? {
        val result = runSuBytes("screencap -p", timeoutSeconds = 8)
        if (result.exitCode != 0 || result.output.isEmpty()) {
            logger.warn("Breeno root screenshot failed: exit=${result.exitCode}, ${result.stderr.take(160)}")
            return null
        }
        return BreenoImageCodec.fromBytes(result.output, source = "screen")
    }

    private fun dumpUiNodes(maxNodes: Int): List<UiNode> {
        val result = runSuText(
            "uiautomator dump --compressed /data/local/tmp/fuck_andes_window.xml >/dev/null && " +
                "cat /data/local/tmp/fuck_andes_window.xml && rm -f /data/local/tmp/fuck_andes_window.xml",
            timeoutSeconds = 10
        )
        if (result.exitCode != 0 || result.output.isBlank()) {
            logger.warn("Breeno root uiautomator failed: exit=${result.exitCode}, ${result.output.take(160)}")
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
                            scrollable = scrollable,
                            focused = focused,
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
            forEach { node ->
                array.put(
                    JSONObject()
                        .put("index", node.index)
                        .put("text", node.text)
                        .put("desc", node.desc)
                        .put("class", node.className)
                        .put("package", node.packageName)
                        .put("bounds", node.bounds.toShortString())
                        .put("center", JSONObject().put("x", node.centerX).put("y", node.centerY))
                        .put("clickable", node.clickable)
                        .put("scrollable", node.scrollable)
                        .put("focused", node.focused)
                        .put("enabled", node.enabled)
                )
            }
        }

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
        val outputThread = thread(name = "breeno-root-stdout") {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val stderrThread = if (text) null else {
            thread(name = "breeno-root-stderr") {
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
