package fuck.andes.hook.breeno

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import fuck.andes.agent.runtime.BreenoAppContext

internal object BreenoExecutionOverlay {
    private const val HIDE_DELAY_MS = 2_500L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var view: TextView? = null
    private var disabled = false

    fun show(title: String, detail: String) {
        post {
            val context = BreenoAppContext.resolve() ?: return@post
            if (!ensureView(context)) return@post
            updateText(title, detail)
            view?.visibility = View.VISIBLE
        }
    }

    fun update(title: String, detail: String) {
        post {
            if (view == null) {
                val context = BreenoAppContext.resolve() ?: return@post
                if (!ensureView(context)) return@post
            }
            updateText(title, detail)
        }
    }

    fun finish(detail: String) {
        post {
            if (view != null) updateText("小布 Agent", detail)
            mainHandler.removeCallbacksAndMessages(HideToken)
            mainHandler.postDelayed({ dismissNow() }, HideToken, HIDE_DELAY_MS)
        }
    }

    fun dismiss() {
        post { dismissNow() }
    }

    private fun ensureView(context: Context): Boolean {
        if (disabled) return false
        if (view != null) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            disabled = true
            return false
        }

        val appContext = context.applicationContext ?: context
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false
        val textView = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.START
            setPadding(26, 18, 26, 18)
            setBackgroundColor(0xCC202124.toInt())
            maxLines = 4
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 96
        }

        return runCatching {
            wm.addView(textView, params)
            windowManager = wm
            view = textView
            true
        }.getOrElse {
            disabled = true
            false
        }
    }

    private fun updateText(title: String, detail: String) {
        val clippedDetail = detail
            .replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > 96) it.take(96) + "..." else it }
        view?.text = "$title\n$clippedDetail"
    }

    private fun dismissNow() {
        val currentView = view ?: return
        runCatching { windowManager?.removeView(currentView) }
        view = null
        windowManager = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private object HideToken
}
