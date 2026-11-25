package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.ui.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.ui.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ui.AgentResultCard
import io.github.mangi.eta.agent.overlay.ui.ScreenCornerRadii
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * 托管 Agent Runtime 运行期的系统浮窗（边缘氛围光、顶部状态胶囊与底部结果卡片），
 * 统一封装 WindowManager 窗口生命周期、分档尺寸伸缩、手势让路穿透及系统返回拦截。
 */
internal class AgentRuntimeOverlayHost(
    private val serviceContext: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
    private val mainHandler: Handler,
    private val currentDisplayedRun: () -> RuntimeRunRegistry.Run?,
    private val isKeepScreenOn: () -> Boolean,
    private val onPause: (String?) -> Unit,
    private val onResume: () -> Unit,
    private val onStop: (String?) -> Unit,
    private val onSupplement: (String?, String) -> Unit,
    private val onDismissResult: (RuntimeRunRegistry.Run?) -> Unit,
    private val onOpenEtaFromResult: (RuntimeRunRegistry.Run?) -> Unit,
) {

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    internal var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** 窗口只投影当前手机控制者；各运行的状态保留在自己的记录中。 */
    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val virtualDisplayState = mutableStateOf(VirtualDisplaySnapshot.Inactive)
    private val capsuleExpanded = mutableStateOf(false)
    private val capsuleSupplementFocusable = mutableStateOf(false)
    /** Agent 手势穿过胶囊时为 true：窗口不可触摸，胶囊淡出让路。 */
    private val capsuleYielded = mutableStateOf(false)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }

    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() = mainHandler.removeCallbacks(restoreCapsule)
    }

    var overlayState: AgentOverlayState
        get() = state.value
        set(value) {
            state.value = value
        }

    val virtualDisplayActive: Boolean
        get() = virtualDisplayState.value.active

    val isCapsuleVisible: Boolean
        get() = capsuleView != null

    val isResultCardVisible: Boolean
        get() = resultCardView != null

    fun attach() {
        AgentOverlayClearance.attach(clearanceHost)
    }

    fun detachAndDestroy() {
        AgentOverlayClearance.detach(clearanceHost)
        removeAllWindows()
    }

    fun updateVirtualDisplay(snapshot: VirtualDisplaySnapshot): Boolean {
        val wasActive = virtualDisplayState.value.active
        virtualDisplayState.value = snapshot
        if (wasActive != snapshot.active) {
            if (!snapshot.active) VirtualDisplayController.setStageExpanded(false)
            updateCapsuleWindowBounds()
            return true
        }
        return false
    }

    fun resetExpansion() {
        capsuleExpanded.value = false
    }

    fun setKeepScreenOn(enabled: Boolean) {
        capsuleView?.keepScreenOn = enabled
    }

    fun showAmbient() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(serviceContext)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── 氛围光窗口：全屏触摸穿透，彩虹光圈（后台虚拟屏运行时隐藏流光，仅承载全屏破壁动画） ─
        val glow = createOverlayComposeView {
            AgentOverlayGlow(
                state = state.value,
                corners = screenCornerRadii(),
                virtualDisplay = virtualDisplayState.value,
            )
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
        }
        glowView = glow
        glowParams = glowLp

        // ── 状态胶囊窗口：状态栏下方居中，收起时一句状态（含微缩镜），点击展开控制与画廊视窗 ─
        val capsule = createOverlayComposeView {
            val controlRunId = currentDisplayedRun()?.runId
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = {
                    VirtualDisplayController.setUserControlling(false)
                    onPause(controlRunId)
                },
                onResume = {
                    VirtualDisplayController.setUserControlling(false)
                    onResume()
                },
                onStop = { onStop(controlRunId) },
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { onSupplement(controlRunId, it) },
                virtualDisplay = virtualDisplayState.value,
                onTakeOverVirtualDisplay = {
                    VirtualDisplayController.setUserControlling(true)
                    onPause(controlRunId)
                },
                onResumeVirtualDisplay = {
                    VirtualDisplayController.setUserControlling(false)
                    onResume()
                },
                onFullscreenVirtualDisplay = ::requestFullscreenVirtualDisplay,
                onTapVirtualCanvas = { nx, ny, longPress ->
                    VirtualDisplayController.dispatchUserTapOnCanvas(nx, ny, longPress)
                },
                onSwipeVirtualCanvas = { sx, sy, ex, ey, durationMs ->
                    VirtualDisplayController.dispatchUserSwipeOnCanvas(sx, sy, ex, ey, durationMs)
                },
            )
        }
        val capsuleLp = capsuleLayoutParams()
        capsule.keepScreenOn = isKeepScreenOn()
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
    }

    fun showResultCard(): Boolean {
        if (windowManager == null && (AgentAccessibilityService.current() != null || Settings.canDrawOverlays(serviceContext))) {
            windowManager = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        val wm = windowManager ?: return false
        if (resultCardView != null) return true
        val card = createOverlayComposeView {
            val resultRun = currentDisplayedRun()
            AgentResultCard(
                state = state.value,
                onClose = { onDismissResult(resultRun) },
                onOpenEta = { onOpenEtaFromResult(resultRun) },
                hasVirtualDisplayApp = virtualDisplayState.value.active &&
                    virtualDisplayState.value.packageName.isNotBlank(),
                onOpenVirtualApp = {
                    thread(name = "eta-vd-result-handoff", isDaemon = true) {
                        VirtualDisplayController.handoffToMainDisplay(serviceContext)
                        mainHandler.post { onDismissResult(resultRun) }
                    }
                },
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return true
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
        return true
    }

    fun setCapsuleInputMode(focusable: Boolean) {
        capsuleSupplementFocusable.value = focusable
        if (capsuleExpanded.value) updateCapsuleWindowBounds(expanding = true)
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    fun removeAmbientWindows() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    fun removeAllWindows() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
    }

    /**
     * 让路时同步切换触摸穿透：标志位立刻生效保证手势不被截走，视觉过渡交给胶囊内的动画。
     * 恢复时先淡入再收回触摸，两者顺序无关紧要，因为恢复发生在手势结束之后。
     */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (!yielded) mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        VirtualDisplayController.setStageExpanded(expand && virtualDisplayState.value.active)
        // 先把窗口扩到展开档再开始动画；收起由胶囊在动画结束后回调 onCollapsedSettled 缩窗。
        if (expand) updateCapsuleWindowBounds(expanding = true)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) {
            VirtualDisplayController.setStageExpanded(false)
            updateCapsuleWindowBounds(expanding = false)
        }
    }

    private fun updateCapsuleWindowBounds(expanding: Boolean = capsuleExpanded.value) {
        val vdActive = virtualDisplayState.value.active
        val focusable = capsuleSupplementFocusable.value
        // 虚拟屏激活时窗口宽度固定为展开档，避免展开/收起修改 WindowManager 宽度引发居中水平跳帧。
        val widthDp = if (vdActive) {
            CAPSULE_STAGE_WINDOW_WIDTH_DP
        } else {
            CAPSULE_WINDOW_WIDTH_DP
        }
        val heightDp = when {
            focusable && expanding -> CAPSULE_SUPPLEMENT_HEIGHT_DP
            expanding && vdActive -> CAPSULE_STAGE_HEIGHT_DP
            expanding -> CAPSULE_CONTROLS_HEIGHT_DP
            else -> CAPSULE_COLLAPSED_HEIGHT_DP
        }
        setCapsuleWindowSize(widthDp, heightDp)
    }

    private fun setCapsuleWindowSize(widthDp: Int, heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val width = dpToPx(widthDp)
        val height = dpToPx(heightDp)
        if (lp.width == width && lp.height == height) return
        lp.width = width
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestFullscreenVirtualDisplay() {
        capsuleExpanded.value = false
        VirtualDisplayController.setStageExpanded(false)
        thread(name = "eta-vd-fullscreen-handoff", isDaemon = true) {
            VirtualDisplayController.handoffToMainDisplay(serviceContext)
        }
    }

    /**
     * 结果卡片是任务结束后的停留界面，系统返回应先关闭它，而不是退回底下的应用。
     * 窗口需可获焦才能收到返回；卡片外仍是 FLAG_NOT_TOUCH_MODAL，触摸照常穿透到下层。
     */
    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val resultRun = currentDisplayedRun()
        val callback = OnBackInvokedCallback { onDismissResult(resultRun) }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsStateWithLifecycle(initialValue = AppearanceSettings())
                AgentAppTheme(
                    appearance = appearance,
                ) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用无 RuntimeShader 的圆角回退。
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                    ) {
                        content()
                    }
                }
            }
        }

    /**
     * 胶囊窗口使用固定宽度与分档高度，动画只在窗口内进行。
     * WRAP_CONTENT 会让每一帧的尺寸变化都触发 WindowManager 重新布局整个窗口，这是卡顿的来源；
     * 但窗口内透明区域同样会吞掉触摸（包括 Agent 自己注入的点击），所以高度只在需要时扩大。
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(if (virtualDisplayState.value.active) CAPSULE_STAGE_WINDOW_WIDTH_DP else CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 贴在状态栏下方居中：远离多数应用的主要点击区，也和系统胶囊、流体云的位置习惯一致。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: serviceContext

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。
            // 显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * 屏幕四角的物理圆角半径。光效窗口覆盖整屏，直接取 Display 上报的值；
     * 部分 ROM 不上报时用 0，兜底按常见大圆角机型估一个保守值，宁可略小也不让光带越出屏幕。
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun resultCardWindowHeightPx(): Int =
        (serviceContext.resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * serviceContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        const val CAPSULE_WINDOW_WIDTH_DP = 272
        const val CAPSULE_STAGE_WINDOW_WIDTH_DP = 324
        const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        const val CAPSULE_STAGE_HEIGHT_DP = 524
        const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** 注入点击只是一个点，目标控件常贴着胶囊边缘，判定范围向外多留一点。 */
        const val CAPSULE_CLEARANCE_SLOP_DP = 8
        const val FALLBACK_SCREEN_CORNER_DP = 28
    }
}
