package io.github.mangi.eta.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.annotation.SuppressLint
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.conversationHandoffStartMode
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunContext
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.mangi.eta.agent.runtime.archive.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.archive.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.archive.AgentRuntimeResultStore
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeImageTransfer
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ipc.AgentUiHandoffPayload
import io.github.mangi.eta.agent.runtime.ipc.AgentWireText

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
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    private val runs = RuntimeRunRegistry()
    private val guiControl = GuiControlCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false
    @Volatile private var lastGuiOwnerRunId: String? = null
    private var displayedRun: RuntimeRunRegistry.Run? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var localPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    internal val overlayHost by lazy {
        AgentRuntimeOverlayHost(
            serviceContext = this,
            lifecycleOwner = this,
            savedStateRegistryOwner = this,
            mainHandler = mainHandler,
            currentDisplayedRun = { displayedRun },
            isKeepScreenOn = { keepScreenOn.value },
            onPause = ::requestPause,
            onResume = ::requestResume,
            onStop = ::requestStop,
            onSupplement = ::requestSupplement,
            onDismissResult = ::dismissResult,
            onOpenEtaFromResult = ::openEtaFromResult,
        )
    }
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        overlayHost.attach()
        Prefs.localAgentPreferences()?.let { prefs ->
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED) {
                    mainHandler.post(::updateKeepScreenOn)
                }
            }
            localPrefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
        serviceScope.launch { guiControl.state.collect(::applyGuiControlState) }
        serviceScope.launch {
            VirtualDisplayController.snapshots.collect { snapshot ->
                if (overlayHost.updateVirtualDisplay(snapshot)) {
                    displayedRun?.let(::syncActiveRunOverlayVisibility)
                    publishExecutionState()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || runs.all().isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runs.all().isNotEmpty()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        destroyed = true
        if (activeInstance === this) activeInstance = null
        localPrefsListener?.let { listener ->
            Prefs.localAgentPreferences()?.unregisterOnSharedPreferenceChangeListener(listener)
        }
        localPrefsListener = null
        setKeepScreenOn(false)
        runs.all().forEach { run ->
            run.releaseIncoming()
            run.session.cancel(
                AppStrings.get(R.string.runtime_err_service_stopped, "Agent Runtime 服务已停止")
            )
        }
        displayedRun = null
        serviceScope.cancel()
        AgentExecutionService.updateRunStatus(this, null, activeRunCount = 0)
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        overlayHost.detachAndDestroy()
        VirtualDisplayController.release(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                } else if (msg.what == AgentRuntimeWire.MSG_STEER) {
                    AgentWireText.close(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure(
                            AppStrings.get(R.string.runtime_err_missing_body, "Agent Runtime 请求缺少消息体"),
                            msg.replyTo,
                        )
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            AppStrings.get(R.string.runtime_err_invalid_format, "Agent Runtime 请求格式无效"),
                            msg.replyTo,
                        )
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure(
                            AppStrings.get(
                                R.string.runtime_err_missing_input,
                                "Agent Runtime 请求缺少 runId 或用户输入",
                            ),
                            msg.replyTo,
                        )
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo, msg.sendingUid == android.os.Process.myUid())
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_STEER -> {
                    steerRun(msg.data, msg.replyTo)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(
                            replyTo,
                            target?.result ?: AgentRuntimeWire.RunResult(
                                runId,
                                false,
                                "",
                                AppStrings.get(
                                    R.string.runtime_err_full_result_unavailable,
                                    "完整运行结果不可用",
                                ),
                                contextSnapshotRef = runId,
                            ),
                        )
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS -> {
                    sendActiveRuns(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
        isEtaProcess: Boolean,
    ) {
        val run = admitRun(incoming.request, replyTo, incoming) ?: return
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                run.session.controller.throwIfCancelled()
                run.awaitPredecessors()
                val request = AgentRuntimeRequestConfigResolver.constrainPresentation(
                    AgentRuntimeImageTransfer.materialize(incoming), isEtaProcess,
                )
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        if (AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig(request)) {
                            RuntimeConfigRepository.assistantOverlayRuntimeConfig()
                        } else {
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                providerId = request.config.providerId,
                                authMode = request.config.authMode,
                                modelName = request.config.model,
                            )
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                run.releaseIncoming()
                if (destroyed || !runs.contains(run) || run.session.controller.isCancelled) return@post
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        run.prepared(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                        )
                        executePreparedRun(run)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        failBeforeExecution(
                            run,
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: AppStrings.get(
                                        R.string.runtime_err_read_image_failed,
                                        "Agent Runtime 无法读取图片",
                                    )
                                is RuntimeConfigUnavailableException ->
                                    AppStrings.get(
                                        R.string.runtime_err_no_model_configured,
                                        "请先在 Eta 中配置可用的模型",
                                    )
                                else -> AppStrings.get(
                                    R.string.runtime_err_prepare_failed,
                                    "Agent Runtime 无法准备请求",
                                )
                            },
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val run = admitRun(request, replyTo) ?: return
        run.prepared(request)
        executePreparedRun(run)
    }

    private fun admitRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger?,
        incoming: AgentRuntimeWire.IncomingRunRequest? = null,
    ): RuntimeRunRegistry.Run? {
        if (destroyed) {
            incoming?.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    request.runId,
                    false,
                    "",
                    AppStrings.get(R.string.runtime_err_service_stopped, "Agent Runtime 服务已停止"),
                ),
            )
            return null
        }
        val admission = runs.admit(request) {
            lateinit var session: AgentRuntimeSession
            session = AgentRuntimeSession(
                runId = request.runId,
                operation = request.operation,
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                beforeTerminalPublish = {
                    runs.finish(request.runId, session)?.releaseIncoming()
                    AgentExecutionService.release("run:${request.runId}")
                    mainHandler.post {
                        if (!destroyed) {
                            publishExecutionState()
                            updateKeepScreenOn()
                            if (runs.all().isEmpty() && displayedRun == null && !guiControl.state.value.userPaused) stopSelf()
                        }
                    }
                },
            )
            RuntimeRunRegistry.Run(request, session, incoming) {
                sendRequestIngestedTo(replyTo, request.runId)
            }
        }
        val run = when (admission) {
            is RuntimeRunRegistry.Admission.Accepted -> admission.run
            is RuntimeRunRegistry.Admission.Duplicate -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                if (replyTo != null) {
                    val attached = admission.run.session.attach(
                        eventSink = { event -> sendEventTo(replyTo, event) },
                        resultSink = { result -> sendResultTo(replyTo, result) },
                    )
                    if (!attached) sendResultTo(
                        replyTo,
                        AgentRuntimeWire.RunResult(
                            request.runId,
                            false,
                            "",
                            AppStrings.get(
                                R.string.runtime_err_finished_recover,
                                "任务已结束，请恢复完整结果",
                            ),
                            contextSnapshotRef = request.runId,
                            operation = request.operation,
                        ),
                    )
                }
                return null
            }
            is RuntimeRunRegistry.Admission.Rejected -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                AndroidAgentLogger.debug { "Agent runtime request rejected: code=${admission.code}" }
                sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                    request.runId, false, "", admission.message,
                    operation = request.operation, rewriteTargetMessageId = request.rewriteTargetMessageId,
                ))
                return null
            }
        }
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        // 定时任务显式关闭运行进度通知且设备具备后台豁免时，同样跳过 FGS，做到运行时 0 通知。
        val silentScheduledRun = isSilentScheduledRun(request)
        val skipForegroundLease = silentScheduledRun &&
            ScheduledTaskRunner.canRunSilentlyWithoutForegroundService(this)
        if (!skipForegroundLease) {
            val allowBoundFallback = RootAccess.isGranted
            val executionHeld = AgentExecutionService.acquire(
                this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
            ) { mainHandler.post { cancelRun(request.runId) } }
            if (!executionHeld && !allowBoundFallback) {
                failBeforeExecution(
                    run,
                    AppStrings.get(
                        R.string.runtime_err_fgs_start_failed,
                        "无法启动后台执行服务，请返回 Eta 后重试",
                    ),
                )
                return null
            }
        }
        admission.replacedRuns.forEach { cancelRun(it.runId) }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        publishExecutionState()
        updateKeepScreenOn()
        return run
    }

    private fun failBeforeExecution(run: RuntimeRunRegistry.Run, message: String) {
        run.releaseIncoming()
        val result = AgentRuntimeWire.RunResult(
            run.runId, false, "", message,
            operation = run.request.operation, rewriteTargetMessageId = run.request.rewriteTargetMessageId,
        )
        dispatchResultIo {
            run.session.complete(result) {
                runCatching { persistRunArtifacts(run.snapshotRequest(), result, emptyList()) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.error("Agent runtime preparation result persistence failed: type=${throwable.safeLogType()}")
                    }
            }
        }
    }

    private fun executePreparedRun(run: RuntimeRunRegistry.Run) {
        if (!runs.contains(run)) return
        val screenOnBinding = run.session.controller.register { mainHandler.post(::updateKeepScreenOn) }
        thread(name = "agent-runtime") {
            try {
                executeRun(run)
            } finally {
                screenOnBinding.close()
            }
        }
    }

    private fun executeRun(run: RuntimeRunRegistry.Run) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            guiControl = guiControl,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = run::snapshotRequest,
            onAcceptedEvent = { event, _ ->
                handleAcceptedRunEvent(run, event)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(run.session, run.request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            run = run,
            result = outcome.result,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    RuntimeRunRegistry.CompletedContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        run: RuntimeRunRegistry.Run,
        event: AgentEvent,
    ) {
        if (event is AgentEvent.GuiControlChanged) {
            run.guiStatus = event.status
            if (event.status == GuiControlStatus.Acquired) lastGuiOwnerRunId = run.runId
        }
        mainHandler.post {
            if (destroyed) return@post
            val guiState = guiControl.state.value
            val ownsGui = guiState.ownerRunId == run.runId
            // StateFlow 可以合并短暂的获得与释放；终态归属仍以顺序事件记录的最后所有者为准。
            if (event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired &&
                !guiState.userPaused && lastGuiOwnerRunId == run.runId && (ownsGui || guiState.ownerRunId == null)) {
                bindDisplayedRun(run)
            }
            if (AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, true)) {
                run.hasExecutedForegroundTool = true
            }
            runCatching {
                run.overlayState = run.overlayState.applyEvent(event)
                if (displayedRun === run && ownsGui && !run.session.isTerminal) {
                    overlayHost.overlayState = run.overlayState
                    syncActiveRunOverlayVisibility(run)
                }
                publishExecutionState()
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        run: RuntimeRunRegistry.Run,
        result: AgentRuntimeWire.RunResult,
        completedContext: RuntimeRunRegistry.CompletedContext? = null,
    ) {
        mainHandler.post {
            if (destroyed) return@post
            run.completedContext = completedContext
            val guiState = guiControl.state.value
            if (lastGuiOwnerRunId != run.runId || guiState.userPaused ||
                (guiState.ownerRunId != null && guiState.ownerRunId != run.runId)) return@post
            bindDisplayedRun(run)
            updateKeepScreenOn()
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        overlayHost.overlayState.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { overlayHost.overlayState.detailText },
                        ),
                    )
                } else {
                    val stopped = run.session.controller.isCancelled || isStoppedError(result.error)
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (stopped) {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = if (stopped) {
                                AppStrings.get(R.string.runtime_err_stopped, LEGACY_STOPPED_ERROR)
                            } else {
                                result.error.orEmpty()
                            },
                        ),
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(
                result.runId,
                false,
                "",
                AppStrings.get(
                    R.string.runtime_err_result_transfer_failed,
                    "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                ),
                contextSnapshotRef = result.runId,
                operation = result.operation,
            )
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            val snapshots = runs.snapshots()
            val runId = snapshots.firstOrNull { it.runId == guiControl.state.value.ownerRunId }?.runId
                ?: snapshots.firstOrNull()?.runId.orEmpty()
            msg.data = AgentRuntimeWire.ackBundle(runId)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRuns(replyTo: Messenger?) {
        runCatching {
            replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE).apply {
                data = AgentRuntimeWire.activeRunsToBundle(runs.snapshots())
            })
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_runs_delivery_failed") {
                "Agent runtime active runs delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = runs.get(runId)?.session
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
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
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
    }

    private fun requestStop(runId: String?) {
        if (runId == null || runs.get(runId) == null) {
            if (!guiControl.state.value.userPaused && displayedRun?.runId == runId &&
                guiControl.state.value.ownerRunId == null) dismissAndStop()
            return
        }
        cancelRun(runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        val run = runs.get(runId) ?: return
        if (run.preparing) {
            run.session.controller.cancel()
            failBeforeExecution(
                run,
                AppStrings.get(R.string.runtime_err_stopped, LEGACY_STOPPED_ERROR),
            )
            return
        }
        val session = run.session
        if (!session.isTerminal) {
            session.controller.cancel()
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.Stopping)
            if (displayedRun === run && !guiControl.state.value.userPaused) overlayHost.overlayState = run.overlayState
            updateKeepScreenOn()
            publishExecutionState()
        }
    }

    private fun steerRun(data: Bundle?, replyTo: Messenger?) {
        if (data == null) {
            sendSteerResponse("", replyTo, accepted = false)
            return
        }
        var runId = ""
        val text = try {
            runId = AgentRuntimeWire.runIdFromBundle(data)
            AgentRuntimeWire.steerTextFromBundle(data).trim()
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warnThrottled("runtime_invalid_steer_request") {
                "Agent runtime rejected invalid steer request: type=${throwable.safeLogType()}"
            }
            ""
        } finally {
            AgentWireText.close(data)
        }
        val run = runId.takeIf(String::isNotBlank)?.let(runs::get)
        val event = if (run != null && text.isNotBlank()) run.supplement(text) else null
        if (run == null || event == null) {
            sendSteerResponse(runId, replyTo, accepted = false)
            return
        }
        AndroidAgentLogger.info(
            "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
        )
        run.overlayState = run.overlayState.applyEvent(event)
        if (displayedRun === run && guiControl.state.value.ownerRunId == run.runId && !guiControl.state.value.userPaused) {
            overlayHost.overlayState = run.overlayState
        }
        sendSteerResponse(runId, replyTo, accepted = true, supplementIndex = event.index)
    }

    private fun sendSteerResponse(
        runId: String,
        replyTo: Messenger?,
        accepted: Boolean,
        supplementIndex: Int = 0,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_STEER_RESPONSE)
            msg.data = AgentRuntimeWire.steerResponseBundle(runId, accepted, supplementIndex)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_steer_response_delivery_failed") {
                "Agent runtime steer response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestPause(runId: String?) {
        if (runId != null) guiControl.pauseForUser(runId)
    }

    private fun requestResume() {
        guiControl.resumeForUser()
    }

    private fun requestSupplement(runId: String?, text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val run = displayedRun?.takeIf { it.runId == runId } ?: return
        if (guiControl.state.value.userPaused && guiControl.state.value.ownerRunId == null) return
        overlayHost.setCapsuleInputMode(focusable = false)
        runs.get(run.runId)?.let { active ->
            val event = active.supplement(supplementText)
            if (event == null) {
                if (!active.session.isTerminal) {
                    overlayHost.overlayState = overlayHost.overlayState.copy(
                        status = AgentOverlayStatus.Finishing,
                    )
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                active.overlayState = active.overlayState.applyEvent(event)
                overlayHost.overlayState = active.overlayState
                return
            }
        }

        val completed = run.completedContext ?: return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            overlayHost.overlayState = overlayHost.overlayState.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    private fun ensureOverlayVisible() {
        overlayHost.showAmbient()
    }

    private fun syncActiveRunOverlayVisibility(run: RuntimeRunRegistry.Run?) {
        if (AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
                virtualDisplayActive = overlayHost.virtualDisplayActive,
            )
        ) {
            removeAmbientWindows()
        } else {
            ensureOverlayVisible()
        }
    }

    private fun handleEtaUiVisibilityChanged() {
        if (destroyed) return
        val run = displayedRun ?: return
        val suppress = AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
            handoffSource = run.request.handoff?.source,
            etaUiInForeground = MainActivity.isInForeground,
            virtualDisplayActive = overlayHost.virtualDisplayActive,
        )
        if (overlayHost.isResultCardVisible) {
            if (suppress) dismissResult(run)
            return
        }
        val guiState = guiControl.state.value
        val activeGuiRun = guiState.userPaused || (guiState.ownerRunId == run.runId && !run.session.isTerminal)
        if (!activeGuiRun) return
        if (suppress) {
            removeAmbientWindows()
        } else {
            overlayHost.overlayState = run.overlayState
            ensureOverlayVisible()
        }
        publishExecutionState()
    }

    private fun updateKeepScreenOn() {
        val enabled = !destroyed &&
            Prefs.isEnabled(Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED) &&
            runs.all().any { run ->
                !run.session.isTerminal &&
                    !run.session.controller.isCancelled &&
                    !run.session.controller.isPaused
            }
        setKeepScreenOn(enabled)
    }

    private fun applyGuiControlState(guiState: GuiControlCoordinator.State) {
        if (destroyed) return
        val owner = guiState.ownerRunId?.let(runs::get)
        if (owner != null && displayedRun !== owner) {
            bindDisplayedRun(owner)
            AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
        }
        when {
            guiState.userPaused -> {
                overlayHost.overlayState = (owner?.overlayState ?: AgentOverlayState.Initial).copy(
                    phase = AgentOverlayPhase.PAUSED,
                    status = AgentOverlayStatus.Paused,
                    detailText = AppStrings.get(R.string.runtime_detail_gui_paused, "手机操作已暂停"),
                )
                owner?.overlayState = overlayHost.overlayState
                syncActiveRunOverlayVisibility(owner ?: displayedRun)
            }
            owner != null -> {
                if (owner.overlayState.phase == AgentOverlayPhase.PAUSED) {
                    owner.overlayState = owner.overlayState.copy(
                        phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Continuing,
                        detailText = "",
                    )
                }
                overlayHost.overlayState = owner.overlayState
                syncActiveRunOverlayVisibility(owner)
            }
            else -> removeAmbientWindows()
        }
        updateKeepScreenOn()
        publishExecutionState()
    }

    private fun bindDisplayedRun(run: RuntimeRunRegistry.Run) {
        if (displayedRun === run) return
        removeOverlayWindows()
        displayedRun?.completedContext = null
        displayedRun = run
        overlayHost.resetExpansion()
    }

    private fun publishExecutionState() {
        val guiState = guiControl.state.value
        val activeRuns = runs.all().filterNot { it.session.isTerminal }
        val visibleRuns = activeRuns.filterNot { isSilentScheduledRun(it.request) }
        val owner = activeRuns.firstOrNull { it.runId == guiState.ownerRunId }
        val targetRun = owner ?: visibleRuns.firstOrNull() ?: activeRuns.firstOrNull()
        val allActiveRunsSilent = activeRuns.isNotEmpty() && visibleRuns.isEmpty() && owner == null
        val status = if (owner != null && guiState.userPaused) {
            AgentOverlayStatus.Paused
        } else {
            targetRun?.overlayState?.status
        }
        val handoff = targetRun?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            .orEmpty()
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val guiOverlayActive = overlayHost.isCapsuleVisible ||
            (owner != null && !owner.session.isTerminal) ||
            overlayHost.virtualDisplayActive
        AgentExecutionService.updateRunStatus(
            this,
            if (allActiveRunsSilent) null else status,
            activeRunCount = if (allActiveRunsSilent) 0 else visibleRuns.size.coerceAtLeast(if (owner != null) 1 else 0),
            conversationId = conversationId,
            archivedSource = archive?.first.orEmpty(),
            archivedKey = archive?.second.orEmpty(),
            startedAtEpochMs = targetRun?.startedAtEpochMs ?: 0L,
            suppressLiveUpdate = guiOverlayActive || allActiveRunsSilent,
        )
    }

    private fun isSilentScheduledRun(request: AgentRuntimeWire.RunRequest): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.SCHEDULED_TASK_HANDOFF_SOURCE) return false
        val scheduled = AgentExternalArchivePayload.from(handoff.payload)
            ?.adapterPayload
            ?.let(ScheduledTaskRunContext::fromAdapterPayload)
            ?: return false
        return !scheduled.showRunningNotification
    }

    @SuppressLint("WakelockTimeout")
    private fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnState.value = enabled
        overlayHost.setKeepScreenOn(enabled)
        if (enabled) {
            val wakeLock = screenWakeLock ?: runCatching {
                @Suppress("DEPRECATION")
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
            }.getOrNull()?.also { screenWakeLock = it }
            if (wakeLock != null && !wakeLock.isHeld) {
                runCatching { wakeLock.acquire() }
            }
        } else {
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
            }
        }
    }

    private fun openEtaFromResult(run: RuntimeRunRegistry.Run?) {
        val handoff = run?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode = conversationHandoffStartMode()
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissResult(run)
    }

    private fun dismissResult(run: RuntimeRunRegistry.Run?) {
        if (run != null && displayedRun === run && lastGuiOwnerRunId == run.runId &&
            guiControl.state.value.ownerRunId == null && !guiControl.state.value.userPaused) dismissAndStop()
    }

    private fun enterFinalState(finalState: AgentOverlayState) {
        overlayHost.overlayState = finalState

        val run = displayedRun
        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = run?.hasExecutedForegroundTool == true,
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
            )
        ) {
            // 撤掉胶囊和边缘光，改显半屏结果卡片；无交互时由卡片自身延时退场并回调关闭
            overlayHost.resetExpansion()
            removeAmbientWindows()
            publishExecutionState()
            if (!overlayHost.showResultCard()) dismissAndStop()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        overlayHost.removeAmbientWindows()
    }

    private fun dismissAndStop() {
        // stopSelf 不会销毁仍被客户端绑定的服务；窗口关闭后不再保留完整请求与结果历史。
        displayedRun?.completedContext = null
        displayedRun = null
        removeOverlayWindows()
        if (runs.all().isEmpty() && !guiControl.state.value.userPaused) {
            VirtualDisplayController.release(this)
            stopSelf()
        }
    }

    private fun removeOverlayWindows() {
        mainHandler.removeCallbacksAndMessages(hideToken)
        overlayHost.removeAllWindows()
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean =
        msg.sendingUid == Process.myUid()

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isStoppedError(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        return error == LEGACY_STOPPED_ERROR ||
            error == SYNTHETIC_STOPPED_ERROR ||
            error == "Stopped" ||
            error == AppStrings.get(R.string.runtime_err_stopped, LEGACY_STOPPED_ERROR)
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    companion object {
        @Volatile
        private var activeInstance: AgentRuntimeService? = null

        // 有活跃任务且开启常亮开关时为 true；同步投影到 App 前台窗口、悬浮窗并持有屏幕唤醒锁。
        private val keepScreenOnState = MutableStateFlow(false)
        val keepScreenOn = keepScreenOnState.asStateFlow()

        internal fun onEtaUiVisibilityChanged() {
            val instance = activeInstance ?: return
            instance.mainHandler.post { instance.handleEtaUiVisibilityChanged() }
        }

        private const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        private const val WAKE_LOCK_TAG = "Eta:AgentRun"
        private const val LEGACY_STOPPED_ERROR = "已停止"
        private const val SYNTHETIC_STOPPED_ERROR = "eta_status:stopped"
        private const val HIDE_DELAY_MS = 2_500L
        private const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        private const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
