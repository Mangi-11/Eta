package fuck.andes.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThreadExecutor = Executor { command -> mainHandler.post(command) }
    private val windowChangeLock = ReentrantLock()
    private val windowChanged = windowChangeLock.newCondition()
    private var lastNodes: List<IndexedNode> = emptyList()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lastNodes = emptyList()
        signalWindowChanged()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> signalWindowChanged()
        }
    }

    override fun onInterrupt() = Unit

    fun observe(maxNodes: Int): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<IndexedNode>()
        collectNodes(root, nodes, maxNodes.coerceIn(1, 120))
        lastNodes = nodes
        return nodes.map { indexed -> indexed.toUiNode() }
    }

    fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun isPackageWindowVisible(packageName: String): Boolean =
        runOnMainSync {
            rootInActiveWindow?.packageName?.toString() == packageName ||
                windows.orEmpty().any { window ->
                    val root = window.root ?: return@any false
                    root.packageName?.toString() == packageName
                }
        } == true

    /**
     * BACK 只表示系统接收了退出动作；浮窗通常还会执行退出动画。
     * 等待目标包窗口真正消失并稳定两个采样周期，避免下一步截图抢在 removeView 之前执行。
     */
    fun awaitPackageWindowGone(
        packageName: String,
        timeoutMillis: Long = 1_000L,
        minimumWaitMillis: Long = 160L,
        stableMillis: Long = 80L,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return !isPackageWindowVisible(packageName)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceIn(200L, 2_000L)
        var absentSince = 0L
        do {
            val now = SystemClock.elapsedRealtime()
            if (isPackageWindowVisible(packageName)) {
                absentSince = 0L
            } else {
                if (absentSince == 0L) absentSince = now
                if (now - startedAt >= minimumWaitMillis && now - absentSince >= stableMillis) {
                    return true
                }
            }
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
                awaitWindowChanged(remainingMillis.coerceAtMost(WINDOW_POLL_FALLBACK_MS))
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return !isPackageWindowVisible(packageName)
    }

    private fun signalWindowChanged() {
        windowChangeLock.lock()
        try {
            windowChanged.signalAll()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun awaitWindowChanged(timeoutMillis: Long) {
        windowChangeLock.lock()
        try {
            windowChanged.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            windowChangeLock.unlock()
        }
    }

    fun clickNode(index: Int): Boolean {
        val node = nodeAt(index) ?: return false
        val actionable = node.firstParentOrSelf { it.isClickable }
        return if (actionable != null) {
            performNodeAction(actionable, AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val bounds = node.bounds()
            gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
    }

    fun longClickNode(index: Int): Boolean {
        val node = nodeAt(index) ?: return false
        val actionable = node.firstParentOrSelf { it.isLongClickable }
        return if (actionable != null) {
            performNodeAction(actionable, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } else {
            val bounds = node.bounds()
            gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), durationMs = 900)
        }
    }

    fun scrollNode(index: Int, forward: Boolean): Boolean {
        val node = nodeAt(index) ?: return false
        val actionable = node.firstParentOrSelf { it.isScrollable } ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return performNodeAction(actionable, action)
    }

    fun inputTextFocused(text: String, append: Boolean): Boolean {
        val node = findFocusedEditableNode() ?: findFirstEditableNode() ?: return false
        val content = if (append) node.text?.toString().orEmpty() + text else text
        return setNodeText(node, content)
    }

    fun setTextNode(index: Int?, text: String): Boolean {
        val node = index?.let { nodeAt(it) } ?: findFocusedEditableNode() ?: findFirstEditableNode()
        if (node == null || !node.isEditable) return false
        return setNodeText(node, text)
    }

    fun pasteFocused(): Boolean {
        val node = findFocusedEditableNode() ?: return false
        return performNodeAction(node, AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun imeEnter(): Boolean {
        val node = findFocusedEditableNode() ?: return false
        return performNodeAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): Boolean =
        dispatchGestureSync(
            Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            },
            durationMs.coerceIn(1, 3_000)
        )

    fun gestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        dispatchGestureSync(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(100, 3_000)
        )

    fun globalAction(name: String): Boolean {
        val action = when (name.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return runOnMainSync { performGlobalAction(action) } == true
    }

    fun copyToClipboard(text: String): Boolean =
        runOnMainSync {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("fuck_andes_agent", text))
            clipboard.hasPrimaryClip()
        } == true

    fun getClipboardText(): String? =
        runOnMainSync {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }

    fun statusJson(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("package", currentPackageName().orEmpty())

    /**
     * 截取当前屏幕，排除 TYPE_ACCESSIBILITY_OVERLAY 浮层（glow/orb/bubble/resultCard/GestureIndicator）。
     * 从 agent-runtime 子线程调用；takeScreenshotOfWindow 内部 post 到主线程，
     * callback 经 mainThreadExecutor 回主线程，latch 在子线程等待，不阻塞主线程。
     * 参考 OpenOmniBot OmniScreenshotAction.captureExcludingOverlaysV14。
     */
    fun captureScreenshotExcludingOverlays(
        excludedPackages: Set<String> = emptySet(),
    ): Bitmap? {
        val allWindows = windows ?: return null
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        val screenW = point.x
        val screenH = point.y

        // 过滤自己的无障碍 overlay 及入口浮窗包，保留应用窗口 + 系统 UI（状态栏等）。
        val windowPackages = allWindows.associate { window ->
            val packageName = window.root?.packageName?.toString()
            window.id to packageName
        }
        val validWindows = allWindows.filter { window ->
            window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                windowPackages[window.id] !in excludedPackages
        }
        if (validWindows.isEmpty()) return null

        val sorted = validWindows.sortedBy { it.layer }
        val latch = CountDownLatch(sorted.size)
        val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
        val lock = Any()
        var successCount = 0

        for (window in sorted) {
            val windowId = window.id
            runCatching {
                takeScreenshotOfWindow(windowId, mainThreadExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            screenshot.hardwareBuffer.use { hb ->
                                val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                                if (bmp != null) {
                                    val sw = convertToSoftwareBitmap(bmp)
                                    val bounds = Rect()
                                    window.getBoundsInScreen(bounds)
                                    synchronized(lock) {
                                        screenshots[windowId] = sw to bounds
                                        successCount++
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // 单个窗口截图失败不阻断整体
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // errorCode 6 = FLAG_SECURE 安全窗口，无法截图，正常跳过
                        latch.countDown()
                    }
                })
            }.onFailure {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        if (successCount == 0) {
            sorted.forEach { runCatching { it.recycle() } }
            return null
        }
        // merge 必须在 recycle 之前：merge 内用 window.id 查 screenshots，
        // recycle 后 window.id 失效会查不到 → 合成全黑
        val merged = mergeScreenshots(screenshots, sorted, screenW, screenH)
        AndroidAgentLogger.debug {
            "Agent accessibility action=capture_screenshot outcome=merged " +
                "allWindows=${allWindows.size} validWindows=${validWindows.size} " +
                "excludedPackages=${excludedPackages.size} " +
                "success=$successCount screenshots=${screenshots.size} " +
                "screen=${screenW}x${screenH} merged=${merged?.width}x${merged?.height}"
        }
        sorted.forEach { runCatching { it.recycle() } }
        return merged
    }

    private fun convertToSoftwareBitmap(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<AccessibilityWindowInfo>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        return try {
            val merged = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(merged)
            canvas.drawColor(Color.BLACK)
            for (window in sortedWindows) {
                val pair = screenshots[window.id] ?: continue
                val (bmp, bounds) = pair
                if (bmp.isRecycled) continue
                runCatching {
                    // 把窗口 bitmap 缩放到其 bounds 尺寸绘制，处理 takeScreenshotOfWindow
                    // 返回尺寸与 bounds 不一致（逻辑像素 vs 物理像素）的情况
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, RectF(bounds), null)
                }
            }
            merged
        } catch (e: Exception) {
            null
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return performNodeAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun nodeAt(index: Int): AccessibilityNodeInfo? =
        lastNodes.firstOrNull { it.index == index }?.node

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNode(root) { it.isEditable && (it.isFocused || it.isAccessibilityFocused) }
    }

    private fun findFirstEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNode(root) { it.isEditable && it.isEnabled }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNode(child, predicate)
            if (match != null) return match
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<IndexedNode>,
        maxNodes: Int
    ) {
        if (out.size >= maxNodes) return
        val bounds = node.bounds()
        val text = node.text?.toString().orEmpty().take(120)
        val desc = node.contentDescription?.toString().orEmpty().take(120)
        val useful = text.isNotBlank() ||
            desc.isNotBlank() ||
            node.isClickable ||
            node.isLongClickable ||
            node.isScrollable ||
            node.isFocused ||
            node.isEditable
        if (node.isVisibleToUser && bounds.width() > 2 && bounds.height() > 2 && useful) {
            out += IndexedNode(
                index = out.size,
                node = node,
                text = text,
                desc = desc,
                className = node.className?.toString().orEmpty(),
                packageName = node.packageName?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                bounds = Rect(bounds),
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                focused = node.isFocused,
                editable = node.isEditable,
                enabled = node.isEnabled
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, maxNodes)
            if (out.size >= maxNodes) return
        }
    }

    private fun AccessibilityNodeInfo.bounds(): Rect =
        Rect().also { getBoundsInScreen(it) }

    private fun AccessibilityNodeInfo.firstParentOrSelf(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        while (current != null) {
            if (predicate(current)) return current
            current = current.parent
        }
        return null
    }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null
    ): Boolean =
        runOnMainSync {
            if (args == null) node.performAction(action) else node.performAction(action, args)
        } == true

    private fun dispatchGestureSync(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        mainHandler.post {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed = true
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed = false
                        latch.countDown()
                    }
                },
                null
            )
            if (!dispatched) {
                completed = false
                latch.countDown()
            }
        }
        latch.await(durationMs + 1_500, TimeUnit.MILLISECONDS)
        return completed
    }

    private fun <T> runOnMainSync(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrNull()
        }
        val latch = CountDownLatch(1)
        var value: T? = null
        mainHandler.post {
            value = runCatching(block).getOrNull()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return value
    }

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val enabled: Boolean
    )

    private data class IndexedNode(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val enabled: Boolean
    ) {
        fun toUiNode(): UiNode =
            UiNode(
                index = index,
                text = text,
                desc = desc,
                className = className,
                packageName = packageName,
                viewId = viewId,
                bounds = bounds,
                clickable = clickable,
                longClickable = longClickable,
                scrollable = scrollable,
                focused = focused,
                editable = editable,
                enabled = enabled
            )
    }

    companion object {
        private const val WINDOW_POLL_FALLBACK_MS = 80L

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }
}
