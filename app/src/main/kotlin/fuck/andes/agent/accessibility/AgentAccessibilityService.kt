package fuck.andes.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNodes: List<IndexedNode> = emptyList()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lastNodes = emptyList()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }
}
