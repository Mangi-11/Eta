package fuck.andes.agent.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.overlay.AgentOverlayBubble
import fuck.andes.agent.overlay.AgentOverlayGlow
import fuck.andes.agent.overlay.AgentOverlayOrb
import fuck.andes.agent.overlay.AgentResultCard
import fuck.andes.agent.overlay.AgentOverlayPhase
import fuck.andes.agent.overlay.AgentOverlayState
import fuck.andes.agent.overlay.AgentOverlayVisibilityPolicy
import fuck.andes.agent.overlay.applyEvent
import fuck.andes.agent.skill.SkillCompatibilityChecker
import fuck.andes.agent.skill.SkillContext
import fuck.andes.agent.skill.SkillRuntime
import fuck.andes.agent.tool.AgentLocalTools
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.ModuleConfig
import fuck.andes.core.safeLogType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceMessenger = Messenger(IncomingHandler())

    private var clientMessenger: Messenger? = null
    @Volatile
    private var activeRunController: AgentRunController? = null
    @Volatile
    private var activeRunId: String? = null

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var orbView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null

    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val collapsed = mutableStateOf(true)
    private var hasExecutedForegroundTool = false
    private val supplementsLock = Any()
    private val activeSupplements = mutableListOf<AgentUiHandoffPayload.Supplement>()
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || activeRunController == null) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeRunController != null) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        clientMessenger = null
        return false
    }

    override fun onDestroy() {
        activeRunController?.cancel()
        activeRunController = null
        activeRunId = null
        mainHandler.removeCallbacksAndMessages(null)
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        bubbleView = null
        orbView = null
        glowView = null
        resultCardParams = null
        bubbleParams = null
        orbParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) return
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    clientMessenger = msg.replyTo
                    val request = runCatching {
                        AgentRuntimeWire.runRequestFromBundle(msg.data ?: return)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效")
                        return
                    }
                    startRun(request)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    cancelRun(msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty())
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    AgentRuntimeResultStore.remove(
                        this@AgentRuntimeService,
                        AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    )
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo)
                }
            }
        }
    }

    private fun vibrateNormal() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        vibrator?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                it.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(100)
            }
        }
    }

    private fun startRun(request: AgentRuntimeWire.RunRequest) {
        activeRunController?.cancel()
        val runController = AgentRunController()
        activeRunController = runController
        activeRunId = request.runId
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        state.value = AgentOverlayState.Initial
        collapsed.value = true
        hasExecutedForegroundTool = false
        synchronized(supplementsLock) {
            activeSupplements.clear()
            if (request.handoff?.source == AGENT_UI_HANDOFF_SOURCE) {
                activeSupplements += AgentUiHandoffPayload.from(request.handoff.payload).supplements
            }
        }

        thread(name = "agent-runtime") {
            val archivedEvents = mutableListOf<AgentEvent>()
            val entrySurfaceDismissed = AtomicBoolean(false)
            val skillIndexService = SkillRuntime.createIndexService(this@AgentRuntimeService)
            val skillLoader = SkillRuntime.createLoader(this@AgentRuntimeService)
            skillIndexService.seedBuiltinSkillsIfNeeded()
            val installedSkills = skillIndexService.listInstalledSkills()
                .filter { SkillCompatibilityChecker.evaluate(it).available }
            val skillContext = SkillContext(
                installedSkills = installedSkills,
            )

            val toolExecutor = AgentLocalTools(
                context = this@AgentRuntimeService,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserToolsEnabled = request.config.browserTools,
                terminalToolsEnabled = request.config.terminalTools,
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
            )
            val toolsBinding = runController.register { toolExecutor.close() }
            try {
                runCatching {
                    val response = AgentModelClient.complete(
                        config = request.config,
                        prompt = request.prompt,
                        toolExecutor = toolExecutor,
                        images = request.images,
                        history = request.history,
                        runController = runController,
                        skillContext = skillContext
                    ) { event ->
                        if (activeRunController == runController) {
                            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
                            archivedEvents += event
                            sendEvent(event)
                            val revealsForegroundOperation =
                                AgentOverlayVisibilityPolicy.shouldRevealFor(event)
                            if (revealsForegroundOperation) {
                                hasExecutedForegroundTool = true
                            }
                            if (
                                AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event) &&
                                request.handoff?.dismissEntrySurfaceOnForegroundOperation == true &&
                                entrySurfaceDismissed.compareAndSet(false, true)
                            ) {
                                dismissEntrySurfaceForForegroundOperation(request)
                            }
                            mainHandler.post {
                                if (activeRunController == runController) {
                                    state.value = state.value.applyEvent(event)
                                    if (revealsForegroundOperation) {
                                        if (orbView == null) {
                                            vibrateNormal()
                                        }
                                        ensureOverlayVisible()
                                    }
                                }
                            }
                        }
                    }
                    val result = AgentRuntimeWire.RunResult(
                        runId = request.runId,
                        ok = true,
                        content = response.content,
                        reasoningContent = response.reasoningContent,
                        transcript = response.transcript,
                    )
                    val persistRequest = request.withActiveSupplements()
                    if (!persistCompletedRunIfCurrent(runController, persistRequest, result)) {
                        throw AgentRunCancelledException()
                    }
                    persistArchivedRun(persistRequest, result, archivedEvents)
                    if (activeRunController == runController) {
                        sendResult(result)
                    }
                    mainHandler.post {
                        if (activeRunController != runController) return@post
                        activeRunController = null
                        activeRunId = null
                        lastCompletedRunContext = CompletedRunContext(
                            request = persistRequest,
                            response = response,
                        )
                        val finalResult = response.content.trim()
                        enterFinalState(
                            state.value.copy(
                                phase = AgentOverlayPhase.FINISHED,
                                statusText = "已返回结果",
                                detailText = finalResult.ifBlank { state.value.detailText }
                            ),
                            keepVisible = entrySurfaceDismissed.get()
                        )
                    }
                }.getOrElse { throwable ->
                    // 资源取消可能表现为 IOException 等目标异常，不能只依赖异常类型判断控制流。
                    val cancelled = runController.isCancelled || throwable is AgentRunCancelledException
                    val message = if (cancelled) {
                        "已停止"
                    } else {
                        throwable.message ?: throwable.javaClass.simpleName
                    }
                    if (cancelled) {
                        AndroidAgentLogger.info("Agent runtime stopped")
                    } else {
                        AndroidAgentLogger.error(
                            "Agent runtime failed: type=${throwable.safeLogType()}"
                        )
                    }
                    if (activeRunController == runController) {
                        val event = AgentEvent.RunFailed(message)
                        archivedEvents += event
                        sendEvent(event)
                    }
                    val result = AgentRuntimeWire.RunResult(
                        runId = request.runId,
                        ok = false,
                        content = "",
                        error = message
                    )
                    val persistRequest = request.withActiveSupplements()
                    // 取消是控制流，不是等待入口进程消费的完成结果；持久化后会在入口重启时误投递。
                    val deliverResult = !cancelled &&
                        persistCompletedRunIfCurrent(runController, persistRequest, result)
                    persistArchivedRun(persistRequest, result, archivedEvents)
                    if (deliverResult && activeRunController == runController) {
                        sendResult(result)
                    }
                    mainHandler.post {
                        if (activeRunController != runController) return@post
                        activeRunController = null
                        activeRunId = null
                        enterFinalState(
                            AgentOverlayState(
                                phase = AgentOverlayPhase.FAILED,
                                statusText = "调用失败",
                                detailText = message
                            ),
                            keepVisible = entrySurfaceDismissed.get()
                        )
                    }
                }
            } finally {
                toolsBinding.close()
                toolExecutor.close()
            }
        }
    }

    private fun dismissEntrySurfaceForForegroundOperation(request: AgentRuntimeWire.RunRequest) {
        if (request.handoff == null) return
        val service = AgentAccessibilityService.current()
        if (service == null) {
            AndroidAgentLogger.warnThrottled("runtime_entry_surface_accessibility_unavailable") {
                "Agent runtime entry surface dismiss skipped: accessibility service unavailable"
            }
            return
        }
        val dismissed = service.globalAction("BACK")
        if (dismissed) {
            AndroidAgentLogger.debug {
                "Agent runtime entry surface dismissed before foreground operation"
            }
        } else {
            AndroidAgentLogger.warnThrottled("runtime_entry_surface_dismiss_failed") {
                "Agent runtime entry surface dismiss failed before foreground operation"
            }
        }
    }

    private fun sendEvent(event: AgentEvent) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            clientMessenger?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendResult(result: AgentRuntimeWire.RunResult) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result)
            clientMessenger?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                AgentRuntimeResultStore.list(this)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ): Boolean {
        val handoff = request.handoff ?: return true
        return AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun ensureRunCanComplete(runController: AgentRunController) {
        if (runController.isCancelled || activeRunController !== runController) {
            throw AgentRunCancelledException()
        }
    }

    private fun persistCompletedRunIfCurrent(
        runController: AgentRunController,
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
    ): Boolean {
        try {
            ensureRunCanComplete(runController)
        } catch (_: AgentRunCancelledException) {
            return false
        }
        if (!persistCompletedRun(request, result)) return false
        return try {
            ensureRunCanComplete(runController)
            true
        } catch (_: AgentRunCancelledException) {
            val stableRunId = result.runId.ifBlank { request.handoff?.id.orEmpty() }
            AgentRuntimeResultStore.remove(this, stableRunId)
            false
        }
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun finishWithFailure(message: String) {
        sendResult(AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message))
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                statusText = "调用失败",
                detailText = message
            )
        )
    }

    private fun requestStop() {
        if (activeRunController == null) {
            dismissAndStop()
            return
        }
        cancelRun(activeRunId.orEmpty())
    }

    private fun cancelRun(runId: String) {
        val activeId = activeRunId
        if (runId.isNotBlank() && activeId != null && runId != activeId) {
            AndroidAgentLogger.debug { "Agent runtime ignored stale cancel request" }
            return
        }
        activeRunController?.cancel()
        state.value = state.value.copy(statusText = "正在停止")
    }

    private fun requestPause() {
        activeRunController?.pause()
        state.value = state.value.copy(
            phase = AgentOverlayPhase.PAUSED,
            statusText = "已暂停",
        )
    }

    private fun requestResume() {
        activeRunController?.resume()
        state.value = state.value.copy(
            phase = AgentOverlayPhase.RUNNING,
            statusText = "继续执行",
        )
    }

    private fun requestSupplement(text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        setBubbleInputMode(focusable = false)
        activeRunController?.let { controller ->
            val event = recordSupplementEvent(supplementText)
            AndroidAgentLogger.info("Agent runtime supplement received: index=${event.index}, chars=${event.text.length}")
            sendEvent(event)
            state.value = state.value.applyEvent(event)
            controller.steer(supplementText)
            return
        }

        val completed = lastCompletedRunContext ?: return
        if (completed.request.handoff?.source != AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(statusText = "当前入口不支持继续补充")
            return
        }
        val continuationRequest = completed.toContinuationRequest(supplementText)
        startRun(continuationRequest)
    }

    private fun recordSupplementEvent(text: String): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            val nextIndex = (activeSupplements.maxOfOrNull { it.index } ?: 0) + 1
            AgentUiHandoffPayload.Supplement(
                index = nextIndex,
                text = text,
                createdAt = System.currentTimeMillis(),
            ).also { activeSupplements += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
        )
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        if (orbView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── 氛围光窗口：全屏触摸穿透，彩虹光圈，截图时被 takeScreenshotOfWindow 过滤 ─
        val glow = ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    AgentOverlayGlow(state = state.value)
                }
            }
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
        }
        glowView = glow
        glowParams = glowLp

        // ── 光球窗口：始终显示，右侧中下 ──────────────────────────────
        val orb = ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    AgentOverlayOrb(
                        state = state.value,
                        onToggleCollapse = ::toggleCollapse,
                    )
                }
            }
        }
        val orbLp = orbLayoutParams()
        runCatching { wm.addView(orb, orbLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_orb_add_view_failed") {
                "Agent runtime orb addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        orbView = orb
        orbParams = orbLp
        orb.visibility = View.VISIBLE

        // ── 小气泡窗口：展开态显示，跟随光球，窗口外触摸穿透 ─────────
        if (!collapsed.value) {
            showBubble(wm)
        }
    }

    private fun toggleCollapse() {
        collapsed.value = !collapsed.value
        val wm = windowManager ?: return
        if (collapsed.value) {
            bubbleView?.let { view -> runCatching { wm.removeView(view) } }
            bubbleView = null
            bubbleParams = null
        } else {
            if (bubbleView == null) showBubble(wm)
        }
    }

    private fun showBubble(wm: WindowManager) {
        if (bubbleView != null) return
        val bubble = ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    AgentOverlayBubble(
                        state = state.value,
                        onCollapse = ::toggleCollapse,
                        onPause = ::requestPause,
                        onResume = ::requestResume,
                        onStop = ::requestStop,
                        onSupplementModeChange = ::setBubbleInputMode,
                        onSupplement = ::requestSupplement,
                    )
                }
            }
        }
        val lp = bubbleLayoutParams()
        runCatching { wm.addView(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_add_view_failed") {
                "Agent runtime bubble addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        bubbleView = bubble
        bubbleParams = lp
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    AgentResultCard(
                        state = state.value,
                        onClose = ::dismissAndStop,
                    )
                }
            }
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
    }

    @Suppress("unused")
    private fun handleDrag(dx: Float, dy: Float) {
        val lp = orbParams ?: return
        val wm = windowManager ?: return
        val view = orbView ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun orbLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 右侧中下，贴近右边缘
            gravity = Gravity.END or Gravity.TOP
            x = dpToPx(8)
            y = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 跟随光球：右侧中下，窗口外触摸穿透
            gravity = Gravity.END or Gravity.TOP
            x = dpToPx(72)
            y = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            windowAnimations = 0
        }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
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
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun setBubbleInputMode(focusable: Boolean) {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_focus_update_failed") {
                "Agent runtime bubble focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        state.value = finalState
        clientMessenger = null

        if (hasExecutedForegroundTool) {
            // 撤掉光球和小气泡，改显半屏结果卡片，不自动关闭，用户手动关闭
            collapsed.value = true
            removeAmbientWindows()
            windowManager?.let(::showResultCard)
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView = null
        bubbleView = null
        glowView = null
        orbParams = null
        bubbleParams = null
        glowParams = null
    }

    private fun dismissAndStop() {
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        bubbleView = null
        orbView = null
        glowView = null
        resultCardParams = null
        bubbleParams = null
        orbParams = null
        glowParams = null
        windowManager = null
        stopSelf()
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) { activeSupplements.toList() }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    private fun CompletedRunContext.toContinuationRequest(supplement: String): AgentRuntimeWire.RunRequest {
        val runId = "run-${UUID.randomUUID()}"
        val baseHistory = request.history +
            AgentModelClient.ConversationMessage(role = "user", content = request.prompt) +
            AgentModelClient.ConversationMessage(role = "assistant", content = response.content)
        val handoff = request.handoff?.let { original ->
            if (original.source != AGENT_UI_HANDOFF_SOURCE) return@let original.copy(id = runId)
            val payload = AgentUiHandoffPayload.from(original.payload)
            val nextSupplement = AgentUiHandoffPayload.Supplement(
                index = (payload.supplements.maxOfOrNull { it.index } ?: 0) + 1,
                text = supplement,
                createdAt = System.currentTimeMillis(),
            )
            original.copy(
                id = runId,
                payload = payload.copy(
                    supplements = payload.supplements + nextSupplement
                ).toJson(),
            )
        }
        return request.copy(
            runId = runId,
            prompt = supplement,
            images = emptyList(),
            history = baseHistory,
            handoff = handoff,
        )
    }

    private companion object {
        const val AGENT_UI_HANDOFF_SOURCE = "agent_ui"
        const val ACTION_KEEP_ALIVE = "fuck.andes.agent.runtime.KEEP_ALIVE"
        const val HIDE_DELAY_MS = 2_500L
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )
}
