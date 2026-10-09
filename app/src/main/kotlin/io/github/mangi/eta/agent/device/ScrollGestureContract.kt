package io.github.mangi.eta.agent.device

import android.graphics.Rect
import kotlin.math.roundToInt

internal enum class ScrollAxis {
    HORIZONTAL,
    VERTICAL,
}

/**
 * 滚动幅度。系统滚动动作固定翻约一屏，不接受距离参数；SMALL 因此只走手势，
 * 用目标区域约四分之一的位移露出少量内容，适合微调到某一项。
 */
internal enum class ScrollAmount(val nearFraction: Float, val farFraction: Float) {
    SMALL(0.38f, 0.62f),
    PAGE(0.2f, 0.8f),
    ;

    companion object {
        fun parse(value: String): ScrollAmount? =
            if (value.isBlank()) PAGE else entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * 滚动方向表示希望显示的新内容所在方向，而不是手指移动方向。
 */
internal enum class ScrollDirection(
    val axis: ScrollAxis,
    val scrollDeltaSign: Int,
) {
    UP(ScrollAxis.VERTICAL, -1),
    DOWN(ScrollAxis.VERTICAL, 1),
    LEFT(ScrollAxis.HORIZONTAL, -1),
    RIGHT(ScrollAxis.HORIZONTAL, 1),
    ;

    fun gestureWithin(bounds: Rect, amount: ScrollAmount = ScrollAmount.PAGE): ScrollGesture? {
        if (bounds.isEmpty) return null

        val axisStart = if (axis == ScrollAxis.VERTICAL) bounds.top else bounds.left
        val axisEndExclusive = if (axis == ScrollAxis.VERTICAL) bounds.bottom else bounds.right
        if (axisEndExclusive - axisStart < MIN_GESTURE_SPAN_PX) return null

        val near = pointOnAxis(axisStart, axisEndExclusive, amount.nearFraction)
        val far = pointOnAxis(axisStart, axisEndExclusive, amount.farFraction)
        val perpendicular = if (axis == ScrollAxis.VERTICAL) {
            midpoint(bounds.left, bounds.right)
        } else {
            midpoint(bounds.top, bounds.bottom)
        }

        val startAxis = if (scrollDeltaSign > 0) far else near
        val endAxis = if (scrollDeltaSign > 0) near else far
        return if (axis == ScrollAxis.VERTICAL) {
            ScrollGesture(
                start = ScrollPoint(perpendicular, startAxis),
                end = ScrollPoint(perpendicular, endAxis),
            )
        } else {
            ScrollGesture(
                start = ScrollPoint(startAxis, perpendicular),
                end = ScrollPoint(endAxis, perpendicular),
            )
        }
    }

    companion object {
        fun parse(value: String): ScrollDirection? =
            entries.firstOrNull { direction -> direction.name.equals(value.trim(), ignoreCase = true) }

        private const val MIN_GESTURE_SPAN_PX = 2

        private fun pointOnAxis(start: Int, endExclusive: Int, fraction: Float): Int {
            val endInclusive = endExclusive - 1
            return (start + (endInclusive - start) * fraction)
                .roundToInt()
                .coerceIn(start, endInclusive)
        }

        private fun midpoint(start: Int, endExclusive: Int): Int =
            (start + (endExclusive - start) / 2).coerceAtMost(endExclusive - 1)
    }
}

internal data class ScrollPoint(
    val x: Int,
    val y: Int,
)

internal data class ScrollGesture(
    val start: ScrollPoint,
    val end: ScrollPoint,
)
