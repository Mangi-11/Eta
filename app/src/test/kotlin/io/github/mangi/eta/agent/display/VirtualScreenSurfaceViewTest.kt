package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun clockwiseLandscapePreviewMapsTouchesAndRejectsItsLetterboxing() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        view.setFrame(Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888),
            VirtualDisplayInfo("session", "run", 3, 1280, 720, rotation = 1))
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 1000)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 1100)
        assertEquals(640, inputs.single().getInt("x"))
        assertEquals(359, inputs.single().getInt("y"))
        assertEquals(1280, inputs.single().getInt("expectedWidth"))
        assertEquals(720, inputs.single().getInt("expectedHeight"))
        assertEquals(1, inputs.single().getInt("expectedRotation"))
        touch(view, MotionEvent.ACTION_DOWN, 500f, 100f, 2000)
        touch(view, MotionEvent.ACTION_UP, 500f, 100f, 2100)
        assertEquals(1, inputs.size)
        view.clearFrame()
    }

    @Test
    fun clockwisePreviewRendersPixelsAtTheCoordinatesUsedForInput() {
        val inputs = mutableListOf<JSONObject>()
        val view = VirtualScreenSurfaceView(RuntimeEnvironment.getApplication())
        resize(view, 200, 400)
        val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 4) {
                setPixel(x, 0, if (x < 2) Color.RED else Color.GREEN)
                setPixel(x, 1, if (x < 2) Color.BLUE else Color.YELLOW)
            }
        }
        view.setFrame(source, VirtualDisplayInfo("session", "run", 3, 4, 2, rotation = 1))
        view.onInput = { _, args -> inputs += args }
        val portrait = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        val landscape = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(portrait))
            assertEquals(Color.BLUE, portrait.getPixel(25, 25))
            assertEquals(Color.RED, portrait.getPixel(175, 25))
            assertEquals(Color.YELLOW, portrait.getPixel(25, 375))
            assertEquals(Color.GREEN, portrait.getPixel(175, 375))
            touch(view, MotionEvent.ACTION_DOWN, 150f, 50f, 1000)
            touch(view, MotionEvent.ACTION_UP, 150f, 50f, 1100)
            assertEquals(0, inputs.single().getInt("x"))
            assertEquals(0, inputs.single().getInt("y"))
            resize(view, 400, 200)
            view.draw(Canvas(landscape))
            assertEquals(Color.RED, landscape.getPixel(25, 25))
            assertEquals(Color.GREEN, landscape.getPixel(375, 25))
            assertEquals(Color.BLUE, landscape.getPixel(25, 175))
            assertEquals(Color.YELLOW, landscape.getPixel(375, 175))
        } finally {
            portrait.recycle()
            landscape.recycle()
            view.clearFrame()
        }
    }

    @Test
    fun verticalSwipeOnClockwisePreviewBecomesHorizontalDisplaySwipe() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        view.setFrame(Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888),
            VirtualDisplayInfo("session", "run", 3, 1600, 800, rotation = 1))
        touch(view, MotionEvent.ACTION_DOWN, 750f, 400f, 1000)
        touch(view, MotionEvent.ACTION_UP, 750f, 1600f, 1500)
        assertEquals("swipe", inputs.single().getString("action"))
        assertEquals(320, inputs.single().getInt("x1"))
        assertEquals(199, inputs.single().getInt("y1"))
        assertEquals(1280, inputs.single().getInt("x2"))
        assertEquals(199, inputs.single().getInt("y2"))
        view.clearFrame()
    }

    @Test
    fun clockwisePreviewPlacesPressIndicatorAtTheRotatedPixel() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        view.setFrame(Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888),
            VirtualDisplayInfo("session", "run", 3, 1600, 800, rotation = 1))
        view.showGesture(VirtualScreenGesture(1, "session", "tap", 320, 199,
            startedAt = SystemClock.elapsedRealtime()))
        val host = view.getChildAt(1) as FrameLayout
        val indicator = host.getChildAt(0)
        val params = indicator.layoutParams as FrameLayout.LayoutParams
        assertEquals(750, params.leftMargin + params.width / 2)
        assertEquals(400, params.topMargin + params.height / 2)
        resize(view, 2000, 1000)
        assertEquals(0, host.childCount)
        view.clearFrame()
    }

    @Test
    fun viewerResizeDuringTouchCancelsGesture() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 1000)
        resize(view, 2000, 1000)
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 1100)
        assertTrue(inputs.isEmpty())
        view.clearFrame()
    }

    @Test
    fun rotationDuringTouchCancelsGestureEvenWhenSessionAndSizeAreUnchanged() {
        val inputs = mutableListOf<JSONObject>()
        val view = view(inputs)
        touch(view, MotionEvent.ACTION_DOWN, 500f, 1000f, 1000)
        view.setFrame(Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888),
            VirtualDisplayInfo("session", "run", 3, 720, 1280, rotation = 2))
        touch(view, MotionEvent.ACTION_UP, 500f, 1000f, 1100)
        assertTrue(inputs.isEmpty())
        view.clearFrame()
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

    private fun resize(view: VirtualScreenSurfaceView, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
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
