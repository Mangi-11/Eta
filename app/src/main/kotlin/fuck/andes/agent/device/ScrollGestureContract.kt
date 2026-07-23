package fuck.andes.agent.device

import android.graphics.Rect
import kotlin.math.roundToInt

internal enum class ScrollAxis {
    HORIZONTAL,
    VERTICAL,
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

    fun gestureWithin(bounds: Rect): ScrollGesture? {
        if (bounds.isEmpty) return null

        val axisStart = if (axis == ScrollAxis.VERTICAL) bounds.top else bounds.left
        val axisEndExclusive = if (axis == ScrollAxis.VERTICAL) bounds.bottom else bounds.right
        if (axisEndExclusive - axisStart < MIN_GESTURE_SPAN_PX) return null

        val near = pointOnAxis(axisStart, axisEndExclusive, NEAR_FRACTION)
        val far = pointOnAxis(axisStart, axisEndExclusive, FAR_FRACTION)
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
        private const val NEAR_FRACTION = 0.2f
        private const val FAR_FRACTION = 0.8f

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
