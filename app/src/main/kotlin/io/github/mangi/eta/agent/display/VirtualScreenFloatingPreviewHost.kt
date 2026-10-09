package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings as EtaSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.hypot

/** Lives inside the existing execution service; never injects touches into the virtual app. */
internal class VirtualScreenFloatingPreviewHost(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowContext = context.createDisplayContext(
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    ).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val manager = windowContext.getSystemService(WindowManager::class.java)
    private val viewerId = "preview-${UUID.randomUUID()}"
    private var settings = EtaSettings()
    private var state = VirtualScreenViewerState()
    private var root: FrameLayout? = null
    private var surface: VirtualScreenSurfaceView? = null
    private var expanded: View? = null
    private var folded: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var capture: Job? = null
    private var folding: Job? = null
    private var collapsed = false
    private var edge = FloatingPreviewPlacement.Edge.RIGHT
    private var run: String? = null
    private var dismissedRun: String? = null
    private var lastFrame = 0L
    private var generation = 0L
    private var dragging = false
    private var position: FloatingPreviewPlacement.Position? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { render() }
    }

    init {
        SettingsDataStore.init(context)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(SettingsDataStore.settingsFlow(), VirtualScreenSession.state) { options, display -> options to display }
                .collect { (options, display) -> settings = options; state = display; render() }
        }
    }

    private fun render() {
        if (run != state.activeRunId) {
            run = state.activeRunId; collapsed = false; dismissedRun = null
            position = null
            folding?.cancel()
        }
        val visible = settings.virtualScreenEnabled && settings.virtualScreenFloatingPreviewEnabled &&
            state.isAgentControlling && run != null && dismissedRun != run && !state.fullViewerVisible &&
            Settings.canDrawOverlays(context) && context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!visible) { removeWindow(); return }
        if (root == null) addWindow()
        if (root == null) return
        if (!dragging) resize()
        if (collapsed) stopCapture() else startCapture()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addWindow() {
        val container = FrameLayout(windowContext).apply {
            setBackgroundColor(0xff20242c.toInt())
            clipToOutline = true
            elevation = dp(8).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            contentDescription = context.getString(R.string.virtual_screen_floating_open)
        }
        val column = LinearLayout(windowContext).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(windowContext).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(windowContext).apply {
            text = context.getString(R.string.virtual_screen_viewer_title); setTextColor(Color.WHITE)
            textSize = 11f; setPadding(dp(10), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(30), 1f))
        header.addView(TextView(windowContext).apply {
            text = "×"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = context.getString(R.string.virtual_screen_floating_dismiss)
            setOnClickListener { dismissedRun = run; removeWindow() }
        }, LinearLayout.LayoutParams(dp(32), dp(30)))
        column.addView(header)
        val image = VirtualScreenSurfaceView(windowContext).apply { touchEnabled = false }
        column.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(column, FrameLayout.LayoutParams(-1, -1))
        val pill = TextView(windowContext).apply {
            text = "▣"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = context.getString(R.string.virtual_screen_floating_open)
        }
        container.addView(pill, FrameLayout.LayoutParams(-1, -1))
        val metrics = manager.currentWindowMetrics
        val layout = WindowManager.LayoutParams(dp(124), dp(278), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = position?.x ?: (metrics.bounds.width() - width - dp(12)).coerceAtLeast(0)
            y = position?.y ?: metrics.bounds.height() / 4
            this.title = "Eta virtual preview"
        }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var moved = false
        container.setOnTouchListener { _, event ->
            if (event.pointerCount != 1) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    folding?.cancel(); downX = event.rawX; downY = event.rawY
                    originX = layout.x; originY = layout.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    moved = moved || hypot(event.rawX - downX, event.rawY - downY) > ViewConfiguration.get(context).scaledTouchSlop
                    if (moved) {
                        layout.x = originX + (event.rawX - downX).toInt()
                        layout.y = originY + (event.rawY - downY).toInt()
                        constrain(); updateLayout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (moved) {
                        val placement = constrain(dp(24))
                        if (placement?.edge != null) {
                            edge = placement.edge
                            folding = scope.launch { delay(600); collapsed = true; resize(); stopCapture() }
                        } else if (collapsed) { collapsed = false; resize(); startCapture() }
                    } else container.performClick()
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; moved = false; constrain(); updateLayout() }
            }
            true
        }
        container.setOnClickListener {
            context.startActivity(Intent(context, VirtualScreenViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
        }
        try {
            manager.addView(container, layout)
            root = container; surface = image; expanded = column; folded = pill; params = layout
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Virtual floating preview unavailable: type=${error.javaClass.simpleName}")
        }
    }

    private fun resize() {
        val layout = params ?: return
        expanded?.visibility = if (collapsed) View.GONE else View.VISIBLE
        folded?.visibility = if (collapsed) View.VISIBLE else View.GONE
        // Keep the bubble's touch center outside the OEM edge gesture strip (24dp on OriginOS).
        layout.width = dp(if (collapsed) 56 else 124)
        layout.height = dp(if (collapsed) 76 else 278)
        if (collapsed) layout.x = if (edge == FloatingPreviewPlacement.Edge.LEFT) 0 else manager.currentWindowMetrics.bounds.width() - layout.width
        constrain(); updateLayout()
    }

    private fun constrain(threshold: Int = 0): FloatingPreviewPlacement.Position? {
        val layout = params ?: return null
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return FloatingPreviewPlacement.constrain(layout.x, layout.y, layout.width, layout.height,
            metrics.bounds.width(), metrics.bounds.height(), insets.top, insets.bottom, threshold).also {
            layout.x = it.x; layout.y = it.y
        }
    }

    private fun updateLayout() { root?.let { view -> params?.let { runCatching { manager.updateViewLayout(view, it) } } } }

    private fun startCapture() {
        if (capture?.isActive == true || root == null || collapsed) return
        val token = ++generation
        VirtualScreenSession.setViewerVisible(viewerId, true, preview = true)
        capture = scope.launch {
            try {
                while (isActive && token == generation && root != null && !collapsed) {
                    val pending = AtomicReference<Bitmap?>()
                    try {
                        val session = state.display?.sessionId
                        val next = withContext(Dispatchers.IO) {
                            val result = VirtualScreenSession.observeForViewer(context, lastFrame, viewerId)
                            val json = JSONObject(result.content)
                            val bitmap = result.images.firstOrNull()?.reference?.substringAfter("base64,")?.let {
                                val bytes = Base64.decode(it, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            pending.set(bitmap)
                            bitmap?.let { decoded ->
                                val display = VirtualScreenSession.state.value.display
                                if (display != null && display.sessionId == session && display.displayId == json.optInt("displayId") &&
                                    display.width == decoded.width && display.height == decoded.height && display.rotation == json.optInt("rotation")) {
                                    Frame(decoded, json.optLong("frameId"), display)
                                } else null
                            }
                        }
                        val currentSurface = surface
                        if (next != null && token == generation && state.display?.sessionId == session && currentSurface != null) {
                            currentSurface.setFrame(next.bitmap, next.display)
                            lastFrame = next.id
                            pending.set(null)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AndroidAgentLogger.warn("Virtual floating preview frame unavailable: type=${error.javaClass.simpleName}")
                        delay(1_000)
                    } finally { pending.getAndSet(null)?.recycle() }
                    delay(VirtualScreenFrameCapturePolicy.FRAME_INTERVAL_MS)
                }
            } finally {
                if (token == generation) {
                    VirtualScreenSession.setViewerVisible(viewerId, false, preview = true)
                    capture = null
                    surface?.clearFrame()
                    lastFrame = 0
                }
            }
        }
    }

    private fun stopCapture() {
        generation++; capture?.cancel(); capture = null
        VirtualScreenSession.setViewerVisible(viewerId, false, preview = true)
        surface?.clearFrame(); lastFrame = 0
    }

    private fun removeWindow() {
        folding?.cancel(); folding = null
        stopCapture()
        params?.let { position = FloatingPreviewPlacement.Position(it.x, it.y, edge) }
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null; surface = null; expanded = null; folded = null; params = null
        dragging = false
    }

    override fun close() {
        removeWindow(); scope.cancel(); runCatching { context.unregisterReceiver(receiver) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private data class Frame(val bitmap: Bitmap, val id: Long, val display: VirtualDisplayInfo)
}
