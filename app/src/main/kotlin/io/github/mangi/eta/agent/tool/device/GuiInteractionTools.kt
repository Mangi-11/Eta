package io.github.mangi.eta.agent.tool.device

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.ui.GestureIndicator
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.tool.AgentAfterActionSummary
import io.github.mangi.eta.agent.tool.ObservationReferencePolicy
import io.github.mangi.eta.agent.tool.requiredText
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.core.AgentLogger
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

internal class ActiveDisplayUnavailableException : IllegalStateException(
    "当前虚拟屏已关闭，旧观察已失效；请重新启动目标应用后再观察和操作",
)

/**
 * 负责屏幕观察状态维护、坐标空间换算、GUI 触控/输入/剪贴板/等待动作以及动作后轻量 UI 树回读。
 */
internal class GuiInteractionTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val deviceController: RootShellDeviceController,
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )?,
    private val requireContext: () -> Context,
    private val activeDisplayId: () -> Int,
    private val setActiveDisplayId: (Int) -> Unit,
    private val isClosed: () -> Boolean,
    private val checkCancellation: () -> Unit,
) {

    private val publishedObservation = AtomicReference(PublishedObservation())

    fun clearObservation() {
        publishedObservation.set(PublishedObservation())
    }

    fun matchesGeneration(generation: Long): Boolean =
        publishedObservation.get().generation == generation

    fun observeScreen(args: JSONObject, generation: Long?): AgentModelClient.ToolResult {
        clearObservation()
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val displayId = currentActiveDisplayId()
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
                displayId = displayId,
            )
        publishObservation(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
                generation = generation.takeIf { JSONObject(observation.content).optBoolean("ok") },
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "display=$displayId observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image),
        )
    }

    /**
     * 成功的 GUI 动作附带一次轻量观察：只读 UI 树、不截图，节点数减半。
     * 模型据此确认动作生效并直接用新的 observation_id 继续操作，不必再单独 observe_screen；
     * 失败或结果未知的动作不附带，保持"先重新观察"的既有约束。
     */
    fun afterAction(raw: String): AgentModelClient.ToolResult {
        checkCancellation()
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) return AgentModelClient.ToolResult(raw)
        val before = publishedObservation.get().elements
        val after = runCatching {
            val displayId = currentActiveDisplayId()
            screenObservationProvider?.invoke(AFTER_ACTION_OPTIONS)
                ?: deviceController.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = AFTER_ACTION_OPTIONS.maxNodes,
                    displayId = displayId,
                )
        }.getOrElse { throwable ->
            clearObservation()
            checkCancellation()
            if (throwable is AgentRunCancelledException) throw throwable
            logger.debug { "Agent local tool after-action observation failed: type=${throwable.javaClass.simpleName}" }
            RootShellDeviceController.Observation(
                content = JSONObject()
                    .put("ok", false)
                    .put("ui_tree_status", "query_failed")
                    .put("code", if (throwable is ActiveDisplayUnavailableException) "DISPLAY_UNAVAILABLE" else "OBSERVATION_FAILED")
                    .put("ui_tree_note", "动作已执行，但后续观察失败；旧节点与坐标已失效，请先重新观察，不要重复动作")
                    .toString(),
                image = null,
                coordinateSpace = null,
                elementObservation = null,
            )
        }
        // 新快照取代动作前的快照：旧 index 在界面变化后本就不可靠，继续保留只会让模型误用。
        publishObservation(
            if (after.elementObservation == null) PublishedObservation()
            else publishedObservation.get().copy(elements = after.elementObservation, coordinateSpace = after.coordinateSpace),
        )
        result.put("after", AgentAfterActionSummary.build(before, after))
        return AgentModelClient.ToolResult(result.toString())
    }

    fun tap(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    fun tapArea(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val x1 = args.getInt("x1")
        val y1 = args.getInt("y1")
        val x2 = args.getInt("x2")
        val y2 = args.getInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace, displayId)
        val second = convertPoint(x2, y2, coordinateSpace, displayId)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    fun tapElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
            ?: return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.tapElement(observation, index)
        }
        if (result.isOkJson()) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(node.centerX, node.centerY, displayId)
        }
        return result
    }

    fun longPressElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.longPressElement(observation, index, durationMs)
        }
        if (result.isOkJson()) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            }
            showLongPress(node.centerX, node.centerY, durationMs, displayId)
        }
        return result
    }

    fun longPress(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 800)
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            }
            showLongPress(point.x, point.y, durationMs, displayId)
            deviceController.longPress(point.x, point.y, durationMs, displayId)
        }
    }

    fun swipe(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val start = convertPoint(
            x = args.getInt("x1"),
            y = args.getInt("y1"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val end = convertPoint(
            x = args.getInt("x2"),
            y = args.getInt("y2"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 500)
        return clearOverlay(displayId, start, end) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
            }
            showSwipe(start.x, start.y, end.x, end.y, durationMs, displayId)
            deviceController.swipe(
                start.x,
                start.y,
                end.x,
                end.y,
                durationMs,
                displayId,
            )
        }
    }

    fun scroll(args: JSONObject): String =
        deviceController.scroll(args.optString("direction"), args.optString("amount"), currentActiveDisplayId())

    fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction"),
            amount = args.optString("amount"),
        )
    }

    /**
     * 统一的文本输入：replace（默认）整体替换为 text，text 为空即清空；append 在光标处插入。
     * 指定 index 时先确认节点来自最近一次观察；不指定时作用于当前输入焦点。
     * submit=true 在写入成功后按输入法回车，用于搜索、发送等提交动作。
     */
    fun typeText(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val text = args.requiredText("text")
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        val written = when (mode) {
            "replace" -> replaceText(args)
            "append" -> {
                if (text.isEmpty()) return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
                if (args.optNullableInt("index") != null) {
                    return errorResult("INVALID_ARGUMENT", "append 只作用于当前输入焦点；要写入指定输入框请用 mode=replace")
                }
                // 长文本走选区插入加粘贴回退，比逐字键入更稳定。
                if (text.length > TYPE_TEXT_INCREMENTAL_CHARS) pasteText(args) else deviceController.inputText(text, displayId)
            }
            else -> return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
        }
        val json = runCatching { JSONObject(written) }.getOrNull() ?: return written
        json.put("tool", "type_text").put("mode", mode)
        if (!json.optBoolean("ok") || !args.optBoolean("submit")) return json.toString()
        val submitted = runCatching { JSONObject(deviceController.pressKey("ENTER", displayId)) }.getOrNull()
        json.put("submitted", submitted?.optBoolean("ok") == true)
        if (submitted?.optBoolean("ok") != true) {
            // 文本已写入但提交失败：整体仍算成功，避免模型重复输入；由 submitted=false 提示单独补按回车或点按钮。
            json.put("submit_error", submitted?.optString("code").orEmpty().ifBlank { "SUBMIT_FAILED" })
        }
        return json.toString()
    }

    fun inputText(args: JSONObject): String {
        val text = args.requiredText("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text, currentActiveDisplayId())
        }
    }

    fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.requiredText("text"),
            index = index,
            observation = observation,
            displayId = currentActiveDisplayId(),
        )
    }

    fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation, displayId = currentActiveDisplayId())
    }

    fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.requiredText("text"))

    fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.requiredText("text"), currentActiveDisplayId())

    fun pressKey(args: JSONObject): String =
        deviceController.pressKey(args.optString("button"), currentActiveDisplayId())

    fun waitMs(args: JSONObject): String =
        deviceController.waitMs(args.optInt("duration_ms", 1_000))

    fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.requiredText("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains"),
            displayId = currentActiveDisplayId(),
        )

    fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            displayId = currentActiveDisplayId(),
        )

    fun openSystemPanel(args: JSONObject): String {
        clearObservation()
        setActiveDisplayId(Display.DEFAULT_DISPLAY)
        return deviceController.openSystemPanel(args.optString("panel"))
    }

    private fun currentActiveDisplayId(): Int {
        val id = activeDisplayId()
        if (!CanvasRoutingPolicy.isVirtualDisplay(id)) return Display.DEFAULT_DISPLAY
        val vd = VirtualDisplayController.snapshots.value
        return if (vd.active && vd.displayId == id) {
            id
        } else {
            clearObservation()
            throw ActiveDisplayUnavailableException()
        }
    }

    private fun publishObservation(observation: PublishedObservation) {
        checkCancellation()
        publishedObservation.set(observation)
        if (isClosed() || Thread.currentThread().isInterrupted) {
            publishedObservation.compareAndSet(observation, PublishedObservation())
            throw AgentRunCancelledException()
        }
    }

    /**
     * 坐标系必须显式声明。旧版按"上一次观察是否带截图"隐式切换默认值，模型无从得知，
     * 经常把截图像素当成屏幕像素；缺省时直接报参数错误，让模型在下一步改正。
     */
    private fun convertPoint(
        x: Int,
        y: Int,
        coordinateSpace: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace.isBlank()) {
            throw InvalidToolArgumentException(
                "缺少 coordinate_space：看截图定位用 normalized（0–999），坐标来自 ui_nodes 用 screen",
            )
        }
        if (requestedSpace == "normalized") {
            if (x !in 0..NORMALIZED_MAX || y !in 0..NORMALIZED_MAX) {
                throw InvalidToolArgumentException("normalized 坐标必须在 0–$NORMALIZED_MAX 之间：($x,$y)")
            }
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            return ScreenPoint(
                x = (x.toLong() * (width - 1) / NORMALIZED_MAX).toInt(),
                y = (y.toLong() * (height - 1) / NORMALIZED_MAX).toInt(),
            )
        }
        if (requestedSpace == "screen") {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (requestedSpace != "screenshot") {
            throw InvalidToolArgumentException("coordinate_space 只能是 normalized、screen 或 screenshot")
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "最近一次观察没有附图，没有 screenshot 坐标系；看截图定位请用 normalized，或先带截图重新观察",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    /** 手势经过状态胶囊时让它暂时穿透并淡出；在虚拟屏内操作时不干扰物理屏胶囊。 */
    private fun <T> clearOverlay(displayId: Int, vararg points: ScreenPoint, gesture: () -> T): T {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            return gesture()
        }
        val area = Rect(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x } + 1,
            points.maxOf { it.y } + 1,
        )
        return AgentOverlayClearance.around(area, gesture)
    }

    private fun showTap(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitTapEffect(x, y, longPress = false)
        } else {
            GestureIndicator.showTap(context, x, y)
        }
    }

    private fun showLongPress(
        x: Int,
        y: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitTapEffect(x, y, longPress = true)
        } else {
            GestureIndicator.showLongPress(context, x, y, durationMs)
        }
    }

    private fun showSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitSwipeEffect(x1, y1, x2, y2, durationMs)
        } else {
            GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
        }
    }

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private data class ScreenPoint(val x: Int, val y: Int)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
        val generation: Long? = null,
    )
}

private val AFTER_ACTION_OPTIONS = AgentScreenObservationContract.Options(
    includeScreenshot = false,
    includeUiTree = true,
    maxNodes = 30,
)

/** append 超过该长度改走粘贴路径；逐字键入的增量重建对长文本既慢又容易被输入法打断。 */
private const val TYPE_TEXT_INCREMENTAL_CHARS = 200

/** 归一化坐标上界：0–999，与主流 GUI 模型的输出习惯一致。 */
private const val NORMALIZED_MAX = 999
