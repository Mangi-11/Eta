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
import fuck.andes.agent.overlay.AgentOverlayPhase
import fuck.andes.agent.overlay.AgentOverlayState
import fuck.andes.agent.overlay.applyEvent
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
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

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
        composeView?.let { view -> runCatching { windowManager?.removeView(view) } }
        composeView = null
        params = null
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
        ensureOverlayVisible()

        thread(name = "agent-runtime") {
            val toolExecutor = AgentLocalTools(
                logger = AndroidAgentLogger,
                terminalToolsEnabled = request.config.terminalTools
            )
            val toolsBinding = runController.register { toolExecutor.close() }
            try {
                runCatching {
                    val response = AgentModelClient.complete(
                        config = request.config,
                        prompt = request.prompt,
                        toolExecutor = toolExecutor,
                        images = request.images,
                        runController = runController
                    ) { event ->
                        if (activeRunController == runController) {
                            AndroidAgentLogger.info("Agent runtime event: ${event.toLogLine()}")
                            sendEvent(event)
                            mainHandler.post {
                                if (activeRunController == runController) {
                                    state.value = state.value.applyEvent(event)
                                    ensureOverlayVisible()
                                }
                            }
                        }
                    }
                    val result = AgentRuntimeWire.RunResult(
                        runId = request.runId,
                        ok = true,
                        content = response.content
                    )
                    persistCompletedRun(request, result)
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
                        sendEvent(AgentEvent.RunFailed(message))
                    }
                    val result = AgentRuntimeWire.RunResult(
                        runId = request.runId,
                        ok = false,
                        content = "",
                        error = message
                    )
                    persistCompletedRun(request, result)
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

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        if (composeView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    AgentOverlayContent(
                        state = state.value,
                        collapsed = collapsed.value,
                        onDrag = ::handleDrag,
                        onToggleCollapse = { collapsed.value = !collapsed.value },
                        onStop = ::requestStop
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }
        runCatching { wm.addView(view, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warn("Agent runtime overlay addView failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            return
        }
        windowManager = wm
        composeView = view
        params = lp
    }

    private fun handleDrag(dx: Float, dy: Float) {
        val lp = params ?: return
        val wm = windowManager ?: return
        val view = composeView ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun enterFinalState(finalState: AgentOverlayState) {
        state.value = finalState
        clientMessenger = null
        mainHandler.removeCallbacksAndMessages(hideToken)
        mainHandler.postDelayed({ dismissAndStop() }, hideToken, HIDE_DELAY_MS)
    }

    private fun dismissAndStop() {
        composeView?.let { view -> runCatching { windowManager?.removeView(view) } }
        composeView = null
        params = null
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
