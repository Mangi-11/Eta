package io.github.mangi.eta.agent.display

import android.graphics.Bitmap

internal object VirtualScreenImageFingerprint {
    fun of(bitmap: Bitmap): Long {
        var bits = 0L
        for (row in 0 until 8) {
            val y = ((row + 0.5) * bitmap.height * 0.9 / 8 + bitmap.height * 0.06)
                .toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until 8) {
                fun luminance(index: Int): Int {
                    val x = ((index + 0.5) * bitmap.width / 9).toInt().coerceIn(0, bitmap.width - 1)
                    val pixel = bitmap.getPixel(x, y)
                    return ((pixel shr 16) and 255) * 299 + ((pixel shr 8) and 255) * 587 + (pixel and 255) * 114
                }
                if (luminance(column) > luminance(column + 1)) bits = bits or (1L shl (row * 8 + column))
            }
        }
        return bits
    }
}
