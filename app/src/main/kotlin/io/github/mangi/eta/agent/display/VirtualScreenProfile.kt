package io.github.mangi.eta.agent.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.min
import kotlin.math.roundToInt

internal data class VirtualScreenProfile(val width: Int, val height: Int, val density: Int) {
    companion object {
        const val MAX_WIDTH = 1440
        const val MAX_HEIGHT = 3200
        const val MAX_DENSITY = 640

        @Suppress("DEPRECATION")
        fun from(context: Context): VirtualScreenProfile {
            val metrics = DisplayMetrics()
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.getRealMetrics(metrics)
            return fit(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }

        /** Scale all three values together so the app keeps the phone's dp size and aspect ratio. */
        fun fit(width: Int, height: Int, density: Int): VirtualScreenProfile {
            if (width < 320 || height < 480 || density <= 0) return VirtualScreenProfile(720, 1600, 320)
            val scale = min(1.0, min(MAX_DENSITY.toDouble() / density,
                min(MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)))
            return VirtualScreenProfile(
                (width * scale).roundToInt().coerceIn(320, MAX_WIDTH),
                (height * scale).roundToInt().coerceIn(480, MAX_HEIGHT),
                (density * scale).roundToInt().coerceIn(120, MAX_DENSITY),
            )
        }
    }
}
