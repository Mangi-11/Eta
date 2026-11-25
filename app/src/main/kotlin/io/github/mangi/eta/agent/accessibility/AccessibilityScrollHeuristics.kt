package io.github.mangi.eta.agent.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import io.github.mangi.eta.agent.device.RootScrollMotionContract
import io.github.mangi.eta.agent.device.ScrollAxis
import io.github.mangi.eta.agent.device.ScrollAxisContract
import io.github.mangi.eta.agent.device.ScrollDirection
import io.github.mangi.eta.agent.device.ScrollEvidenceContract

internal data class ScrollCandidate(
    val node: AccessibilityNodeInfo,
    val supportRank: Int,
    val depth: Int,
    val area: Long,
)

internal data class ScrollAnchor(
    val key: String,
    val centerX: Int,
    val centerY: Int,
)

internal data class ScrollMethod(
    val actionId: Int,
    val name: String,
    val args: Bundle? = null,
)

internal data class ScrollSignal(
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

internal data class ScrollTargetIdentity(
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

/**
 * 无障碍滚动容器筛选、动作选择、边界判定与前后内容锚点位移推断的纯启发式策略集合。
 */
internal object AccessibilityScrollHeuristics {

    private const val MAX_UI_TREE_DEPTH = 24
    private const val MAX_SCROLL_SEARCH_NODES = 512
    private const val SCROLL_ANCHOR_NODE_LIMIT = 48

    private val SCROLL_ACTION_IDS = setOf(
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id,
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

    fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
        ensureActive: () -> Unit,
        clippedBounds: (AccessibilityNodeInfo) -> Rect,
    ): AccessibilityNodeInfo {
        val candidates = mutableListOf<ScrollCandidate>()
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            ensureActive()
            if (
                depth > MAX_UI_TREE_DEPTH ||
                visitedNodes >= MAX_SCROLL_SEARCH_NODES ||
                !activePath.add(node)
            ) return
            visitedNodes++
            try {
                if (!node.isVisibleToUser) return
                if (node.isEnabled && node.hasScrollCapability()) {
                    val bounds = clippedBounds(node)
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
                    ensureActive()
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

    fun AccessibilityNodeInfo.hasScrollCapability(): Boolean {
        if (isScrollable) return true
        val actions = supportedActionIds()
        return SCROLL_ACTION_IDS.any(actions::contains)
    }

    fun chooseScrollMethod(
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

    fun isAtScrollBoundary(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
        signal: ScrollSignal? = null,
    ): Boolean {
        if (signal != null && signal.axisDelta(direction.axis) != null) {
            when (direction) {
                ScrollDirection.UP -> if (signal.maxScrollY > 0 && signal.scrollY <= 0) return true
                ScrollDirection.DOWN -> if (signal.maxScrollY > 0 && signal.scrollY >= signal.maxScrollY) return true
                ScrollDirection.LEFT -> if (signal.maxScrollX > 0 && signal.scrollX <= 0) return true
                ScrollDirection.RIGHT -> if (signal.maxScrollX > 0 && signal.scrollX >= signal.maxScrollX) return true
            }
        }
        if (chooseScrollMethod(node, direction) != null) return false
        return chooseScrollMethod(node, direction.opposite()) != null
    }

    fun scrollContentAnchors(
        root: AccessibilityNodeInfo,
        ensureActive: () -> Unit,
    ): List<ScrollAnchor> {
        val anchors = ArrayList<ScrollAnchor>(SCROLL_ANCHOR_NODE_LIMIT)
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        val rootBounds = root.boundsInScreenRect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            ensureActive()
            if (
                depth > MAX_UI_TREE_DEPTH ||
                anchors.size >= SCROLL_ANCHOR_NODE_LIMIT ||
                !activePath.add(node)
            ) return
            try {
                if (!node.isVisibleToUser) return
                val bounds = node.boundsInScreenRect()
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
                    ensureActive()
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

    fun inferScrollAnchorDelta(
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

    internal fun AccessibilityNodeInfo.exposesOnlyOppositeAxis(
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

    private fun AccessibilityNodeInfo.supportedActionIds(): Set<Int> =
        actionList.mapTo(hashSetOf()) { action -> action.id }

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
}
