package io.github.mangi.eta.ui.app

import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

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

    fun install() {
        activity.lifecycle.addObserver(this)
        // 外观配置异步读取完成后才允许首帧，避免系统启动画面先退到空白窗口。
        content.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        if (!isContentReady()) return false
        content.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (content.viewTreeObserver.isAlive) {
            content.viewTreeObserver.removeOnPreDrawListener(this)
        }
        activity.lifecycle.removeObserver(this)
    }
}
