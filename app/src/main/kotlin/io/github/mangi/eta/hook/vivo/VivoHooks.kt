package io.github.mangi.eta.hook.vivo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.EtaInjectedStrings
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object VivoHooks {
    private val installed = AtomicBoolean()
    private val deferredHandles = CopyOnWriteArrayList<HookHandle>()
    private val active = AtomicReference<Run?>()
    private val history = VivoConversationHistory()
    private val ownership = VivoTurnOwnership()
    private val streamTokens = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 64
    })
    @Volatile private var streamingSupported = false
    private val cancellingNative = ThreadLocal<Boolean>()
    @Volatile private var islandNotifications: VivoIslandNotifications? = null
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        { runnable -> Thread(runnable, "Eta-VivoBridge").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun install(module: XposedModule, rootLogger: ModuleLogger, classLoader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Vivo")
        return hooks.install {
            val application = HookSupport.findClassOrNull(classLoader, "com.vivo.ai.copilot.CopilotApp")
            val onCreate = application?.let { HookSupport.findMethod(it, "onCreate") }
            if (onCreate == null) {
                missing("vivo.bootstrap", "CopilotApp.onCreate", "未找到小 V Application 入口")
                return@install
            }
            intercept("vivo.bootstrap", onCreate, "Vivo CopilotApp.onCreate") { chain ->
                val result = chain.proceed()
                val context = chain.thisObject as? Context
                val version = context?.let {
                    runCatching {
                        it.packageManager.getPackageInfo(
                            it.packageName, PackageManager.PackageInfoFlags.of(0),
                        ).longVersionCode
                    }.getOrDefault(-1L)
                } ?: -1L
                if (VivoTakeoverPolicy.isSupportedVersion(version)) {
                    if (context != null) {
                        islandNotifications = runCatching {
                            VivoIslandNotifications(context, logger, onStop = { runId ->
                                cancelActive(notify = true, expectedRunId = runId)
                            })
                        }.onFailure {
                            logger.warn("小 V 原子岛初始化不可用: type=${it.safeLogType()}")
                        }.getOrNull()
                        islandNotifications?.let { notifications ->
                            installIslandRelay(module, rootLogger, classLoader, context, notifications)
                        }
                    }
                    installBusinessHooks(module, rootLogger, classLoader)
                } else {
                    logger.warn("小 V 版本未适配，保持原生行为: versionCode=$version")
                }
                result
            }
        }
    }

    private fun installIslandRelay(
        module: XposedModule,
        logger: ModuleLogger,
        loader: ClassLoader,
        context: Context,
        notifications: VivoIslandNotifications,
    ) {
        val hooks = HookRegistrar(module, logger, "VivoIsland")
        val installation = hooks.install {
            val service = HookSupport.findClassOrNull(loader, VivoIslandWire.SERVICE)
            val onBind = service?.let { HookSupport.findMethod(it, "onBind", Intent::class.java) }
            if (onBind == null) {
                missing("vivo.island", "WidgetTaskService.onBind", "未找到 Eta 任务原子岛入口")
                return@install
            }
            val relay = VivoIslandRelay(context, notifications) { active.get() != null }
            intercept("vivo.island", onBind, "Eta runtime island binding") { chain ->
                if ((chain.args.firstOrNull() as? Intent)?.action == VivoIslandWire.ACTION_BIND) relay.binder
                else chain.proceed()
            }
        }
        deferredHandles += installation.handles
        logger.scoped("VivoIsland").info(installation.report.summary())
    }

    private fun installBusinessHooks(module: XposedModule, rootLogger: ModuleLogger, loader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val hooks = HookRegistrar(module, rootLogger, "Vivo")
        val installation = hooks.install {
            val targets = VivoChatBridge.Targets.resolve(loader)
            if (targets == null) {
                missing("vivo.text", "CopilotSpeechGptLinker.sendChat", "小 V 请求或回答协议不完整，保持原生行为")
                return@install
            }
            val streaming = VivoStreamingTargets.resolve(loader)
            if (streaming != null) {
                val handle = intercept("vivo.stream", streaming.onResult, "Vivo incremental reply accumulator") { chain ->
                    runCatching {
                        val processor = chain.thisObject
                        val message = chain.args.getOrNull(1)
                        if (processor != null && message != null) streaming.prepare(processor, message) { trace, request, token ->
                            ownership.owns(trace, request) && streamTokens["$trace:$request"] == token
                        }
                    }.onFailure { logger.warnThrottled("vivo_stream_accumulator_failed") {
                        "小 V 流式文本同步失败: type=${it.safeLogType()}"
                    } }
                    chain.proceed()
                }
                streamingSupported = handle != null
            } else missing("vivo.stream", "TalkBusinessProcessor.onResult", "未找到流式回答累加器，使用完整回答回写")
            intercept("vivo.cancel", targets.cancel, "Vivo GPT cancellation") { chain ->
                val node = (chain.args.firstOrNull() as? Enum<*>)?.name
                if (cancellingNative.get() != true && (node == "GPT" || node == "GPTONLY")) {
                    cancelActive(notify = true)
                }
                chain.proceed()
            }
            val events = runCatching { targets.nativeEvents(loader) }.getOrNull()
            if (events == null) {
                missing("vivo.decision", "CopilotEventCenter.post", "未找到小 V 语音完成入口")
            } else {
                intercept("vivo.decision", events.dispatch, "Vivo final text and ASR request") { chain ->
                    val event = chain.args.firstOrNull()
                    val claimed = try {
                        when {
                            event == null || VivoChatBridge.isPostingReply(event) || !events.isDecision(event) -> false
                            ownership.owns(events.traceId(event), events.requestId(event)) -> true
                            events.state(event) != 1 || events.fullDuplex(event) -> false
                            else -> {
                                val request = events.message(event)?.let(targets::request)
                                val accepted = request != null && tryClaim(logger, targets, request)
                                if (accepted) {
                                    // The speech SDK can already be generating a native reply when ASR ends.
                                    // Cancel it without treating our own cancellation as a user stopping Eta.
                                    cancellingNative.set(true)
                                    try {
                                        runCatching { events.cancelNativeReply() }.onFailure {
                                            logger.warn("小 V 原生回答取消失败: type=${it.safeLogType()}")
                                        }
                                    } finally {
                                        cancellingNative.remove()
                                    }
                                }
                                accepted
                            }
                        }
                    } catch (exception: Exception) {
                        logger.warnThrottled("vivo_decision_failed") {
                            "小 V 聊天事件检查失败: type=${exception.safeLogType()}"
                        }
                        false
                    }
                    if (claimed) null else chain.proceed()
                }
            }
            intercept("vivo.text", targets.sendChat, "Vivo ordinary text request") { chain ->
                val claimed = try {
                    val message = chain.args.firstOrNull()
                    val request = message?.let(targets::request)
                    request != null && tryClaim(logger, targets, request)
                } catch (exception: Exception) {
                    logger.warnThrottled("vivo_admission_failed") {
                        "小 V 接管检查失败: type=${exception.safeLogType()}"
                    }
                    false
                }
                if (claimed) null else {
                    cancelActive()
                    chain.proceed()
                }
            }
        }
        deferredHandles += installation.handles
        rootLogger.scoped("Vivo").info(installation.report.summary())
    }

    private fun tryClaim(logger: ModuleLogger, targets: VivoChatBridge.Targets, request: VivoChatBridge.Request): Boolean {
        if (ownership.owns(request.turn.traceId, request.turn.requestId)) return true
        if (!VivoTakeoverPolicy.isSupportedInputMode(request.inputMode, request.fullDuplex)) return false
        val prompt = VivoTakeoverPolicy.prompt(
            request.text, Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
            Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX), request.hasAttachments,
        ) ?: return false
        val context = AgentAppContext.resolve() ?: return false
        val bridge = targets.bind() ?: return false
        return claim(context, logger, request, prompt, bridge)
    }

    private fun claim(
        context: Context,
        logger: ModuleLogger,
        request: VivoChatBridge.Request,
        prompt: String,
        bridge: VivoChatBridge,
    ): Boolean {
        if (!ownership.claim(request.turn)) return true
        val run = Run(context, request, prompt, bridge)
        val task = FutureTask<Unit> { execute(context, logger, run) }
        run.task = task
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            ownership.release(request.turn)
            return false
        }
        // Once queued, ownership is irrevocable: a rendering failure must never send the prompt twice.
        active.getAndSet(run)?.cancel()
        streamTokens["${request.turn.traceId}:${request.turn.requestId}"] = run.id
        main.post {
            if (!run.cancelled.get() && active.get() === run) {
                islandNotifications?.start(run.id, request.turn.sessionId.ifBlank { request.turn.traceId })
                runCatching { bridge.start(request) }.onFailure {
                    logger.warn("小 V 等待状态回写失败: type=${it.safeLogType()}")
                }
            }
            run.activated.countDown()
        }
        logger.info("已接管小 V 请求: inputMode=${request.inputMode}, queryChars=${prompt.length}")
        return true
    }

    private fun execute(context: Context, logger: ModuleLogger, run: Run) {
        try {
            run.activated.await()
            if (run.cancelled.get() || active.get() !== run) return
            val config = AgentModelClient.loadConfig()
            if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank() ||
                config.contextWindow == null || config.contextWindow <= 0
            ) {
                deliver(logger, run, text(context, R.string.injected_vivo_configure_model,
                    "Open Eta and configure a model, API key and context window first."))
                islandNotifications?.finish(run.id, VivoIslandNotifications.State.FAILED)
                return
            }
            val client = AgentRuntimeClient(context, logger)
            val result = client.run(
                AgentRuntimeWire.RunRequest(
                    runId = run.id,
                    prompt = run.prompt,
                    config = config,
                    images = emptyList(),
                    history = history.snapshot(run.request.turn.sessionId),
                    handoff = VivoHandoff.create(
                        run.id, run.request.turn.traceId, run.request.turn.requestId,
                        run.request.turn.sessionId, run.prompt,
                    ),
                ),
                onEvent = { event -> updateRun(logger, run, event) },
            )
            val content = if (result.ok) result.content.trim().ifBlank {
                text(context, R.string.injected_completed, "Eta completed this task")
            } else {
                text(context, R.string.injected_failed, "Eta could not complete the task. Try again later")
            }
            if (deliver(logger, run, content)) {
                islandNotifications?.finish(run.id,
                    if (result.ok) VivoIslandNotifications.State.COMPLETED else VivoIslandNotifications.State.FAILED)
                if (result.ok) history.remember(run.request.turn.sessionId, run.prompt, content)
                client.ackResult(result.runId.ifBlank { run.id })
                logger.info("小 V 结果已回写: ok=${result.ok}")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: Exception) {
            logger.warn("小 V Runtime 执行失败: type=${exception.safeLogType()}")
            deliver(logger, run,
                text(context, R.string.injected_failed, "Eta could not complete the task. Try again later"))
            islandNotifications?.finish(run.id, VivoIslandNotifications.State.FAILED)
        } finally {
            if (active.compareAndSet(run, null)) {
                // Also terminate the island if delivery or the worker was interrupted unexpectedly.
                islandNotifications?.finish(run.id, VivoIslandNotifications.State.FAILED)
            }
        }
    }

    private fun updateRun(logger: ModuleLogger, run: Run, event: io.github.mangi.eta.agent.runtime.AgentEvent) {
        if (run.cancelled.get() || active.get() !== run) return
        islandNotifications?.update(run.id, event)
        if (streamingSupported && run.reply.accept(event) && run.flushScheduled.compareAndSet(false, true)) {
            main.postDelayed({
                run.flushScheduled.set(false)
                if (!run.cancelled.get() && active.get() === run) runCatching {
                    run.reply.flush()?.let { run.bridge.stream(run.request, run.id, it) }
                }.onFailure { logger.warnThrottled("vivo_stream_delivery_failed") {
                    "小 V 流式回写失败: type=${it.safeLogType()}"
                } }
            }, 80)
        }
    }

    private fun deliver(logger: ModuleLogger, run: Run, content: String): Boolean {
        if (run.cancelled.get() || active.get() !== run) return false
        val delivered = AtomicBoolean()
        val latch = CountDownLatch(1)
        main.post {
            try {
                if (!run.cancelled.get() && active.get() === run) {
                    completeReply(run, content)
                    delivered.set(true)
                }
            } catch (exception: Exception) {
                logger.warn("小 V 结果回写失败: type=${exception.safeLogType()}")
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(5, TimeUnit.SECONDS) && delivered.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun text(context: Context, id: Int, fallback: String) = EtaInjectedStrings.get(context, id, fallback)

    private fun completeReply(run: Run, content: String) {
        if (streamingSupported) run.reply.complete(content).forEach { run.bridge.stream(run.request, run.id, it) }
        else run.bridge.complete(run.request, content)
    }

    private fun cancelActive(notify: Boolean = false, expectedRunId: String? = null) {
        val run = active.get() ?: return
        if (expectedRunId != null && run.id != expectedRunId) return
        if (!active.compareAndSet(run, null)) return
        run.cancel()
        if (notify) main.post {
            runCatching {
                if (active.get() == null) completeReply(run, text(run.context, R.string.overlay_stopped, "Stopped"))
            }
        }
    }

    private class Run(
        val context: Context,
        val request: VivoChatBridge.Request,
        val prompt: String,
        val bridge: VivoChatBridge,
    ) {
        val id = UUID.randomUUID().toString()
        val activated = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val reply = VivoReplyStream()
        val flushScheduled = AtomicBoolean()
        lateinit var task: FutureTask<Unit>

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            islandNotifications?.finish(id, VivoIslandNotifications.State.CANCELLED)
            activated.countDown()
            task.cancel(true)
            executor.remove(task)
        }
    }
}
