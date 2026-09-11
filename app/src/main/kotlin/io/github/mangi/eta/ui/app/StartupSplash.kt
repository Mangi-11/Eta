package io.github.mangi.eta.ui.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AnimationUtils
import android.window.SplashScreenView
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.mangi.eta.R
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

internal fun ComponentActivity.installStartupSplash(
    isContentReady: () -> Boolean,
) {
    StartupSplash(this, isContentReady).install()
}

private class StartupSplash(
    private val activity: ComponentActivity,
    private val isContentReady: () -> Boolean,
) : DefaultLifecycleObserver, ViewTreeObserver.OnPreDrawListener {
    private val content = activity.findViewById<View>(android.R.id.content)
    private var splashView: SplashScreenView? = null
    private var exitAnimator: ValueAnimator? = null
    private var hasStopped = false

    fun install() {
        activity.lifecycle.addObserver(this)
        // 外观配置异步读取完成后才允许首帧，避免系统启动画面先退到空白窗口。
        content.viewTreeObserver.addOnPreDrawListener(this)
        activity.splashScreen.setOnExitAnimationListener(::onExit)
    }

    override fun onPreDraw(): Boolean {
        if (!isContentReady()) return false
        content.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }

    private fun onExit(view: SplashScreenView) {
        splashView = view
        if (hasStopped || !ValueAnimator.areAnimatorsEnabled() ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            dismiss()
            return
        }

        val durationScale = ValueAnimator.getDurationScale()
        val remaining = remainingSplashAnimationMillis(
            animationStart = view.iconAnimationStart,
            animationDuration = view.iconAnimationDuration,
            now = Instant.now(),
            durationScale = durationScale,
        )
        if (!durationScale.isFinite() || durationScale <= 0f) {
            dismiss()
            return
        }
        val background = view.background.mutate()
        val backgroundAlpha = background.alpha
        val icon = view.iconView
        val iconAlpha = icon?.alpha ?: 1f
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = activity.resources.getInteger(R.integer.splash_exit_duration).toLong()
            // ValueAnimator 会自行应用系统倍率；首页就绪较晚时仍保留完整的短过渡。
            startDelay = (remaining / durationScale).toLong().minus(duration).coerceAtLeast(0L)
            interpolator = AnimationUtils.loadInterpolator(activity, R.interpolator.splash_exit)
            addUpdateListener {
                val opacity = it.animatedValue as Float
                background.alpha = (backgroundAlpha * opacity).roundToInt()
                // 图标可能由独立 Surface 绘制，单独赋绝对透明度，避免父层透明度逐帧累乘。
                icon?.alpha = iconAlpha * opacity
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dismiss()
                }
            })
        }
        exitAnimator = animator
        animator.start()
    }

    private fun dismiss() {
        exitAnimator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        exitAnimator = null
        val view = splashView
        splashView = null
        view?.remove()
    }

    override fun onStop(owner: LifecycleOwner) {
        hasStopped = true
        dismiss()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dismiss()
        if (content.viewTreeObserver.isAlive) {
            content.viewTreeObserver.removeOnPreDrawListener(this)
        }
        activity.splashScreen.clearOnExitAnimationListener()
        activity.lifecycle.removeObserver(this)
    }
}

internal fun remainingSplashAnimationMillis(
    animationStart: Instant?,
    animationDuration: Duration?,
    now: Instant,
    durationScale: Float,
): Long {
    if (animationStart == null || animationDuration == null ||
        !durationScale.isFinite() || durationScale <= 0f
    ) return 0L

    val duration = (animationDuration.toMillis() * durationScale).toLong().coerceAtLeast(0L)
    val elapsed = Duration.between(animationStart, now).toMillis()
    return (duration - elapsed).coerceIn(0L, duration)
}
