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
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.overlay.AgentOverlayContent
import fuck.andes.agent.overlay.AgentOverlayGlow
import fuck.andes.agent.overlay.AgentOverlayOrb
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

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var orbView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val collapsed = mutableStateOf(false)
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
            AndroidAgentLogger.warn("Agent runtime: client unbound while run is active, keeping detached run")
        }
        clientMessenger = null
        return false
    }

    override fun onDestroy() {
        activeRunController?.cancel()
        activeRunController = null
        mainHandler.removeCallbacksAndMessages(null)
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        orbView = null
        glowView = null
        panelParams = null
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
                        finishWithFailure(throwable.message ?: throwable.javaClass.simpleName)
                        return
                    }
                    startRun(request)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    activeRunController?.cancel()
                    state.value = state.value.copy(statusText = "正在停止")
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

    private fun startRun(request: AgentRuntimeWire.RunRequest) {
        activeRunController?.cancel()
        val runController = AgentRunController()
        activeRunController = runController
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warn("Agent runtime keep-alive start failed: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        state.value = AgentOverlayState.Initial
        collapsed.value = false

        thread(name = "agent-runtime") {
            val archivedEvents = mutableListOf<AgentEvent>()
            val skillIndexService = SkillRuntime.createIndexService(this@AgentRuntimeService)
            val skillLoader = SkillRuntime.createLoader(this@AgentRuntimeService)
            skillIndexService.seedBuiltinSkillsIfNeeded()
            val installedSkills = skillIndexService.listInstalledSkills()
                .filter { SkillCompatibilityChecker.evaluate(it).available }
            val skillContext = SkillContext(
                installedSkills = installedSkills,
            )

            val toolExecutor = AgentLocalTools(
                logger = AndroidAgentLogger,
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
                            AndroidAgentLogger.info("Agent runtime event: ${event.toLogLine()}")
                            archivedEvents += event
                            sendEvent(event)
                            mainHandler.post {
                                if (activeRunController == runController) {
                                    state.value = state.value.applyEvent(event)
                                    if (AgentOverlayVisibilityPolicy.shouldRevealFor(event)) {
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
                        reasoningContent = response.reasoningContent
                    )
                    persistCompletedRun(request, result)
                    persistArchivedRun(request, result, archivedEvents)
                    if (activeRunController == runController) {
                        sendResult(result)
                    }
                    mainHandler.post {
                        if (activeRunController != runController) return@post
                        activeRunController = null
                        enterFinalState(
                            state.value.copy(
                                phase = AgentOverlayPhase.FINISHED,
                                statusText = "已返回结果"
                            )
                        )
                    }
                }.getOrElse { throwable ->
                    val message = if (throwable is AgentRunCancelledException) {
                        "已停止"
                    } else {
                        throwable.message ?: throwable.javaClass.simpleName
                    }
                    AndroidAgentLogger.error("Agent runtime failed: $message", throwable)
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
                    persistCompletedRun(request, result)
                    persistArchivedRun(request, result, archivedEvents)
                    if (activeRunController == runController) {
                        sendResult(result)
                    }
                    mainHandler.post {
                        if (activeRunController != runController) return@post
                        activeRunController = null
                        enterFinalState(
                            AgentOverlayState(
                                phase = AgentOverlayPhase.FAILED,
                                statusText = "调用失败",
                                detailText = message
                            )
                        )
                    }
                }
            } finally {
                toolsBinding.close()
                toolExecutor.close()
            }
        }
    }

    private fun sendEvent(event: AgentEvent) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            clientMessenger?.send(msg)
        }
    }

    private fun sendResult(result: AgentRuntimeWire.RunResult) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result)
            clientMessenger?.send(msg)
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
            AndroidAgentLogger.warn("Agent runtime drain results failed: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
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

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        if (orbView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── 氛围光窗口：全屏触摸穿透，屏幕四边光圈 ──────────────────
        val glow = ComposeView(this).apply {
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
            AndroidAgentLogger.warn("Agent runtime glow addView failed: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
        glowView = glow
        glowParams = glowLp

        // ── 光球窗口：始终显示，右侧中下 ──────────────────────────────
        val orb = ComposeView(this).apply {
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
            AndroidAgentLogger.warn("Agent runtime orb addView failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            return
        }
        orbView = orb
        orbParams = orbLp

        // ── 卡片窗口：展开态显示，底部居中 ────────────────────────────
        if (!collapsed.value) {
            showPanel(wm)
        }
    }

    private fun toggleCollapse() {
        collapsed.value = !collapsed.value
        val wm = windowManager ?: return
        if (collapsed.value) {
            panelView?.let { view -> runCatching { wm.removeView(view) } }
        } else {
            if (panelView == null) showPanel(wm)
        }
    }

    private fun showPanel(wm: WindowManager) {
        if (panelView != null) return
        val panel = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    AgentOverlayContent(
                        state = state.value,
                        onCollapse = ::toggleCollapse,
                        onPause = ::requestPause,
                        onResume = ::requestResume,
                        onStop = ::requestStop,
                    )
                }
            }
        }
        val lp = panelLayoutParams()
        runCatching { wm.addView(panel, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warn("Agent runtime panel addView failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            return
        }
        panelView = panel
        panelParams = lp
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    private fun panelLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 底部居中，安全区由 Compose 内部 navigationBarsPadding 处理
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
        }

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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState) {
        state.value = finalState
        clientMessenger = null
        mainHandler.removeCallbacksAndMessages(hideToken)
        mainHandler.postDelayed({ dismissAndStop() }, hideToken, HIDE_DELAY_MS)
    }

    private fun dismissAndStop() {
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        orbView = null
        glowView = null
        panelParams = null
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

    private companion object {
        const val ACTION_KEEP_ALIVE = "fuck.andes.agent.runtime.KEEP_ALIVE"
        const val HIDE_DELAY_MS = 2_500L
    }
}
