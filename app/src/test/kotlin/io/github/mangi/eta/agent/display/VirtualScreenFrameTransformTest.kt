package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VirtualScreenFrameTransformTest {
    @Test
    fun allRotationsReturnUprightPixelsInLogicalCoordinateOrder() {
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
        )
        repeat(4) { rotation ->
            val source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, Color.RED)
                setPixel(1, 0, Color.GREEN)
                setPixel(0, 2, Color.BLUE)
                setPixel(1, 2, Color.YELLOW)
            }
            val output = VirtualScreenFrameTransform.upright(source, rotation)
            try {
                assertEquals(if (rotation % 2 == 0) 2 else 3, output.width)
                assertEquals(if (rotation % 2 == 0) 3 else 2, output.height)
                assertEquals(expected[rotation], listOf(output.getPixel(0, 0), output.getPixel(output.width - 1, 0),
                    output.getPixel(0, output.height - 1), output.getPixel(output.width - 1, output.height - 1)))
                assertFalse(source.isRecycled)
            } finally {
                if (output !== source) output.recycle()
                source.recycle()
            }
        }
    }
}
