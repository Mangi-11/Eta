package io.github.mangi.eta.agent.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import kotlin.math.min

object GestureIndicator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeIndicator: ActiveIndicator? = null

    fun showTap(context: Context, x: Int, y: Int) {
        showPress(context, x, y, PressKind.TAP, holdDurationMs = TAP_HOLD_DURATION_MS)
    }

    fun showLongPress(context: Context, x: Int, y: Int, durationMs: Int) {
        val holdDurationMs = (durationMs.toLong() - POP_IN_DURATION_MS - FADE_OUT_DURATION_MS)
            .coerceIn(MIN_LONG_PRESS_HOLD_MS, MAX_LONG_PRESS_HOLD_MS)
        showPress(context, x, y, PressKind.LONG_PRESS, holdDurationMs)
    }

    private fun showPress(
        context: Context,
        x: Int,
        y: Int,
        kind: PressKind,
        holdDurationMs: Long,
    ) {
        mainHandler.post {
            val service = AgentAccessibilityService.current()
            val overlayContext = service ?: context
            val overlayType = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val indicatorView = PressIndicatorView(overlayContext, kind, holdDurationMs)

            val density = overlayContext.resources.displayMetrics.density
            val sizePx = (INDICATOR_CONTAINER_SIZE_DP * density).toInt()

            val lp = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - sizePx / 2
                this.y = y - sizePx / 2
            }

            attachIndicator(wm, indicatorView, lp)
        }
    }

    fun showSwipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        mainHandler.post {
            val service = AgentAccessibilityService.current()
            val overlayContext = service ?: context
            val overlayType = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val indicatorView = SwipeIndicatorView(overlayContext, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = 0
                this.y = 0
            }

            attachIndicator(wm, indicatorView, lp)
        }
    }

    private fun attachIndicator(
        windowManager: WindowManager,
        view: AnimatedIndicatorView,
        layoutParams: WindowManager.LayoutParams,
    ) {
        dismissActiveIndicator()
        runCatching { windowManager.addView(view, layoutParams) }
            .onSuccess {
                val indicator = ActiveIndicator(windowManager, view)
                activeIndicator = indicator
                view.startIndicatorAnimation {
                    mainHandler.post { finishIndicator(indicator) }
                }
            }
    }

    private fun dismissActiveIndicator() {
        activeIndicator?.let(::finishIndicator)
    }

    private fun finishIndicator(indicator: ActiveIndicator) {
        if (activeIndicator === indicator) {
            activeIndicator = null
        }
        indicator.view.cancelIndicatorAnimation()
        runCatching { indicator.windowManager.removeView(indicator.view) }
    }

    private class ActiveIndicator(
        val windowManager: WindowManager,
        val view: AnimatedIndicatorView,
    )

    private const val INDICATOR_CONTAINER_SIZE_DP = 60f
    private const val POP_IN_DURATION_MS = 200L
    private const val TAP_HOLD_DURATION_MS = 100L
    private const val FADE_OUT_DURATION_MS = 180L
    private const val MIN_LONG_PRESS_HOLD_MS = 240L
    private const val MAX_LONG_PRESS_HOLD_MS = 620L
}

/** Same indicator artwork, contained in the virtual-screen viewer rather than a system overlay. */
internal class GestureIndicatorHost(context: Context) : FrameLayout(context) {
    private var active: AnimatedIndicatorView? = null

    fun showPress(x: Float, y: Float, longPress: Boolean, durationMs: Int) {
        val view = PressIndicatorView(context, if (longPress) PressKind.LONG_PRESS else PressKind.TAP,
            if (longPress) (durationMs.toLong() - 380).coerceIn(240, 620) else 100)
        val size = (60 * resources.displayMetrics.density).toInt()
        attach(view, LayoutParams(size, size).apply {
            leftMargin = x.toInt() - size / 2
            topMargin = y.toInt() - size / 2
        })
    }

    fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        attach(SwipeIndicatorView(context, x1, y1, x2, y2, durationMs),
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun attach(view: AnimatedIndicatorView, params: LayoutParams) {
        clear()
        active = view
        addView(view, params)
        view.startIndicatorAnimation { if (active === view) clear() }
    }

    fun clear() {
        active?.cancelIndicatorAnimation()
        active = null
        removeAllViews()
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}

private enum class PressKind { TAP, LONG_PRESS }

private abstract class AnimatedIndicatorView(context: Context) : View(context) {
    abstract fun startIndicatorAnimation(onFinished: () -> Unit)
    abstract fun cancelIndicatorAnimation()
}

private class PressIndicatorView(
    context: Context,
    private val kind: PressKind,
    private val holdDurationMs: Long,
) : AnimatedIndicatorView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    init {
        paint.color = 0xFF2879FB.toInt() // Premium tech blue
    }

    override fun startIndicatorAnimation(onFinished: () -> Unit) {
        alpha = 0f
        scaleX = 0.5f
        scaleY = 0.5f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200L)
            .setInterpolator(OvershootInterpolator(2.0f))
            .withEndAction {
                animate()
                    .alpha(0f)
                    .scaleX(if (kind == PressKind.LONG_PRESS) 1.12f else 1.06f)
                    .scaleY(if (kind == PressKind.LONG_PRESS) 1.12f else 1.06f)
                    .setDuration(180L)
                    .setStartDelay(holdDurationMs)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { onFinished() }
                    .start()
            }
            .start()
    }

    override fun cancelIndicatorAnimation() {
        animate().cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val ringRadius = 20f * density

        // 与目标控件保持清晰的空心边界，内部只给一层很轻的落点着色。
        paint.style = Paint.Style.FILL
        paint.alpha = if (kind == PressKind.LONG_PRESS) 0x38 else 0x2A
        canvas.drawCircle(cx, cy, ringRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (kind == PressKind.LONG_PRESS) 2.5f * density else 2f * density
        paint.alpha = 255
        canvas.drawCircle(cx, cy, ringRadius, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = 230
        canvas.drawCircle(cx, cy, if (kind == PressKind.LONG_PRESS) 4f * density else 3f * density, paint)

        if (kind == PressKind.LONG_PRESS) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.alpha = 110
            canvas.drawCircle(cx, cy, 25f * density, paint)
        }
    }
}

private class SwipeIndicatorView(
    context: Context,
    private val startX: Float,
    private val startY: Float,
    private val endX: Float,
    private val endY: Float,
    private val durationMs: Int
) : AnimatedIndicatorView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var alphaVal = 1f
    private var animator: AnimatorSet? = null

    init {
        paint.color = 0xFF2879FB.toInt() // Premium tech blue
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun startIndicatorAnimation(onFinished: () -> Unit) {
        val progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(300).toLong()
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
        }
        val alphaAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160
            addUpdateListener { animation ->
                alphaVal = animation.animatedValue as Float
                invalidate()
            }
        }
        animator = AnimatorSet().apply {
            playSequentially(progressAnimator, alphaAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }
            })
            start()
        }
    }

    override fun cancelIndicatorAnimation() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density

        // Current swipe head point
        val curX = startX + (endX - startX) * progress
        val curY = startY + (endY - startY) * progress

        // 先画极淡的路径预告，再让实线和指尖同步前进，避免轨迹看起来比手势先发生。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.alpha = (alphaVal * 0.12f * 255).toInt()
        canvas.drawLine(startX, startY, endX, endY, paint)

        paint.strokeWidth = 3f * density
        paint.alpha = (alphaVal * 0.62f * 255).toInt()
        canvas.drawLine(startX, startY, curX, curY, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = (alphaVal * 0.9f * 255).toInt()
        canvas.drawCircle(curX, curY, 7f * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.alpha = (alphaVal * 0.48f * 255).toInt()
        canvas.drawCircle(curX, curY, 13f * density, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = (alphaVal * 0.5f * 255).toInt()
        canvas.drawCircle(startX, startY, min(4f * density, 4f * density * (1f - progress) + density), paint)
    }
}
