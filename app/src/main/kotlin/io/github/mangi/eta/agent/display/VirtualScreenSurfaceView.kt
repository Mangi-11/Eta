package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.mangi.eta.agent.overlay.GestureIndicatorHost
import org.json.JSONObject
import kotlin.math.hypot

internal class VirtualScreenSurfaceView(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
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
        info = display
        image.setImageBitmap(next)
        bitmap?.takeIf { it !== next }?.recycle()
        bitmap = next
    }

    fun clearFrame() {
        info = null
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        cancelTouch()
    }

    fun showGesture(gesture: VirtualScreenGesture?) {
        val display = info ?: return
        if (gesture == null || gesture.id == lastGesture || gesture.sessionId != display.sessionId) return
        lastGesture = gesture.id
        if (SystemClock.elapsedRealtime() - gesture.startedAt > gesture.durationMs + 700) return
        val viewport = viewport() ?: return
        val x = viewport.left + gesture.x * viewport.scale
        val y = viewport.top + gesture.y * viewport.scale
        if (gesture.action == "swipe") {
            indicators.showSwipe(
                x, y, viewport.left + gesture.endX * viewport.scale,
                viewport.top + gesture.endY * viewport.scale, gesture.durationMs
            )
        } else indicators.showPress(x, y, gesture.action == "long_press", gesture.durationMs)
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
