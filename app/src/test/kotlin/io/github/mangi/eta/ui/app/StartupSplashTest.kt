package io.github.mangi.eta.ui.app

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
}
