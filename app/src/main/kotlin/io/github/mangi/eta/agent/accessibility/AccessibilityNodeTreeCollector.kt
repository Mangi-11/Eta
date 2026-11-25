package io.github.mangi.eta.agent.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import io.github.mangi.eta.agent.accessibility.AccessibilityScrollHeuristics.hasScrollCapability

internal data class NodeTraversalState(
    val maxVisitedNodes: Int,
    val activePath: MutableSet<AccessibilityNodeInfo> = hashSetOf(),
    var visitedNodes: Int = 0,
    var truncated: Boolean = false,
)

/**
 * 负责无障碍 UI 节点树的广度查找、深度裁剪收集及节点文本/边界辅助读取。
 */
internal object AccessibilityNodeTreeCollector {

    private const val MAX_UI_TREE_DEPTH = 24
    private const val MAX_FOCUS_SEARCH_NODES = 512

    fun findNode(
        node: AccessibilityNodeInfo,
        ensureActive: () -> Unit,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = hashSetOf<AccessibilityNodeInfo>()
        queue.addLast(node to 0)
        while (queue.isNotEmpty() && visited.size < MAX_FOCUS_SEARCH_NODES) {
            ensureActive()
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (predicate(current)) return current
            if (depth >= MAX_UI_TREE_DEPTH) continue
            for (childIndex in 0 until current.childCount) {
                ensureActive()
                val child = runCatching { current.getChild(childIndex) }.getOrNull() ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AgentAccessibilityService.IndexedNode>,
        maxNodes: Int,
        depth: Int,
        traversal: NodeTraversalState,
        ensureActive: () -> Unit,
        clickableAncestor: AgentAccessibilityService.NodeActionTarget? = null,
        longClickableAncestor: AgentAccessibilityService.NodeActionTarget? = null,
        scrollableAncestor: AgentAccessibilityService.NodeActionTarget? = null,
    ) {
        ensureActive()
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

            val bounds = node.boundsInScreenRect()
            val text = node.realText().take(120)
            val hint = node.hintText?.toString().orEmpty().take(60)
            val desc = node.contentDescription?.toString().orEmpty().take(120)
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val focused = node.isFocused
            val editable = node.isEditable
            val password = node.isPassword
            val enabled = node.isEnabled
            val clickTarget = if (clickable && enabled) {
                AgentAccessibilityService.NodeActionTarget.capture(node)
            } else {
                clickableAncestor
            }
            val longClickTarget = if (longClickable && enabled) {
                AgentAccessibilityService.NodeActionTarget.capture(node)
            } else {
                longClickableAncestor
            }
            val hasScrollCapability = enabled && node.hasScrollCapability()
            val scrollTarget = if (hasScrollCapability) {
                AgentAccessibilityService.NodeActionTarget.capture(node)
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
                out += AgentAccessibilityService.IndexedNode(
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
                    checked = node.isCheckable.takeIf { it }?.let {
                        if (Build.VERSION.SDK_INT >= 36) {
                            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
                        } else {
                            @Suppress("DEPRECATION")
                            node.isChecked
                        }
                    },
                    selected = node.isSelected,
                    hint = hint,
                    clickTarget = clickTarget,
                    longClickTarget = longClickTarget,
                    scrollTarget = scrollTarget,
                )
            }
            for (index in 0 until node.childCount) {
                ensureActive()
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
                    ensureActive = ensureActive,
                    clickableAncestor = clickTarget,
                    longClickableAncestor = longClickTarget,
                    scrollableAncestor = scrollTarget,
                )
            }
        } finally {
            traversal.activePath.remove(node)
        }
    }
}

internal fun AccessibilityNodeInfo.boundsInScreenRect(): Rect =
    Rect().also { getBoundsInScreen(it) }

/**
 * 输入框的真实内容。空框显示提示文字时 text 返回的是提示语，若当作已有内容，
 * 追加输入会把提示拼进结果，清空后读回校验也会误报失败。
 */
internal fun AccessibilityNodeInfo.realText(): String =
    if (isShowingHintText) "" else text?.toString().orEmpty()

internal fun AccessibilityNodeInfo.isUsableInputTarget(): Boolean =
    isEditable && isEnabled && isVisibleToUser

internal fun AccessibilityNodeInfo.incrementalTextValidationError(): AgentAccessibilityService.NodeActionResult? {
    val currentText = text
    if (isPassword || currentText == null) {
        return AgentAccessibilityService.NodeActionResult.failure(
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
        return AgentAccessibilityService.NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "当前输入框未提供可靠光标或选区；请用 replace_text 提供完整值",
        )
    }
    return null
}
