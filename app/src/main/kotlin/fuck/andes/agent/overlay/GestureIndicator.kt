package fuck.andes.agent.overlay

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
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import fuck.andes.agent.accessibility.AgentAccessibilityService

object GestureIndicator {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showTap(context: Context, x: Int, y: Int) {
        mainHandler.post {
            val service = AgentAccessibilityService.current()
            val overlayContext = service ?: context
            val overlayType = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }

            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val indicatorView = TapIndicatorView(overlayContext)

            val density = overlayContext.resources.displayMetrics.density
            val sizePx = (60f * density).toInt()

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

            runCatching {
                wm.addView(indicatorView, lp)
                indicatorView.startAnimation {
                    mainHandler.post {
                        runCatching { wm.removeView(indicatorView) }
                    }
                }
            }
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

            runCatching {
                wm.addView(indicatorView, lp)
                indicatorView.startAnimation {
                    mainHandler.post {
                        runCatching { wm.removeView(indicatorView) }
                    }
                }
            }
        }
    }
}

private class TapIndicatorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 0f
    private var alphaVal = 1f
    private var animator: ValueAnimator? = null

    init {
        paint.color = 0xFF2879FB.toInt() // Premium tech blue
    }

    fun startAnimation(onFinished: () -> Unit) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                scale = fraction
                alphaVal = 1f - fraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = Math.min(cx, cy) * 0.8f
        val density = resources.displayMetrics.density

        // Solid inner circle
        paint.style = Paint.Style.FILL
        paint.alpha = (alphaVal * 0.4f * 255).toInt()
        canvas.drawCircle(cx, cy, maxRadius * scale * 0.5f, paint)

        // Outer ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.alpha = (alphaVal * 255).toInt()
        canvas.drawCircle(cx, cy, maxRadius * scale, paint)
    }
}

private class SwipeIndicatorView(
    context: Context,
    private val startX: Float,
    private val startY: Float,
    private val endX: Float,
    private val endY: Float,
    private val durationMs: Int
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var alphaVal = 1f

    init {
        paint.color = 0xFF2879FB.toInt() // Premium tech blue
    }

    fun startAnimation(onFinished: () -> Unit) {
        val progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(300).toLong()
            addUpdateListener { animation ->
                progress = animation.animatedValue as Float
                invalidate()
            }
        }
        val alphaAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200
            startDelay = progressAnimator.duration
            addUpdateListener { animation ->
                alphaVal = animation.animatedValue as Float
                invalidate()
            }
        }
        AnimatorSet().apply {
            playSequentially(progressAnimator, alphaAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density

        // Current swipe head point
        val curX = startX + (endX - startX) * progress
        val curY = startY + (endY - startY) * progress

        // Draw track line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * density
        paint.alpha = (alphaVal * 0.25f * 255).toInt()
        canvas.drawLine(startX, startY, endX, endY, paint)

        // Draw swipe head solid circle
        paint.style = Paint.Style.FILL
        paint.alpha = (alphaVal * 255).toInt()
        canvas.drawCircle(curX, curY, 12f * density, paint)

        // Draw swipe head outer ripple ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.alpha = (alphaVal * 0.4f * 255).toInt()
        canvas.drawCircle(curX, curY, 20f * density, paint)
    }
}
