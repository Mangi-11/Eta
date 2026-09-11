package io.github.mangi.eta.ui.app

import android.app.Application
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.View
import androidx.activity.ComponentActivity
import io.github.mangi.eta.R
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StartupSplashTest {
    @Test
    fun `仅在首帧必要配置未就绪时阻止绘制`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        try {
            val activity = controller.get()
            var ready = false
            var checks = 0
            activity.installStartupSplash {
                checks++
                ready
            }
            val observer = activity.findViewById<View>(android.R.id.content).viewTreeObserver

            assertTrue(observer.dispatchOnPreDraw())
            ready = true
            assertFalse(observer.dispatchOnPreDraw())

            ready = false
            assertFalse(observer.dispatchOnPreDraw())
            assertEquals(2, checks)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `准备期间销毁页面会移除绘制拦截`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        var checks = 0
        activity.installStartupSplash {
            checks++
            false
        }
        val observer = activity.findViewById<View>(android.R.id.content).viewTreeObserver
        assertTrue(observer.dispatchOnPreDraw())

        controller.destroy()

        assertFalse(observer.dispatchOnPreDraw())
        assertEquals(1, checks)
    }

    @Test
    fun `系统可解析启动动画与渐变资源`() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(context.getDrawable(R.drawable.ic_splash_animated) is AnimatedVectorDrawable)
    }

    @Test
    fun `首页准备完成后只等待动画尚未播放的部分`() {
        val start = Instant.ofEpochMilli(1000L)
        val duration = Duration.ofMillis(900L)

        assertEquals(600L, remainingSplashAnimationMillis(start, duration, start.plusMillis(300L), 1f))
        assertEquals(0L, remainingSplashAnimationMillis(start, duration, start.plusMillis(1200L), 1f))
        assertEquals(0L, remainingSplashAnimationMillis(null, duration, start, 1f))
        assertEquals(0L, remainingSplashAnimationMillis(start, null, start, 1f))
    }

    @Test
    fun `遵循系统动画倍率并避免时钟变化导致额外等待`() {
        val start = Instant.ofEpochMilli(1000L)
        val duration = Duration.ofMillis(900L)

        assertEquals(0L, remainingSplashAnimationMillis(start, duration, start, 0f))
        assertEquals(150L, remainingSplashAnimationMillis(start, duration, start.plusMillis(300L), 0.5f))
        assertEquals(1500L, remainingSplashAnimationMillis(start, duration, start.plusMillis(300L), 2f))
        assertEquals(900L, remainingSplashAnimationMillis(start, duration, start.minusMillis(100L), 1f))
    }
}
