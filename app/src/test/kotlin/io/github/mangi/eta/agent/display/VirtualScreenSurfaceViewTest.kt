package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenSurfaceViewTest {
    @Test
    fun tapsAndLongPressesUseScaledDisplayCoordinates() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 1000)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 1100)
        assertEquals("tap", inputs[0].getString("action"))
        assertEquals(360, inputs[0].getInt("x"))
        assertEquals(640, inputs[0].getInt("y"))
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 2000)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 2800)
        assertEquals("long_press", inputs[1].getString("action"))
        assertEquals(800, inputs[1].getInt("durationMs"))
        view.clearFrame()
    }

    @Test
    fun swipesStayInTheDisplayWhenFingerLeavesPreview() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 1000)
        touch(view, MotionEvent.ACTION_UP, 500f, -100f, 1500)
        assertEquals("swipe", inputs.single().getString("action"))
        assertEquals(0, inputs.single().getInt("y2"))
        view.clearFrame()
    }

    @Test
    fun cancelledAndViewOnlyTouchesDoNotSendInput() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 40f, 1000)
        touch(view, MotionEvent.ACTION_UP, 500f, 40f, 1100)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 2000)
        touch(view, MotionEvent.ACTION_CANCEL, 500f, 1000f, 2100)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 2200)
        view.touchEnabled = false
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 3000)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 3100)
        assertTrue(inputs.isEmpty())
        view.clearFrame()
    }

    @Test
    fun sessionReplacementAndRemovedFramesCannotReceiveOldTouch() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 1000)
        view.setFrame(
            Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888),
            VirtualDisplayInfo("replacement", "next-run", 5, 720, 1280)
        )
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 1100)
        view.clearFrame()
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 2000)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 2100)
        assertTrue(inputs.isEmpty())
    }

    private fun view(inputs: MutableList<JSONObject>) =
        VirtualScreenSurfaceView(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 1000, 2000)
            setFrame(
                Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888),
                VirtualDisplayInfo("session", "run", 3, 720, 1280)
            )
            onInput = { session, args -> assertEquals("session", session); inputs += args }
        }

    private fun touch(view: VirtualScreenSurfaceView, action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(1000, time, action, x, y, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
