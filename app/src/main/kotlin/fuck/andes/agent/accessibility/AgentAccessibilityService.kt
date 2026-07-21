package fuck.andes.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import fuck.andes.agent.device.ScrollAxis
import fuck.andes.agent.device.ScrollAxisContract
import fuck.andes.agent.device.ScrollDirection
import fuck.andes.agent.device.ScrollEvidence
import fuck.andes.agent.device.ScrollEvidenceContract
import fuck.andes.agent.device.ScrollMovementSource
import fuck.andes.agent.device.RootScrollMotionContract
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private data class ScreenshotWindow(
        val id: Int,
        val layer: Int,
        val type: Int,
        val bounds: Rect,
        val active: Boolean,
        val focused: Boolean,
    )

    private data class NodeTraversalState(
        val maxVisitedNodes: Int,
        val activePath: MutableSet<AccessibilityNodeInfo> = hashSetOf(),
        var visitedNodes: Int = 0,
        var truncated: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = SCREENSHOT_EXECUTOR
    private val windowChangeLock = ReentrantLock()
    private val windowChanged = windowChangeLock.newCondition()
    private val scrollEventLock = ReentrantLock()
    private val scrollEventArrived = scrollEventLock.newCondition()
    private val scrollActionLock = ReentrantLock()
    private var scrollEventSequence = 0L
    private val recentScrollSignals = ArrayDeque<ScrollSignal>()
    private val windowContentGenerations = mutableMapOf<Int, Long>()
    private val serviceToken = SERVICE_TOKENS.incrementAndGet()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCurrentInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCurrentInstance()
        super.onDestroy()
    }

    private fun clearCurrentInstance() {
        if (instance === this) instance = null
        signalWindowChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pruneWindowContentGenerations()
                signalWindowChanged()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                bumpWindowContentGeneration(event.windowId)
                recordScrollEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                bumpWindowContentGeneration(event.windowId)
        }
    }

    override fun onInterrupt() = Unit

    /**
     * 一次观察与其节点句柄组成不可变快照。调用方必须把同一实例传回节点动作，
     * 避免其他运行或 wait_for_text 的临时观察改写 index 含义。
     */
    fun captureNodeSnapshot(maxNodes: Int): NodeSnapshot? = runOnMainSync {
        val startedAt = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return@runOnMainSync null
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val indexedNodes = mutableListOf<IndexedNode>()
        val traversal = NodeTraversalState(
            maxVisitedNodes = (nodeLimit * UI_TREE_VISIT_MULTIPLIER)
                .coerceIn(MIN_UI_TREE_VISITED_NODES, MAX_UI_TREE_VISITED_NODES),
        )
        collectNodes(
            node = root,
            out = indexedNodes,
            maxNodes = nodeLimit,
            depth = 0,
            traversal = traversal,
        )
        NodeSnapshot(
            id = "o${SNAPSHOT_IDS.incrementAndGet()}",
            serviceToken = serviceToken,
            packageName = root.packageName?.toString().orEmpty(),
            windowId = root.windowId,
            contentGeneration = windowContentGeneration(root.windowId),
            capturedAtElapsedMs = startedAt,
            truncated = traversal.truncated,
            indexedNodes = indexedNodes.toList(),
        ).also { snapshot ->
            AndroidAgentLogger.debug {
                "Agent accessibility action=observe_tree observation=${snapshot.id} " +
                    "nodes=${snapshot.nodes.size} visited=${traversal.visitedNodes} " +
                    "truncated=${traversal.truncated} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
            }
        }
    }

    /** 临时查询不发布任何全局节点状态，适用于 wait_for_text。 */
    fun queryNodes(maxNodes: Int): List<UiNode> =
        captureNodeSnapshot(maxNodes)?.nodes.orEmpty()

    fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun displaySize(): Pair<Int, Int>? = runCatching {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        if (point.x > 0 && point.y > 0) point.x to point.y else null
    }.getOrNull()

    internal fun packageWindowVisibility(packageName: String): PackageWindowVisibility =
        runOnMainSync {
            val activeRoot = rootInActiveWindow
            if (activeRoot?.packageName?.toString() == packageName) {
                return@runOnMainSync PackageWindowVisibility.VISIBLE
            }
            var inspectedRoot = activeRoot != null
            var hasUnknownRelevantWindow = false
            for (window in windows.orEmpty()) {
                val root = window.root
                if (root == null) {
                    if (
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        window.isActive ||
                        window.isFocused
                    ) {
                        hasUnknownRelevantWindow = true
                    }
                    continue
                }
                inspectedRoot = true
                if (root.packageName?.toString() == packageName) {
                    return@runOnMainSync PackageWindowVisibility.VISIBLE
                }
            }
            if (inspectedRoot && !hasUnknownRelevantWindow) {
                PackageWindowVisibility.GONE
            } else {
                PackageWindowVisibility.UNKNOWN
            }
        } ?: PackageWindowVisibility.UNKNOWN

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
            return false
        }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceIn(200L, 2_000L)
        var absentSince = 0L
        do {
            val now = SystemClock.elapsedRealtime()
            when (packageWindowVisibility(packageName)) {
                PackageWindowVisibility.VISIBLE,
                PackageWindowVisibility.UNKNOWN -> absentSince = 0L
                PackageWindowVisibility.GONE -> {
                    if (absentSince == 0L) absentSince = now
                    if (
                        now - startedAt >= minimumWaitMillis &&
                        now - absentSince >= stableMillis
                    ) {
                        return true
                    }
                }
            }
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
                awaitWindowChanged(remainingMillis.coerceAtMost(WINDOW_POLL_FALLBACK_MS))
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
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

    private fun recordScrollEvent(event: AccessibilityEvent) {
        val source = runCatching { event.source }.getOrNull()
        val sourceBounds = source?.bounds() ?: Rect()
        val signal = ScrollSignal(
            sequence = 0L,
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            deltaX = event.scrollDeltaX,
            deltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            sourceUniqueId = source?.uniqueId.orEmpty(),
            sourceViewId = source?.viewIdResourceName.orEmpty(),
            sourceClassName = source?.className?.toString().orEmpty(),
            sourceBounds = sourceBounds,
        )
        scrollEventLock.lock()
        try {
            scrollEventSequence++
            recentScrollSignals.addLast(signal.copy(sequence = scrollEventSequence))
            while (recentScrollSignals.size > MAX_SCROLL_SIGNALS) {
                recentScrollSignals.removeFirst()
            }
            scrollEventArrived.signalAll()
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun bumpWindowContentGeneration(windowId: Int) {
        if (windowId < 0) return
        windowContentGenerations[windowId] = windowContentGeneration(windowId) + 1L
    }

    private fun windowContentGeneration(windowId: Int): Long =
        windowContentGenerations[windowId] ?: 0L

    private fun pruneWindowContentGenerations() {
        if (windowContentGenerations.isEmpty()) return
        val liveWindowIds = windows.orEmpty().mapTo(hashSetOf()) { window -> window.id }
        windowContentGenerations.keys.retainAll(liveWindowIds)
    }

    private fun currentScrollEventSequence(): Long {
        scrollEventLock.lock()
        return try {
            scrollEventSequence
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun awaitScrollSignal(
        afterSequence: Long,
        packageName: String,
        windowId: Int,
        targetIdentity: ScrollTargetIdentity,
        timeoutMillis: Long,
    ): ScrollSignal? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        scrollEventLock.lock()
        try {
            while (true) {
                val signal = recentScrollSignals.firstOrNull { candidate ->
                    candidate.sequence > afterSequence &&
                        candidate.windowId == windowId &&
                        candidate.packageName == packageName &&
                        candidate.matchesTarget(targetIdentity)
                }
                if (
                    signal != null
                ) {
                    return signal
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                try {
                    scrollEventArrived.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            scrollEventLock.unlock()
        }
    }

    fun clickNode(snapshot: NodeSnapshot, index: Int): NodeActionResult =
        withValidatedIndexedNode(snapshot, index) { indexed ->
            val node = indexed.node
            val actionable = indexed.clickTarget?.resolveFor(node)
            if (indexed.clickTarget != null && actionable == null) {
                NodeActionResult.failure(
                    "STALE_ACTION_TARGET",
                    "节点的可点击目标已经变化，请重新观察屏幕",
                )
            } else if (actionable != null) {
                when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_CLICK)) {
                    ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_CLICK")
                    ActionDispatch.REJECTED ->
                        NodeActionResult.failure("ACTION_FAILED", "目标节点拒绝点击动作")
                    ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
                }
            } else {
                val bounds = clippedNodeBounds(node)
                if (bounds.isEmpty) {
                    NodeActionResult.failure("INVALID_NODE_BOUNDS", "目标节点没有可点击区域")
                } else gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }
        }

    fun longClickNode(
        snapshot: NodeSnapshot,
        index: Int,
        durationMs: Long,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        val node = indexed.node
        val actionable = indexed.longClickTarget?.resolveFor(node)
        if (indexed.longClickTarget != null && actionable == null) {
            NodeActionResult.failure(
                "STALE_ACTION_TARGET",
                "节点的可长按目标已经变化，请重新观察屏幕",
            )
        } else if (actionable != null) {
            when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_LONG_CLICK")
                ActionDispatch.REJECTED ->
                    NodeActionResult.failure("ACTION_FAILED", "目标节点拒绝长按动作")
                ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            }
        } else {
            val bounds = clippedNodeBounds(node)
            if (bounds.isEmpty) {
                NodeActionResult.failure("INVALID_NODE_BOUNDS", "目标节点没有可长按区域")
            } else {
                gestureTap(
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    durationMs = durationMs.coerceIn(300L, 3_000L),
                ).let { result ->
                    if (result.ok) result.copy(method = "GESTURE_LONG_PRESS") else result
                }
            }
        }
    }

    internal fun scrollNode(
        snapshot: NodeSnapshot,
        index: Int,
        direction: ScrollDirection,
    ): ScrollActionResult {
        val validation = runOnMainSync { validateNode(snapshot, index) }
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "SERVICE_TIMEOUT",
                message = "无障碍服务主线程无响应",
                targetIndex = index,
            )
        val validationNode = when (validation) {
            is NodeValidation.Invalid -> return ScrollActionResult.failure(
                direction = direction,
                code = validation.result.code,
                message = validation.result.message,
                targetIndex = index,
            )
            is NodeValidation.Valid -> validation.indexedNode
        }
        val scrollable = validationNode.scrollTarget?.resolveFor(validationNode.node)
        if (validationNode.scrollTarget != null && scrollable == null) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_ACTION_TARGET",
                message = "节点的滚动容器已经变化，请重新观察屏幕",
                targetIndex = index,
            )
        }
        scrollable ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NOT_SCROLLABLE",
            message = "指定节点及其父节点不可滚动",
            targetIndex = index,
        )
        return executeScroll(scrollable, direction, targetIndex = index)
    }

    internal fun scrollCurrent(direction: ScrollDirection): ScrollActionResult {
        val target = runOnMainSync {
            rootInActiveWindow?.let { root -> findBestScrollableNode(root, direction) }
        } ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NO_ACTIVE_WINDOW",
            message = "当前活动窗口不可访问",
        )
        return executeScroll(target, direction, targetIndex = null)
    }

    private fun executeScroll(
        target: AccessibilityNodeInfo,
        direction: ScrollDirection,
        targetIndex: Int?,
    ): ScrollActionResult = scrollActionLock.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val refreshed = runOnMainSync { target.refresh() } == true
        if (!refreshed || !target.isVisibleToUser || !target.isEnabled) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_NODE",
                message = "滚动目标已经失效，请重新观察屏幕",
                targetIndex = targetIndex,
            )
        }
        if (target.exposesOnlyOppositeAxis(direction)) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "AXIS_MISMATCH",
                message = "目标只支持另一滚动轴；为避免误触侧滑，本次未执行任何动作",
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val packageName = target.packageName?.toString().orEmpty()
        val windowId = target.windowId
        val beforeAnchors = scrollContentAnchors(target)
        val targetIdentity = ScrollTargetIdentity.from(target)
        val beforeSequence = currentScrollEventSequence()
        val method = chooseScrollMethod(target, direction)
        var methodName = method?.name.orEmpty()
        val nodeDispatch = method?.let { selected ->
            performNodeAction(target, selected.actionId, selected.args)
        }
        if (nodeDispatch == ActionDispatch.OUTCOME_UNKNOWN) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "滚动动作可能已提交，但系统未在时限内返回结果；请先重新观察，避免重复滚动",
                method = methodName,
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        var accepted = nodeDispatch == ActionDispatch.ACCEPTED

        if (!accepted) {
            val boundaryBeforeGesture = runOnMainSync {
                target.refresh() && isAtScrollBoundary(target, direction)
            } == true
            if (boundaryBeforeGesture) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            val bounds = clippedNodeBounds(target)
            val gesture = direction.gestureWithin(bounds)
                ?: return ScrollActionResult.failure(
                    direction = direction,
                    code = "INVALID_NODE_BOUNDS",
                    message = "滚动目标没有足够的可用区域",
                    targetIndex = targetIndex,
                )
            methodName = "GESTURE_SWIPE"
            val gestureResult = gestureSwipe(
                gesture.start.x.toFloat(),
                gesture.start.y.toFloat(),
                gesture.end.x.toFloat(),
                gesture.end.y.toFloat(),
                SCROLL_GESTURE_DURATION_MS,
            )
            if (!gestureResult.ok) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = gestureResult.code,
                    message = gestureResult.message,
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            accepted = true
        }
        if (!accepted) {
            val boundary = runOnMainSync {
                target.refresh() && isAtScrollBoundary(target, direction)
            } == true
            if (boundary) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_FAILED",
                message = "系统拒绝滚动动作",
                method = methodName,
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }

        val signal = awaitScrollSignal(
            afterSequence = beforeSequence,
            packageName = packageName,
            windowId = windowId,
            targetIdentity = targetIdentity,
            timeoutMillis = SCROLL_VERIFY_TIMEOUT_MS,
        )
        val afterAnchors = runOnMainSync {
            if (target.refresh()) scrollContentAnchors(target) else emptyList()
        }.orEmpty()
        val eventDelta = signal?.axisDelta(direction.axis)?.takeIf { delta -> delta != 0 }
        val anchorDelta = inferScrollAnchorDelta(beforeAnchors, afterAnchors, direction)
        val delta = eventDelta ?: anchorDelta
        val movementSource = when {
            eventDelta != null -> ScrollMovementSource.EVENT
            anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
            else -> null
        }
        val boundary = runOnMainSync {
            target.refresh() && isAtScrollBoundary(target, direction, signal)
        } == true
        val evidence = ScrollEvidenceContract.classify(
            direction = direction,
            delta = delta,
            movementSource = movementSource,
            atBoundary = boundary,
        )
        if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "DIRECTION_MISMATCH",
                message = "界面向请求方向的反方向移动",
                method = methodName,
                targetIndex = targetIndex,
                deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (
            evidence == ScrollEvidence.MOVED_BY_EVENT ||
            evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
        ) {
            return ScrollActionResult(
                ok = true,
                direction = direction,
                moved = true,
                atBoundary = boundary,
                method = methodName,
                deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                verifiedBy = if (evidence == ScrollEvidence.MOVED_BY_EVENT) {
                    "scroll_event"
                } else {
                    "anchor_motion"
                },
                elapsedMs = elapsedMs,
                targetIndex = targetIndex,
            )
        }
        if (evidence == ScrollEvidence.AT_BOUNDARY) {
            return ScrollActionResult.boundary(
                direction = direction,
                method = methodName,
                targetIndex = targetIndex,
                elapsedMs = elapsedMs,
            )
        }
        return ScrollActionResult.failure(
            direction = direction,
            code = "ACTION_OUTCOME_UNKNOWN",
            message = "滚动动作已经发出，但无法确认内容位移；请先重新观察，禁止直接重试",
            method = methodName,
            targetIndex = targetIndex,
            deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
            deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
            elapsedMs = elapsedMs,
        )
    }

    fun inputTextFocused(text: String): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "没有获得输入焦点的可编辑节点",
            )
        node.incrementalTextValidationError()?.let { error ->
            return@runNodeActionOnMainSync error
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.text?.toString().orEmpty(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@runNodeActionOnMainSync NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
        )
        setNodeText(node, plan.text, plan.cursor)
    }

    fun setTextNode(
        snapshot: NodeSnapshot?,
        index: Int?,
        text: String,
    ): NodeActionResult {
        if (index != null) {
            val requiredSnapshot = snapshot
                ?: return NodeActionResult.failure("NO_OBSERVATION", "指定 index 需要有效观察快照")
            return withValidatedNode(requiredSnapshot, index) { node ->
                if (!node.isEditable) {
                    NodeActionResult.failure("NOT_EDITABLE", "指定节点不可编辑")
                } else {
                    setNodeText(node, text, text.length)
                }
            }
        }
        return runNodeActionOnMainSync {
            val node = findFocusedEditableNode()
                ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                    "NO_FOCUSED_EDITABLE",
                    "没有获得输入焦点的可编辑节点",
                )
            setNodeText(node, text, text.length)
        }
    }

    /** 优先直接按选区写入，只有目标拒绝 SET_TEXT 时才回退系统粘贴。 */
    fun pasteText(text: String): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "没有获得输入焦点的可编辑节点",
            )
        node.incrementalTextValidationError()?.let { error ->
            return@runNodeActionOnMainSync error
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.text?.toString().orEmpty(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@runNodeActionOnMainSync NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
        )
        val directResult = setNodeText(node, plan.text, plan.cursor)
        if (directResult.ok) {
            return@runNodeActionOnMainSync directResult.copy(method = "ACTION_SET_TEXT_PASTE")
        }
        if (directResult.code != "ACTION_FAILED") {
            return@runNodeActionOnMainSync directResult
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val originalClip = runCatching { clipboard.primaryClip }.getOrNull()
        val temporaryLabel = "$CLIP_LABEL:${CLIP_IDS.incrementAndGet()}"
        val temporaryClip = ClipData.newPlainText(temporaryLabel, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val copied = runCatching {
            clipboard.setPrimaryClip(temporaryClip)
        }.isSuccess
        if (!copied) {
            return@runNodeActionOnMainSync NodeActionResult.failure(
                "CLIPBOARD_WRITE_FAILED",
                "写入剪贴板失败",
            )
        }
        val pasteResult = try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                val verified = runCatching { node.refresh() }.getOrDefault(false) &&
                    node.text?.toString() == plan.text
                if (verified) {
                    NodeActionResult.success(method = "ACTION_PASTE", verified = true)
                } else {
                    NodeActionResult.outcomeUnknown()
                }
            } else {
                NodeActionResult.failure("ACTION_FAILED", "输入节点拒绝粘贴动作")
            }
        } catch (_: Throwable) {
            NodeActionResult.outcomeUnknown()
        }
        val restored = restoreClipboardIfStillOwned(
            clipboard = clipboard,
            temporaryLabel = temporaryLabel,
            originalClip = originalClip,
        )
        if (!restored) {
            NodeActionResult.outcomeUnknown().copy(clipboardWritten = true)
        } else {
            pasteResult.copy(clipboardWritten = true)
        }
    }

    private fun restoreClipboardIfStillOwned(
        clipboard: ClipboardManager,
        temporaryLabel: String,
        originalClip: ClipData?,
    ): Boolean {
        val currentClip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return false
        if (currentClip.description.label?.toString() != temporaryLabel) {
            // 用户或其他应用已经写入新内容，不能用旧快照覆盖它。
            return true
        }
        return runCatching {
            if (originalClip != null) {
                clipboard.setPrimaryClip(originalClip)
            } else {
                clipboard.clearPrimaryClip()
            }
        }.isSuccess
    }

    fun imeEnter(): NodeActionResult = runNodeActionOnMainSync {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainSync NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "没有获得输入焦点的可编辑节点",
            )
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            NodeActionResult.success(method = "ACTION_IME_ENTER")
        } else {
            NodeActionResult.failure("ACTION_FAILED", "输入节点拒绝回车动作")
        }
    }

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            },
            durationMs.coerceIn(1, 3_000),
            successMethod = "GESTURE_TAP",
        )

    fun gestureSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(100, 3_000),
            successMethod = "GESTURE_SWIPE",
        )

    fun globalActionResult(name: String): NodeActionResult {
        val action = when (name.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return NodeActionResult.failure("INVALID_ARGUMENT", "不支持的系统动作")
        }
        return runNodeActionOnMainSync {
            if (performGlobalAction(action)) {
                NodeActionResult.success(method = "GLOBAL_ACTION_${name.uppercase()}")
            } else {
                NodeActionResult.failure("ACTION_FAILED", "系统拒绝全局动作")
            }
        }
    }

    fun globalAction(name: String): Boolean = globalActionResult(name).ok

    fun copyToClipboard(text: String): NodeActionResult =
        runNodeActionOnMainSync {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            NodeActionResult.success(method = "CLIPBOARD_SET")
        }

    fun readClipboard(): ClipboardReadResult = runOnMainSync {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return@runOnMainSync ClipboardReadResult.failure()
        if (clip.itemCount <= 0) return@runOnMainSync ClipboardReadResult.failure()
        ClipboardReadResult(
            ok = true,
            text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty(),
        )
    } ?: ClipboardReadResult.failure(code = "SERVICE_TIMEOUT")

    fun statusJson(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("package", currentPackageName().orEmpty())

    /**
     * 截取当前屏幕，排除 TYPE_ACCESSIBILITY_OVERLAY 浮层（glow/orb/bubble/resultCard/GestureIndicator）。
     * 从 agent-runtime 子线程调用；takeScreenshotOfWindow 内部 post 到主线程，
     * callback 在有界后台线程执行位图复制，latch 只阻塞 agent-runtime 工作线程。
     */
    fun captureScreenshotExcludingOverlays(
        excludedPackages: Set<String> = emptySet(),
    ): ScreenshotCaptureResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScreenshotCaptureResult.unavailable()
        }
        val startedAt = SystemClock.elapsedRealtime()
        val allWindows = windows ?: return ScreenshotCaptureResult.unavailable()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenshotCaptureResult.unavailable().also {
                allWindows.forEach { window -> runCatching { window.recycle() } }
            }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        val screenW = point.x
        val screenH = point.y
        if (screenW <= 0 || screenH <= 0) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable()
        }
        val screenBounds = Rect(0, 0, screenW, screenH)

        // 只过滤能确认属于 Eta 的无障碍 overlay；第三方 overlay 必须保留，
        // 否则截图与实际接收坐标手势的窗口会不一致。
        val windowPackages = allWindows.associate { window ->
            window.id to window.root?.packageName?.toString()
        }
        val windowDecisions = allWindows.associate { window ->
            window.id to ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay =
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                active = window.isActive,
                focused = window.isFocused,
                resolvedPackage = windowPackages[window.id],
                ownPackage = packageName,
                excludedPackages = excludedPackages,
            )
        }
        val unknownRelevantWindowIds = windowDecisions
            .filterValues { decision ->
                decision == ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN
            }
            .keys
            .toList()
        if (unknownRelevantWindowIds.isNotEmpty()) {
            val expectedWindows = allWindows.size
            allWindows.forEach { window -> runCatching { window.recycle() } }
            AndroidAgentLogger.warn(
                "Agent accessibility action=capture_screenshot outcome=failed " +
                    "reason=unknown_relevant_window_during_exclusion " +
                    "windows=${unknownRelevantWindowIds.size}"
            )
            return ScreenshotCaptureResult.blockedByUnknownWindow(
                expectedWindows = expectedWindows,
                windowIds = unknownRelevantWindowIds,
            )
        }
        val captureWindows = allWindows.mapNotNull { window ->
            if (windowDecisions[window.id] == ScreenshotWindowPolicy.Decision.EXCLUDE) {
                return@mapNotNull null
            }
            val bounds = Rect().also(window::getBoundsInScreen)
            if (
                bounds.width() <= 0 ||
                bounds.height() <= 0 ||
                !Rect.intersects(bounds, screenBounds)
            ) {
                return@mapNotNull null
            }
            ScreenshotWindow(
                id = window.id,
                layer = window.layer,
                type = window.type,
                bounds = bounds,
                active = window.isActive,
                focused = window.isFocused,
            )
        }.distinctBy { window -> window.id }.sortedBy { window -> window.layer }
        if (captureWindows.isEmpty()) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable()
        }
        val topApplicationWindowId = captureWindows
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull(ScreenshotWindow::layer)
            ?.id
            ?: captureWindows.maxByOrNull(ScreenshotWindow::layer)?.id
        val criticalWindowIds = captureWindows
            .asSequence()
            .filter { window ->
                window.id == topApplicationWindowId || window.active || window.focused
            }
            .map(ScreenshotWindow::id)
            .toSet()

        val latch = CountDownLatch(captureWindows.size)
        val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
        val failures = mutableMapOf<Int, Int>()
        val lock = Any()
        val acceptingResults = AtomicBoolean(true)

        for (window in captureWindows) {
            runCatching {
                takeScreenshotOfWindow(window.id, screenshotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val sw = convertToSoftwareBitmap(screenshot)
                                ?: throw IllegalStateException("screenshot bitmap unavailable")
                            var retained = false
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    screenshots[window.id] = sw to Rect(window.bounds)
                                    retained = true
                                }
                            }
                            if (!retained && !sw.isRecycled) sw.recycle()
                        } catch (_: Exception) {
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        synchronized(lock) {
                            if (acceptingResults.get()) failures[window.id] = errorCode
                        }
                        latch.countDown()
                    }
                })
            }.onFailure {
                synchronized(lock) {
                    if (acceptingResults.get()) {
                        failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    }
                }
                latch.countDown()
            }
        }
        val completed = try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        acceptingResults.set(false)
        val captured = synchronized(lock) {
            screenshots.toMap().also { screenshots.clear() }
        }
        val capturedIds = captured.keys
        val missingIds = captureWindows.map(ScreenshotWindow::id).filterNot(capturedIds::contains)
        val criticalWindowMissing = missingIds.any(criticalWindowIds::contains)
        val merged = if (captured.isEmpty() || criticalWindowMissing) {
            null
        } else {
            mergeScreenshots(captured, captureWindows, screenW, screenH)
        }
        val failureCodes = synchronized(lock) { failures.toMap() }
        val complete = completed && missingIds.isEmpty() && merged != null
        AndroidAgentLogger.debug {
            "Agent accessibility action=capture_screenshot outcome=merged " +
                "allWindows=${allWindows.size} validWindows=${captureWindows.size} " +
                "excludedPackages=${excludedPackages.size} " +
                "completed=$completed screenshots=${captured.size} " +
                "complete=$complete criticalMissing=$criticalWindowMissing " +
                "screen=${screenW}x${screenH} merged=${merged?.width}x${merged?.height} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
        }
        captured.values.forEach { (bitmap, _) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        allWindows.forEach { window -> runCatching { window.recycle() } }
        return ScreenshotCaptureResult(
            bitmap = merged,
            complete = complete,
            expectedWindows = captureWindows.size,
            capturedWindows = captured.size,
            missingWindowIds = missingIds,
            failureCodes = failureCodes,
            timedOut = !completed,
            criticalWindowMissing = criticalWindowMissing,
        )
    }

    private fun convertToSoftwareBitmap(screenshot: ScreenshotResult): Bitmap? =
        screenshot.hardwareBuffer.use { hardwareBuffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return@use null
            try {
                if (wrapped.config == Bitmap.Config.HARDWARE) {
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    wrapped
                }
            } finally {
                if (wrapped.config == Bitmap.Config.HARDWARE && !wrapped.isRecycled) {
                    wrapped.recycle()
                }
            }
        }

    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<ScreenshotWindow>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        var merged: Bitmap? = null
        return try {
            val output = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            merged = output
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val occlusionPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            for (window in sortedWindows) {
                val pair = screenshots[window.id]
                if (pair == null) {
                    // 上层窗口抓取失败时必须遮住其区域，不能向模型暴露实际已被遮挡的底层界面。
                    canvas.drawRect(RectF(window.bounds), occlusionPaint)
                } else {
                    val (bmp, bounds) = pair
                    if (bmp.isRecycled) continue
                    // 把窗口 bitmap 缩放到其 bounds 尺寸绘制，处理 takeScreenshotOfWindow
                    // 返回尺寸与 bounds 不一致（逻辑像素 vs 物理像素）的情况。
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, RectF(bounds), null)
                }
            }
            output
        } catch (_: Exception) {
            merged?.takeUnless(Bitmap::isRecycled)?.recycle()
            null
        }
    }

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        text: String,
        cursor: Int,
    ): NodeActionResult {
        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            return NodeActionResult.failure("ACTION_FAILED", "输入节点拒绝文本修改动作")
        }
        val safeCursor = cursor.coerceIn(0, text.length)
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, safeCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, safeCursor)
        }
        val refreshed = runCatching { node.refresh() }.getOrDefault(false)
        if (!node.isPassword && (!refreshed || node.text?.toString() != text)) {
            return NodeActionResult.outcomeUnknown()
        }
        val selectionRestored = refreshed && node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArgs,
            )
        return NodeActionResult.success(
            method = if (selectionRestored) {
                "ACTION_SET_TEXT_AND_SELECTION"
            } else {
                "ACTION_SET_TEXT"
            },
            verified = !node.isPassword,
        )
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val inputFocus = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()
        if (inputFocus?.isUsableInputTarget() == true) return inputFocus
        return findNode(root) { node -> node.isFocused && node.isUsableInputTarget() }
    }

    private fun AccessibilityNodeInfo.isUsableInputTarget(): Boolean =
        isEditable && isEnabled && isVisibleToUser

    private fun AccessibilityNodeInfo.incrementalTextValidationError(): NodeActionResult? {
        val currentText = text
        if (isPassword || currentText == null) {
            return NodeActionResult.failure(
                "TEXT_CONTENT_UNAVAILABLE",
                "当前输入框不允许可靠读取已有文本；请用 replace_text 提供完整值",
            )
        }
        if (
            !TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = currentText.length,
                selectionStart = textSelectionStart,
                selectionEnd = textSelectionEnd,
            )
        ) {
            return NodeActionResult.failure(
                "TEXT_SELECTION_UNAVAILABLE",
                "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
            )
        }
        return null
    }

    private fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): AccessibilityNodeInfo {
        val candidates = mutableListOf<ScrollCandidate>()
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_UI_TREE_DEPTH ||
                visitedNodes >= MAX_SCROLL_SEARCH_NODES ||
                !activePath.add(node)
            ) return
            visitedNodes++
            try {
                if (!node.isVisibleToUser) return
                if (node.isEnabled && node.hasScrollCapability()) {
                    val bounds = clippedNodeBounds(node)
                    val supportRank = node.directionSupportRank(direction)
                    if (
                        !node.exposesOnlyOppositeAxis(direction) &&
                        !bounds.isEmpty &&
                        direction.gestureWithin(bounds) != null
                    ) {
                        candidates += ScrollCandidate(
                            node = node,
                            supportRank = supportRank,
                            depth = depth,
                            area = bounds.width().toLong() * bounds.height().toLong(),
                        )
                    }
                }
                for (childIndex in 0 until node.childCount) {
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return candidates.maxWithOrNull(
            compareBy<ScrollCandidate> { it.area }
                .thenBy { it.supportRank }
                .thenBy { -it.depth },
        )?.node ?: root
    }

    private fun AccessibilityNodeInfo.directionSupportRank(direction: ScrollDirection): Int {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return when {
            direction.exactScrollActionId() in actions -> 4
            direction.pageActionId() in actions -> 3
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            ) && (
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions ||
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
                ) -> 2
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id in actions -> 1
            else -> 0
        }
    }

    private fun AccessibilityNodeInfo.exposesOnlyOppositeAxis(
        direction: ScrollDirection,
    ): Boolean {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return ScrollAxisContract.exposesOnlyOppositeAxis(
            requestedAxis = direction.axis,
            hasVerticalActions = hasVertical,
            hasHorizontalActions = hasHorizontal,
        )
    }

    private fun AccessibilityNodeInfo.hasScrollCapability(): Boolean {
        if (isScrollable) return true
        val actions = supportedActionIds()
        return SCROLL_ACTION_IDS.any(actions::contains)
    }

    private fun AccessibilityNodeInfo.supportedActionIds(): Set<Int> =
        actionList.mapTo(hashSetOf()) { action -> action.id }

    private fun chooseScrollMethod(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): ScrollMethod? {
        if (node.exposesOnlyOppositeAxis(direction)) return null
        val actionIds = node.supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val exactAction = direction.exactScrollActionId()
        if (exactAction in actionIds) {
            return ScrollMethod(exactAction, "ACTION_SCROLL_${direction.name}")
        }
        val pageAction = direction.pageActionId()
        if (pageAction in actionIds) {
            return ScrollMethod(pageAction, "ACTION_PAGE_${direction.name}")
        }
        if (
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            )
        ) {
            val fallback = if (direction == ScrollDirection.DOWN) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (fallback in actionIds) {
                return ScrollMethod(
                    actionId = fallback,
                    name = if (direction == ScrollDirection.DOWN) {
                        "ACTION_SCROLL_FORWARD"
                    } else {
                        "ACTION_SCROLL_BACKWARD"
                    },
                )
            }
        }
        val inDirection = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id
        if (inDirection in actionIds) {
            val args = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_DIRECTION_INT,
                    direction.focusDirection(),
                )
            }
            return ScrollMethod(inDirection, "ACTION_SCROLL_IN_DIRECTION", args)
        }
        return null
    }

    private fun isAtScrollBoundary(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
        signal: ScrollSignal? = null,
    ): Boolean {
        if (signal != null && signal.axisDelta(direction.axis) != null) {
            when (direction) {
                ScrollDirection.UP -> if (
                    signal.maxScrollY > 0 && signal.scrollY <= 0
                ) return true
                ScrollDirection.DOWN -> if (
                    signal.maxScrollY > 0 && signal.scrollY >= signal.maxScrollY
                ) return true
                ScrollDirection.LEFT -> if (
                    signal.maxScrollX > 0 && signal.scrollX <= 0
                ) return true
                ScrollDirection.RIGHT -> if (
                    signal.maxScrollX > 0 && signal.scrollX >= signal.maxScrollX
                ) return true
            }
        }
        if (chooseScrollMethod(node, direction) != null) return false
        return chooseScrollMethod(node, direction.opposite()) != null
    }

    private fun clippedNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = node.bounds()
        val size = displaySize()
        if (size != null) {
            if (!bounds.intersect(0, 0, size.first, size.second)) bounds.setEmpty()
        }
        return bounds
    }

    private fun scrollContentAnchors(root: AccessibilityNodeInfo): List<ScrollAnchor> {
        val anchors = ArrayList<ScrollAnchor>(SCROLL_ANCHOR_NODE_LIMIT)
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        val rootBounds = root.bounds()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (
                depth > MAX_UI_TREE_DEPTH ||
                anchors.size >= SCROLL_ANCHOR_NODE_LIMIT ||
                !activePath.add(node)
            ) return
            try {
                if (!node.isVisibleToUser) return
                val bounds = node.bounds()
                val key = buildString {
                    append(node.uniqueId.orEmpty())
                    append('|')
                    append(node.className?.toString().orEmpty())
                    append('|')
                    append(node.viewIdResourceName.orEmpty())
                    append('|')
                    append(node.text?.toString().orEmpty().take(80))
                    append('|')
                    append(node.contentDescription?.toString().orEmpty().take(80))
                }
                if (
                    node.uniqueId?.isNotBlank() == true ||
                    node.viewIdResourceName?.isNotBlank() == true ||
                    node.text?.isNotBlank() == true ||
                    node.contentDescription?.isNotBlank() == true
                ) {
                    anchors += ScrollAnchor(
                        key = key,
                        centerX = bounds.centerX() - rootBounds.left,
                        centerY = bounds.centerY() - rootBounds.top,
                    )
                }
                for (childIndex in 0 until node.childCount) {
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return anchors
    }

    private fun inferScrollAnchorDelta(
        before: List<ScrollAnchor>,
        after: List<ScrollAnchor>,
        direction: ScrollDirection,
    ): Int? {
        fun unique(anchors: List<ScrollAnchor>): Map<String, ScrollAnchor> = anchors
            .groupBy(ScrollAnchor::key)
            .mapNotNull { (key, matches) -> matches.singleOrNull()?.let { anchor -> key to anchor } }
            .toMap()
        val beforeUnique = unique(before)
        val afterUnique = unique(after)
        val contentDeltas = beforeUnique.mapNotNull { (key, oldAnchor) ->
            val newAnchor = afterUnique[key] ?: return@mapNotNull null
            if (direction.axis == ScrollAxis.VERTICAL) {
                newAnchor.centerY - oldAnchor.centerY
            } else {
                newAnchor.centerX - oldAnchor.centerX
            }
        }
        return RootScrollMotionContract.inferScrollDelta(contentDeltas)
    }

    private fun ScrollDirection.exactScrollActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
    }

    private fun ScrollDirection.pageActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
    }

    private fun ScrollDirection.focusDirection(): Int = when (this) {
        ScrollDirection.UP -> View.FOCUS_UP
        ScrollDirection.DOWN -> View.FOCUS_DOWN
        ScrollDirection.LEFT -> View.FOCUS_LEFT
        ScrollDirection.RIGHT -> View.FOCUS_RIGHT
    }

    private fun ScrollDirection.opposite(): ScrollDirection = when (this) {
        ScrollDirection.UP -> ScrollDirection.DOWN
        ScrollDirection.DOWN -> ScrollDirection.UP
        ScrollDirection.LEFT -> ScrollDirection.RIGHT
        ScrollDirection.RIGHT -> ScrollDirection.LEFT
    }

    private fun withValidatedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (AccessibilityNodeInfo) -> NodeActionResult,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        block(indexed.node)
    }

    private fun withValidatedIndexedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (IndexedNode) -> NodeActionResult,
    ): NodeActionResult {
        val validation = runOnMainSync { validateNode(snapshot, index) }
            ?: return NodeActionResult.failure("SERVICE_TIMEOUT", "无障碍服务主线程无响应")
        return when (validation) {
            is NodeValidation.Invalid -> validation.result
            is NodeValidation.Valid -> block(validation.indexedNode)
        }
    }

    private fun validateNode(snapshot: NodeSnapshot, index: Int): NodeValidation {
        if (snapshot.serviceToken != serviceToken) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("SERVICE_RECONNECTED", "无障碍服务已重连，请重新观察屏幕"),
            )
        }
        val indexed = snapshot.indexedNodes.firstOrNull { it.index == index }
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index"),
            )
        val activeRoot = rootInActiveWindow
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "当前活动窗口不可访问，请重新观察屏幕"),
            )
        val activePackage = activeRoot.packageName?.toString().orEmpty()
        if (activeRoot.windowId != snapshot.windowId || activePackage != snapshot.packageName) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "活动窗口已经变化，请重新观察屏幕"),
            )
        }
        if (
            windowContentGeneration(snapshot.windowId) != snapshot.contentGeneration &&
            !snapshot.hasUnambiguousIdentity(indexed)
        ) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_CONTENT", "窗口内容已经变化，请重新观察屏幕"),
            )
        }
        val node = indexed.node
        if (!runCatching { node.refresh() }.getOrDefault(false)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_NODE", "目标节点已经失效，请重新观察屏幕"),
            )
        }
        if (!indexed.identityMatches(node)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("IDENTITY_CHANGED", "目标节点内容或身份已经变化，请重新观察屏幕"),
            )
        }
        if (!node.isVisibleToUser || !node.isEnabled) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("NODE_NOT_ACTIONABLE", "目标节点当前不可见或不可用"),
            )
        }
        return NodeValidation.Valid(indexed)
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = hashSetOf<AccessibilityNodeInfo>()
        queue.addLast(node to 0)
        while (queue.isNotEmpty() && visited.size < MAX_FOCUS_SEARCH_NODES) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (predicate(current)) return current
            if (depth >= MAX_UI_TREE_DEPTH) continue
            for (childIndex in 0 until current.childCount) {
                val child = runCatching { current.getChild(childIndex) }.getOrNull() ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<IndexedNode>,
        maxNodes: Int,
        depth: Int,
        traversal: NodeTraversalState,
        clickableAncestor: NodeActionTarget? = null,
        longClickableAncestor: NodeActionTarget? = null,
        scrollableAncestor: NodeActionTarget? = null,
    ) {
        if (out.size >= maxNodes) {
            traversal.truncated = true
            return
        }
        if (depth > MAX_UI_TREE_DEPTH || traversal.visitedNodes >= traversal.maxVisitedNodes) {
            traversal.truncated = true
            return
        }
        if (!traversal.activePath.add(node)) {
            traversal.truncated = true
            return
        }
        traversal.visitedNodes++
        try {
            // 不可见父节点的后代不会成为可操作目标，尽早裁掉这类大分支。
            val visible = node.isVisibleToUser
            if (depth > 0 && !visible) return

            val bounds = node.bounds()
            val text = node.text?.toString().orEmpty().take(120)
            val desc = node.contentDescription?.toString().orEmpty().take(120)
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val scrollable = node.isScrollable
            val focused = node.isFocused
            val editable = node.isEditable
            val password = node.isPassword
            val enabled = node.isEnabled
            val clickTarget = if (clickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                clickableAncestor
            }
            val longClickTarget = if (longClickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                longClickableAncestor
            }
            val hasScrollCapability = enabled && node.hasScrollCapability()
            val scrollTarget = if (hasScrollCapability) {
                NodeActionTarget.capture(node)
            } else {
                scrollableAncestor
            }
            val useful = text.isNotBlank() ||
                desc.isNotBlank() ||
                clickable ||
                longClickable ||
                hasScrollCapability ||
                focused ||
                editable
            if (visible && bounds.width() > 2 && bounds.height() > 2 && useful) {
                out += IndexedNode(
                    index = out.size,
                    node = node,
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    text = text,
                    desc = desc,
                    className = node.className?.toString().orEmpty(),
                    packageName = node.packageName?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    bounds = Rect(bounds),
                    clickable = clickable,
                    longClickable = longClickable,
                    scrollable = scrollTarget != null,
                    focused = focused,
                    editable = editable,
                    password = password,
                    enabled = enabled,
                    clickTarget = clickTarget,
                    longClickTarget = longClickTarget,
                    scrollTarget = scrollTarget,
                )
            }
            for (index in 0 until node.childCount) {
                if (
                    out.size >= maxNodes ||
                    traversal.visitedNodes >= traversal.maxVisitedNodes
                ) {
                    traversal.truncated = true
                    return
                }
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                collectNodes(
                    node = child,
                    out = out,
                    maxNodes = maxNodes,
                    depth = depth + 1,
                    traversal = traversal,
                    clickableAncestor = clickTarget,
                    longClickableAncestor = longClickTarget,
                    scrollableAncestor = scrollTarget,
                )
            }
        } finally {
            traversal.activePath.remove(node)
        }
    }

    private fun AccessibilityNodeInfo.bounds(): Rect =
        Rect().also { getBoundsInScreen(it) }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null
    ): ActionDispatch =
        when (val result = callOnMainSync {
            if (args == null) node.performAction(action) else node.performAction(action, args)
        }) {
            is MainThreadCallResult.Completed -> {
                if (result.value == true) ActionDispatch.ACCEPTED else ActionDispatch.REJECTED
            }
            MainThreadCallResult.NOT_STARTED -> ActionDispatch.REJECTED
            MainThreadCallResult.OUTCOME_UNKNOWN -> ActionDispatch.OUTCOME_UNKNOWN
        }

    private fun dispatchGestureResult(
        path: Path,
        durationMs: Long,
        successMethod: String,
    ): NodeActionResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return NodeActionResult.failure(
                "GESTURE_NOT_DISPATCHED",
                "不能在无障碍主线程同步等待手势",
            )
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        val outcome = java.util.concurrent.atomic.AtomicReference<GestureDispatch>()
        val posted = mainHandler.post {
            if (!gate.tryStart()) {
                latch.countDown()
                return@post
            }
            val gesture = runCatching {
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
            }.getOrElse {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
                return@post
            }
            val dispatched = try {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.COMPLETED)
                            gate.finish()
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.CANCELLED)
                            gate.finish()
                            latch.countDown()
                        }
                    },
                    GESTURE_CALLBACK_HANDLER,
                )
            } catch (_: Throwable) {
                // Binder 事务可能已经送达；异常不能证明手势未执行，禁止 Root 重放。
                outcome.set(GestureDispatch.OUTCOME_UNKNOWN)
                gate.finish()
                latch.countDown()
                return@post
            }
            if (!dispatched) {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
            }
        }
        if (!posted) {
            return NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "无障碍主线程拒绝手势任务")
        }
        val finishedInTime = try {
            latch.await(durationMs + GESTURE_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finishedInTime) {
            if (gate.cancelIfPending()) {
                return NodeActionResult.failure(
                    "GESTURE_NOT_DISPATCHED",
                    "无障碍主线程繁忙，手势已在执行前取消",
                )
            }
            return NodeActionResult.outcomeUnknown()
        }
        return when (outcome.get()) {
            GestureDispatch.COMPLETED -> NodeActionResult.success(successMethod)
            GestureDispatch.CANCELLED -> NodeActionResult.outcomeUnknown()
            GestureDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            GestureDispatch.NOT_DISPATCHED,
            null -> NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "系统拒绝手势任务")
        }
    }

    private fun runNodeActionOnMainSync(block: () -> NodeActionResult): NodeActionResult =
        when (val result = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> result.value
                ?: NodeActionResult.failure("ACTION_FAILED", "无障碍动作执行异常")
            MainThreadCallResult.NOT_STARTED ->
                NodeActionResult.failure("SERVICE_TIMEOUT", "无障碍服务主线程无响应，动作未执行")
            MainThreadCallResult.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
        }

    private fun <T> runOnMainSync(block: () -> T): T? =
        when (val result = callOnMainSync(block)) {
            is MainThreadCallResult.Completed -> result.value
            MainThreadCallResult.NOT_STARTED,
            MainThreadCallResult.OUTCOME_UNKNOWN -> null
        }

    private fun <T> callOnMainSync(block: () -> T): MainThreadCallResult<T> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                MainThreadCallResult.Completed(block())
            } catch (_: Throwable) {
                // 框架调用抛错时无法证明副作用没有发生，禁止调用方回退重放。
                MainThreadCallResult.OUTCOME_UNKNOWN
            }
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        var value: T? = null
        var failed = false
        val posted = mainHandler.post {
            if (gate.tryStart()) {
                try {
                    value = block()
                } catch (_: Throwable) {
                    failed = true
                } finally {
                    gate.finish()
                }
            }
            latch.countDown()
        }
        if (!posted) return MainThreadCallResult.NOT_STARTED
        val completed = try {
            latch.await(MAIN_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            if (gate.cancelIfPending()) return MainThreadCallResult.NOT_STARTED
            // 已开始的副作用不得由调用方回退重做；返回未知结果，让模型先重新观察。
            return MainThreadCallResult.OUTCOME_UNKNOWN
        }
        if (failed) return MainThreadCallResult.OUTCOME_UNKNOWN
        return MainThreadCallResult.Completed(value)
    }

    data class NodeActionResult(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val clipboardWritten: Boolean = false,
        val verified: Boolean? = null,
    ) {
        companion object {
            fun success(method: String, verified: Boolean? = null): NodeActionResult =
                NodeActionResult(ok = true, method = method, verified = verified)

            fun failure(code: String, message: String): NodeActionResult =
                NodeActionResult(ok = false, code = code, message = message)

            fun outcomeUnknown(): NodeActionResult = failure(
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "动作可能已执行，但系统未在时限内返回结果；请先重新观察，避免重复操作",
            )
        }
    }

    private enum class ActionDispatch {
        ACCEPTED,
        REJECTED,
        OUTCOME_UNKNOWN,
    }

    private enum class GestureDispatch {
        COMPLETED,
        CANCELLED,
        NOT_DISPATCHED,
        OUTCOME_UNKNOWN,
    }

    private sealed interface MainThreadCallResult<out T> {
        data class Completed<T>(val value: T?) : MainThreadCallResult<T>
        data object NOT_STARTED : MainThreadCallResult<Nothing>
        data object OUTCOME_UNKNOWN : MainThreadCallResult<Nothing>
    }

    data class ScreenshotCaptureResult(
        val bitmap: Bitmap?,
        val complete: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int>,
        val failureCodes: Map<Int, Int>,
        val timedOut: Boolean,
        val criticalWindowMissing: Boolean,
    ) {
        val partial: Boolean get() = bitmap != null && !complete

        companion object {
            fun unavailable(): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = 0,
                capturedWindows = 0,
                missingWindowIds = emptyList(),
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = false,
            )

            fun blockedByUnknownWindow(
                expectedWindows: Int,
                windowIds: List<Int>,
            ): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = expectedWindows,
                capturedWindows = 0,
                missingWindowIds = windowIds,
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = true,
            )
        }
    }

    data class ClipboardReadResult(
        val ok: Boolean,
        val text: String = "",
        val code: String = "",
    ) {
        companion object {
            fun failure(code: String = "CLIPBOARD_UNAVAILABLE_OR_EMPTY"): ClipboardReadResult =
                ClipboardReadResult(ok = false, code = code)
        }
    }

    internal data class ScrollActionResult(
        val ok: Boolean,
        val direction: ScrollDirection,
        val moved: Boolean,
        val atBoundary: Boolean?,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val deltaX: Int? = null,
        val deltaY: Int? = null,
        val verifiedBy: String = "",
        val elapsedMs: Long = 0L,
        val targetIndex: Int? = null,
    ) {
        companion object {
            fun boundary(
                direction: ScrollDirection,
                method: String,
                targetIndex: Int?,
                elapsedMs: Long,
            ): ScrollActionResult = ScrollActionResult(
                ok = true,
                direction = direction,
                moved = false,
                atBoundary = true,
                method = method,
                verifiedBy = "action_list",
                elapsedMs = elapsedMs,
                targetIndex = targetIndex,
            )

            fun failure(
                direction: ScrollDirection,
                code: String,
                message: String,
                method: String = "",
                targetIndex: Int? = null,
                deltaX: Int? = null,
                deltaY: Int? = null,
                elapsedMs: Long = 0L,
            ): ScrollActionResult = ScrollActionResult(
                ok = false,
                direction = direction,
                moved = false,
                atBoundary = null,
                code = code,
                message = message,
                method = method,
                targetIndex = targetIndex,
                deltaX = deltaX,
                deltaY = deltaY,
                elapsedMs = elapsedMs,
            )
        }
    }

    class NodeSnapshot internal constructor(
        val id: String,
        internal val serviceToken: Long,
        val packageName: String,
        val windowId: Int,
        internal val contentGeneration: Long,
        val capturedAtElapsedMs: Long,
        val truncated: Boolean,
        internal val indexedNodes: List<IndexedNode>,
    ) {
        val nodes: List<UiNode> = indexedNodes.map(IndexedNode::toUiNode)

        internal fun hasUnambiguousIdentity(target: IndexedNode): Boolean {
            if (!target.hasStrongIdentity()) return false
            val hasUniqueId = target.uniqueId.isNotBlank()
            val matches = if (hasUniqueId) {
                indexedNodes.count { candidate -> candidate.uniqueId == target.uniqueId }
            } else {
                indexedNodes.count(target::sameSemanticIdentity)
            }
            return AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = hasUniqueId,
                snapshotTruncated = truncated,
                identityMatchCount = matches,
            )
        }
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
        val password: Boolean,
        val enabled: Boolean
    )

    internal data class IndexedNode(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val uniqueId: String,
        val windowId: Int,
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
        val password: Boolean,
        val enabled: Boolean,
        val clickTarget: NodeActionTarget?,
        val longClickTarget: NodeActionTarget?,
        val scrollTarget: NodeActionTarget?,
    ) {
        fun hasStrongIdentity(): Boolean = identity().strong

        fun sameSemanticIdentity(other: IndexedNode): Boolean =
            windowId == other.windowId &&
                packageName == other.packageName &&
                className == other.className &&
                viewId == other.viewId &&
                text == other.text &&
                desc == other.desc

        fun identityMatches(refreshed: AccessibilityNodeInfo): Boolean =
            identity().matches(refreshed.toIdentity())

        private fun identity(): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
            uniqueId = uniqueId,
            windowId = windowId,
            packageName = packageName,
            className = className,
            viewId = viewId,
            text = text,
            description = desc,
            password = password,
        )

        private fun AccessibilityNodeInfo.toIdentity(): AccessibilityNodeIdentity =
            AccessibilityNodeIdentity(
                uniqueId = uniqueId.orEmpty(),
                windowId = windowId,
                packageName = packageName?.toString().orEmpty(),
                className = className?.toString().orEmpty(),
                viewId = viewIdResourceName.orEmpty(),
                text = text?.toString().orEmpty().take(120),
                description = contentDescription?.toString().orEmpty().take(120),
                password = isPassword,
            )

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
                password = password,
                enabled = enabled
            )
    }

    internal data class NodeActionTarget(
        val node: AccessibilityNodeInfo,
        val identity: AccessibilityNodeIdentity,
    ) {
        fun resolveFor(descendant: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!runCatching { node.refresh() }.getOrDefault(false)) return null
            if (!node.isVisibleToUser || !node.isEnabled) return null
            val refreshedIdentity = AccessibilityNodeIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                windowId = node.windowId,
                packageName = node.packageName?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                text = node.text?.toString().orEmpty().take(120),
                description = node.contentDescription?.toString().orEmpty().take(120),
                password = node.isPassword,
            )
            if (!identity.matches(refreshedIdentity)) return null
            var current: AccessibilityNodeInfo? = descendant
            var depth = 0
            while (current != null && depth <= MAX_UI_TREE_DEPTH) {
                if (current == node) return node
                current = current.parent
                depth++
            }
            return null
        }

        companion object {
            fun capture(node: AccessibilityNodeInfo): NodeActionTarget = NodeActionTarget(
                node = node,
                identity = AccessibilityNodeIdentity(
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    packageName = node.packageName?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = node.text?.toString().orEmpty().take(120),
                    description = node.contentDescription?.toString().orEmpty().take(120),
                    password = node.isPassword,
                ),
            )
        }
    }

    private data class ScrollCandidate(
        val node: AccessibilityNodeInfo,
        val supportRank: Int,
        val depth: Int,
        val area: Long,
    )

    private data class ScrollAnchor(
        val key: String,
        val centerX: Int,
        val centerY: Int,
    )

    private data class ScrollMethod(
        val actionId: Int,
        val name: String,
        val args: Bundle? = null,
    )

    private data class ScrollSignal(
        val sequence: Long,
        val packageName: String,
        val windowId: Int,
        val deltaX: Int,
        val deltaY: Int,
        val scrollX: Int,
        val scrollY: Int,
        val maxScrollX: Int,
        val maxScrollY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val sourceUniqueId: String,
        val sourceViewId: String,
        val sourceClassName: String,
        val sourceBounds: Rect,
    ) {
        fun axisDelta(axis: ScrollAxis): Int? = ScrollEvidenceContract.normalizeAccessibilityDelta(
            when (axis) {
            ScrollAxis.HORIZONTAL -> deltaX
            ScrollAxis.VERTICAL -> deltaY
            },
        )

        fun matchesTarget(target: ScrollTargetIdentity): Boolean {
            if (target.uniqueId.isNotBlank() && sourceUniqueId.isNotBlank()) {
                return target.uniqueId == sourceUniqueId
            }
            if (target.viewId.isNotBlank() && sourceViewId.isNotBlank()) {
                return target.viewId == sourceViewId && target.className == sourceClassName
            }
            return target.className == sourceClassName && target.bounds == sourceBounds
        }
    }

    private data class ScrollTargetIdentity(
        val uniqueId: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
    ) {
        companion object {
            fun from(node: AccessibilityNodeInfo): ScrollTargetIdentity = ScrollTargetIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = Rect().also(node::getBoundsInScreen),
            )
        }
    }

    companion object {
        private const val CLIP_LABEL = "fuck_andes_agent"
        private const val WINDOW_POLL_FALLBACK_MS = 80L
        private const val MAX_UI_TREE_DEPTH = 24
        private const val UI_TREE_VISIT_MULTIPLIER = 8
        private const val MIN_UI_TREE_VISITED_NODES = 128
        private const val MAX_UI_TREE_VISITED_NODES = 960
        private const val MAX_FOCUS_SEARCH_NODES = 512
        private const val MAX_SCROLL_SEARCH_NODES = 512
        private const val SCROLL_ANCHOR_NODE_LIMIT = 48
        private const val SCROLL_GESTURE_DURATION_MS = 300L
        private const val SCROLL_VERIFY_TIMEOUT_MS = 520L
        private const val GESTURE_CALLBACK_GRACE_MS = 1_500L
        private const val MAIN_SYNC_TIMEOUT_SECONDS = 3L
        private const val MAX_SCROLL_SIGNALS = 64

        private val GESTURE_CALLBACK_THREAD = HandlerThread("agent-gesture-callback").apply {
            isDaemon = true
            start()
        }
        private val GESTURE_CALLBACK_HANDLER = Handler(GESTURE_CALLBACK_THREAD.looper)

        /**
         * 进程级 executor 不在 service 重连时 shutdownNow；否则已经由框架创建、
         * 尚在队列中的 ScreenshotResult 无法进入回调释放 HardwareBuffer。
         */
        private val SCREENSHOT_EXECUTOR: ExecutorService =
            Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "agent-screenshot-callback").apply { isDaemon = true }
            }

        private val SCROLL_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        )
        private val VERTICAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
        )
        private val HORIZONTAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        )

        private val SERVICE_TOKENS = AtomicLong(0)
        private val SNAPSHOT_IDS = AtomicLong(0)
        private val CLIP_IDS = AtomicLong(0)

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }

    private sealed interface NodeValidation {
        data class Valid(val indexedNode: IndexedNode) : NodeValidation
        data class Invalid(val result: NodeActionResult) : NodeValidation
    }
}
