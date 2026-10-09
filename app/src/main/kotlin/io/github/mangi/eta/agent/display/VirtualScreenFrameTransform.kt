package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.graphics.Matrix

/** Surface output follows physical axes; return pixels in the display's logical input axes. */
internal object VirtualScreenFrameTransform {
    fun upright(source: Bitmap, rotation: Int): Bitmap {
        require(rotation in 0..3)
        if (rotation == 0) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(-90f * rotation) }, false)
    }
}
