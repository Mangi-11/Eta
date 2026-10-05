package io.github.mangi.eta.hook.vivo

import android.content.Context
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
                    installBusinessHooks(module, rootLogger, classLoader)
                } else {
                    logger.warn("小 V 版本未适配，保持原生行为: versionCode=$version")
                }
                result
            }
        }
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
            intercept("vivo.cancel", targets.cancel, "Vivo GPT cancellation") { chain ->
                val node = (chain.args.firstOrNull() as? Enum<*>)?.name
                if (node == "GPT" || node == "GPTONLY") cancelActive()
                chain.proceed()
            }
            intercept("vivo.text", targets.sendChat, "Vivo ordinary text request") { chain ->
                val claimed = try {
                    val message = chain.args.firstOrNull()
                    val request = message?.let(targets::request)
                    val prompt = request?.let {
                        VivoTakeoverPolicy.prompt(
                            it.text, Prefs.isEnabled(Prefs.Keys.VIVO_CUSTOM_MODEL),
                            Prefs.isEnabled(Prefs.Keys.VIVO_REQUIRE_PREFIX), it.hasAttachments,
                        )
                    }
                    if (request != null && prompt != null) {
                        val context = AgentAppContext.resolve()
                        val bridge = targets.bind()
                        context != null && bridge != null && claim(context, logger, request, prompt, bridge)
                    } else false
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

    private fun claim(
        context: Context,
        logger: ModuleLogger,
        request: VivoChatBridge.Request,
        prompt: String,
        bridge: VivoChatBridge,
    ): Boolean {
        val run = Run(request, prompt, bridge)
        val task = FutureTask<Unit> { execute(context, logger, run) }
        run.task = task
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            return false
        }
        // Once queued, ownership is irrevocable: a rendering failure must never send the prompt twice.
        active.getAndSet(run)?.cancel()
        main.post {
            if (!run.cancelled.get() && active.get() === run) {
                runCatching { bridge.start(request) }.onFailure {
                    logger.warn("小 V 等待状态回写失败: type=${it.safeLogType()}")
                }
            }
            run.activated.countDown()
        }
        logger.info("已接管小 V 文字请求: queryChars=${prompt.length}")
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
                onEvent = {},
            )
            val content = if (result.ok) result.content.trim().ifBlank {
                text(context, R.string.injected_completed, "Eta completed this task")
            } else {
                text(context, R.string.injected_failed, "Eta could not complete the task. Try again later")
            }
            if (deliver(logger, run, content)) {
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
        } finally {
            active.compareAndSet(run, null)
        }
    }

    private fun deliver(logger: ModuleLogger, run: Run, content: String): Boolean {
        if (run.cancelled.get() || active.get() !== run) return false
        val delivered = AtomicBoolean()
        val latch = CountDownLatch(1)
        main.post {
            try {
                if (!run.cancelled.get() && active.get() === run) {
                    run.bridge.complete(run.request, content)
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

    private fun cancelActive() {
        active.getAndSet(null)?.cancel()
    }

    private class Run(val request: VivoChatBridge.Request, val prompt: String, val bridge: VivoChatBridge) {
        val id = UUID.randomUUID().toString()
        val activated = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        lateinit var task: FutureTask<Unit>

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            activated.countDown()
            task.cancel(true)
            executor.remove(task)
        }
    }
}
