package io.github.mangi.eta.agent.display

import kotlin.math.min

/** Fits the full frame, rotating landscape content clockwise in a portrait viewer. */
internal data class VirtualScreenViewport(
    val left: Float,
    val top: Float,
    val scale: Float,
    val screenWidth: Int,
    val screenHeight: Int,
    val rotatesClockwise: Boolean = false,
) {
    data class Point(val x: Int, val y: Int)
    data class ViewPoint(val x: Float, val y: Float)

    fun toDisplay(x: Float, y: Float, clamp: Boolean = false): Point? {
        if (!x.isFinite() || !y.isFinite()) return null
        val dx = (x - left) / scale
        val dy = (y - top) / scale
        val previewWidth = if (rotatesClockwise) screenHeight else screenWidth
        val previewHeight = if (rotatesClockwise) screenWidth else screenHeight
        if (!clamp && (dx < 0 || dy < 0 || dx >= previewWidth || dy >= previewHeight)) return null
        val px = dx.toInt().coerceIn(0, previewWidth - 1)
        val py = dy.toInt().coerceIn(0, previewHeight - 1)
        return if (rotatesClockwise) Point(py, screenHeight - 1 - px) else Point(px, py)
    }

    /** Map pixel centers so an indicator and a touch refer to the same display pixel. */
    fun toView(x: Int, y: Int): ViewPoint = if (rotatesClockwise) {
        ViewPoint(left + (screenHeight - y - 0.5f) * scale, top + (x + 0.5f) * scale)
    } else {
        ViewPoint(left + (x + 0.5f) * scale, top + (y + 0.5f) * scale)
    }

    companion object {
        fun previewAspectRatio(
            viewWidth: Float,
            viewHeight: Float,
            screenWidth: Int,
            screenHeight: Int,
        ): Float = if (shouldRotateClockwise(viewWidth, viewHeight, screenWidth, screenHeight)) {
            screenHeight.toFloat() / screenWidth
        } else {
            screenWidth.toFloat() / screenHeight
        }

        fun fit(
            viewWidth: Int,
            viewHeight: Int,
            screenWidth: Int,
            screenHeight: Int,
        ): VirtualScreenViewport? {
            if (min(min(viewWidth, viewHeight), min(screenWidth, screenHeight)) <= 0) return null
            val rotatesClockwise = shouldRotateClockwise(
                viewWidth.toFloat(), viewHeight.toFloat(), screenWidth, screenHeight,
            )
            val previewWidth = if (rotatesClockwise) screenHeight else screenWidth
            val previewHeight = if (rotatesClockwise) screenWidth else screenHeight
            val scale = min(viewWidth.toFloat() / previewWidth, viewHeight.toFloat() / previewHeight)
            return VirtualScreenViewport(
                left = (viewWidth - previewWidth * scale) / 2,
                top = (viewHeight - previewHeight * scale) / 2,
                scale = scale,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                rotatesClockwise = rotatesClockwise,
            )
        }

        private fun shouldRotateClockwise(
            viewWidth: Float,
            viewHeight: Float,
            screenWidth: Int,
            screenHeight: Int,
        ): Boolean = screenWidth > screenHeight && viewHeight > viewWidth
    }
}
