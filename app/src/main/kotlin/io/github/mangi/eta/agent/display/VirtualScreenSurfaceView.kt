package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.mangi.eta.agent.overlay.GestureIndicatorHost
import org.json.JSONObject
import kotlin.math.hypot

internal class VirtualScreenSurfaceView(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.MATRIX }
    private val indicators = GestureIndicatorHost(context)
    private var bitmap: Bitmap? = null
    private var info: VirtualDisplayInfo? = null
    private var lastGesture = 0L
    private var down: MotionEvent? = null
    private var downSession: String? = null
    var onInput: (String, JSONObject) -> Unit = { _, _ -> }
    var touchEnabled = true

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicators, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setFrame(next: Bitmap, display: VirtualDisplayInfo) {
        if (info?.sessionId != display.sessionId || info?.hasSameGeometry(display) != true) {
            cancelTouch()
            indicators.clear()
        }
        info = display
        next.density = Bitmap.DENSITY_NONE
        image.setImageBitmap(next)
        updateImageMatrix()
        bitmap?.takeIf { it !== next }?.recycle()
        bitmap = next
    }

    fun clearFrame() {
        info = null
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        cancelTouch()
        indicators.clear()
        lastGesture = 0L
    }

    fun showGesture(gesture: VirtualScreenGesture?) {
        val display = info ?: return
        if (gesture == null || gesture.id == lastGesture || gesture.sessionId != display.sessionId) return
        lastGesture = gesture.id
        if (SystemClock.elapsedRealtime() - gesture.startedAt > gesture.durationMs + 700) return
        val viewport = viewport() ?: return
        val start = viewport.toView(gesture.x, gesture.y)
        if (gesture.action == "swipe") {
            val end = viewport.toView(gesture.endX, gesture.endY)
            indicators.showSwipe(start.x, start.y, end.x, end.y, gesture.durationMs)
        } else indicators.showPress(start.x, start.y, gesture.action == "long_press", gesture.durationMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled || info == null || bitmap == null) {
            cancelTouch(); return false
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelTouch(); return true
        }
        val viewport = viewport() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                if (viewport.toDisplay(event.x, event.y) == null) return false
                down = MotionEvent.obtain(event)
                downSession = info?.sessionId
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP -> {
                val first = down ?: return true
                val session = downSession
                val start = viewport.toDisplay(first.x, first.y)
                val end = viewport.toDisplay(event.x, event.y, clamp = true)
                if (session != null && session == info?.sessionId && start != null && end != null) {
                    val duration = (event.eventTime - first.eventTime).toInt().coerceIn(100, 3000)
                    val moved = hypot(
                        event.x - first.x,
                        event.y - first.y
                    ) > ViewConfiguration.get(context).scaledTouchSlop
                    val args = if (moved) JSONObject().put("action", "swipe")
                        .put("x1", start.x).put("y1", start.y).put("x2", end.x).put("y2", end.y)
                        .put("durationMs", duration.coerceAtMost(2000))
                    else JSONObject().put(
                        "action",
                        if (duration >= ViewConfiguration.getLongPressTimeout()) "long_press" else "tap"
                    )
                        .put("x", start.x).put("y", start.y).put("durationMs", duration)
                    info?.let { display ->
                        args.put("expectedWidth", display.width).put("expectedHeight", display.height)
                            .put("expectedRotation", display.rotation)
                    }
                    onInput(session, args)
                    performClick()
                }
                cancelTouch()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick(); return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        cancelTouch()
        indicators.clear()
        updateImageMatrix()
    }

    private fun updateImageMatrix() {
        val viewport = viewport() ?: return
        image.imageMatrix = Matrix().apply {
            if (viewport.rotatesClockwise) {
                postRotate(90f)
                postTranslate(viewport.screenHeight.toFloat(), 0f)
            }
            postScale(viewport.scale, viewport.scale)
            postTranslate(viewport.left, viewport.top)
        }
    }

    private fun viewport(): VirtualScreenViewport? = info?.let {
        VirtualScreenViewport.fit(width, height, it.width, it.height)
    }

    private fun cancelTouch() {
        down?.recycle()
        down = null
        downSession = null
    }

    override fun onDetachedFromWindow() {
        clearFrame()
        super.onDetachedFromWindow()
    }
}
