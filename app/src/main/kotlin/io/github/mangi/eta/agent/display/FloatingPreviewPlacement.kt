package io.github.mangi.eta.agent.display

import kotlin.math.abs

/** Window geometry shared by dragging, edge folding and configuration changes. */
internal object FloatingPreviewPlacement {
    data class Position(val x: Int, val y: Int, val edge: Edge?)
    enum class Edge { LEFT, RIGHT }

    fun constrain(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int,
        topInset: Int = 0, bottomInset: Int = 0, edgeThreshold: Int = 0): Position {
        val right = (screenWidth - width).coerceAtLeast(0)
        val top = topInset.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        val bottom = (screenHeight - bottomInset - height).coerceAtLeast(top)
        val boundedX = x.coerceIn(0, right)
        val edge = when {
            boundedX <= edgeThreshold -> Edge.LEFT
            abs(right - boundedX) <= edgeThreshold -> Edge.RIGHT
            else -> null
        }
        return Position(boundedX, y.coerceIn(top, bottom), edge)
    }
}
