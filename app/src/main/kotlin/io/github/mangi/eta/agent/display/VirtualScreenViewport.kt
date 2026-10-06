package io.github.mangi.eta.agent.display

import kotlin.math.min

/** FIT_CENTER mapping shared by user input and gesture indicators. Letterboxing is not interactive. */
internal data class VirtualScreenViewport(
    val left: Float,
    val top: Float,
    val scale: Float,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    data class Point(val x: Int, val y: Int)

    fun toDisplay(x: Float, y: Float, clamp: Boolean = false): Point? {
        if (!x.isFinite() || !y.isFinite()) return null
        val dx = (x - left) / scale
        val dy = (y - top) / scale
        if (!clamp && (dx < 0 || dy < 0 || dx >= screenWidth || dy >= screenHeight)) return null
        return Point(
            dx.toInt().coerceIn(0, screenWidth - 1),
            dy.toInt().coerceIn(0, screenHeight - 1)
        )
    }

    companion object {
        fun fit(
            viewWidth: Int,
            viewHeight: Int,
            screenWidth: Int,
            screenHeight: Int
        ): VirtualScreenViewport? {
            if (min(min(viewWidth, viewHeight), min(screenWidth, screenHeight)) <= 0) return null
            val scale = min(viewWidth.toFloat() / screenWidth, viewHeight.toFloat() / screenHeight)
            return VirtualScreenViewport(
                (viewWidth - screenWidth * scale) / 2,
                (viewHeight - screenHeight * scale) / 2, scale, screenWidth, screenHeight
            )
        }
    }
}
