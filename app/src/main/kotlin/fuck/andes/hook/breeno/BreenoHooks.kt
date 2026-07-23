package fuck.andes.hook.breeno

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType
import fuck.andes.core.toSafeLogToken

import android.os.Handler
import android.os.Looper
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal object BreenoHooks {
    private const val MESSAGE_QUEUE_MANAGER_CLASS =
        "com.heytap.speech.engine.connect.core.manager.MessageQueueManager"
    private const val MESSAGE_CLASS = "com.heytap.speech.engine.protocol.event.Message"
    private const val MESSAGE_PROCESSOR_CLASS =
        "com.heytap.speech.engine.connect.core.manager.i"
    private const val CDM_NODE_CLASS = "com.heytap.speech.engine.nodes.a"
    private const val DM_PARAMETER_CLASS = "com.heytap.speech.engine.nodes.DmParameter"
    private const val HEYTAP_SPEECH_ENGINE_CLASS = "com.heytap.speech.engine.HeytapSpeechEngine"
    private const val DIRECTIVE_CLASS = "com.heytap.speech.engine.protocol.directive.Directive"
    private const val DIRECTIVE_HEADER_CLASS = "com.heytap.speech.engine.protocol.directive.DirectiveHeader"
    private const val DIRECTIVE_PAYLOAD_CLASS = "com.heytap.speech.engine.protocol.directive.DirectivePayload"
    private const val STREAM_TEXT_CARD_CLASS =
        "com.heytap.speech.engine.protocol.directive.myai.StreamTextCard"
    private const val AI_CHAT_REPOSITORY_CLASS =
        "com.heytap.speechassist.aichat.repository.AIChatRepository"
    private const val AI_CHAT_DATA_CENTER_CLASS =
        "com.heytap.speechassist.aichat.AIChatDataCenter"
    private const val AI_CHAT_VIEW_BEAN_CLASS =
        "com.heytap.speechassist.aichat.bean.AIChatViewBean"
    private const val AI_CHAT_ROOM_ID_MANAGER_CLASS =
        "com.heytap.speechassist.aichat.AIChatRoomIdManager"
    private const val AI_CHAT_FAST_MODE_STATE_MANAGER_CLASS =
        "com.heytap.speechassist.aichathome.chat.ui.tip.AiChatFastModeStateManager"
    private const val INSERT_RECORD_CLASS =
        "com.heytap.speechassist.aichat.repository.api.InsertRecord"
    private const val JSON_UTIL_CLASS = "com.heytap.speechassist.utils.j3"
    private const val KOTLIN_FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
    private const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
    private const val EXPERIMENTAL_PREFIX = "/agent "
    private const val EXPERIMENTAL_ADB_PREFIX = "/agent%20"
    private const val BREENO_HANDOFF_SOURCE = "breeno"
    private const val BREENO_DEFAULT_AGENT_NAME = "default"
    private const val INJECTED_MARKER_KEY = "fuckAndesAgent"
    private const val AI_CHAT_TYPE_QUERY = 1
    private const val AI_CHAT_TYPE_ANSWER = 2
    private const val AGENT_REQUEST_DEDUP_WINDOW_MS = 12_000L
    private const val CLAIMED_ROOM_TTL_MS = 120_000L
    private const val INJECTED_ANSWER_TTL_MS = 45_000L
    private const val NATIVE_DIRECTIVE_SUPPRESS_TTL_MS = 120_000L
    private const val RECORD_TYPE_QUERY = "Q"
    private const val RECORD_TYPE_ANSWER = "A"
    private const val BREENO_REASONING_STATE = "深度思考"
    private const val BREENO_STREAM_FLUSH_DELAY_MS = 80L
    private const val BREENO_STREAM_FLUSH_CHARS = 48
    private const val BREENO_ARCHIVE_TITLE_CHARS = 20
    private const val MAX_PROTOCOL_EVENTS = 32
    private const val MAX_INBOUND_DIRECTIVE_CHARS = 512 * 1024
    private const val AGENT_BRIDGE_THREADS = 3
    private const val AGENT_BRIDGE_QUEUE_CAPACITY = 16
    private const val CDM_IMAGE_CACHE_ENTRIES = 16
    private const val CDM_IMAGE_CACHE_ALIASES = 48
    private const val CDM_IMAGE_CACHE_ESTIMATED_CHARS = 2L * 1024L * 1024L
    private const val CDM_IMAGE_CACHE_TTL_MS = 30_000L
    private const val HANDLED_RUN_ID_CAPACITY = 64
    private const val HANDLED_RUN_ID_TTL_MS = 12L * 60L * 60L * 1000L
    private const val PENDING_ACK_CAPACITY = 32
    private const val PENDING_ACK_BATCH_SIZE = 8
    private const val PENDING_ACK_RESCAN_ATTEMPTS = 3
    private const val PENDING_ACK_RETRY_ATTEMPTS = 6
    private const val PENDING_ACK_RETRY_DELAY_MS = 750L
    private val agentBridgeThreadId = AtomicInteger()
    private val modelExecutor = ThreadPoolExecutor(
        AGENT_BRIDGE_THREADS,
        AGENT_BRIDGE_THREADS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(AGENT_BRIDGE_QUEUE_CAPACITY),
        { runnable ->
            Thread(
                runnable,
                "Eta-AgentBridge-${agentBridgeThreadId.incrementAndGet()}"
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val cdmImageCache = BreenoRequestImages.SnapshotCache(
        maxEntries = CDM_IMAGE_CACHE_ENTRIES,
        maxAliases = CDM_IMAGE_CACHE_ALIASES,
        maxEstimatedChars = CDM_IMAGE_CACHE_ESTIMATED_CHARS,
        ttlMillis = CDM_IMAGE_CACHE_TTL_MS,
    )
    private val handledRuntimeRunIds = BoundedRunIdSet(
        capacity = HANDLED_RUN_ID_CAPACITY,
        ttlMillis = HANDLED_RUN_ID_TTL_MS,
    )
    // 只有已经展示或明确废弃的结果才能进入 ACK 重扫，避免交付过程中被提前确认。
    private val acknowledgeableRuntimeRunIds = BoundedRunIdSet(
        capacity = HANDLED_RUN_ID_CAPACITY,
        ttlMillis = HANDLED_RUN_ID_TTL_MS,
    )
    private val pendingRuntimeAcks = PendingAckState(
        capacity = PENDING_ACK_CAPACITY,
        rescanAttempts = PENDING_ACK_RESCAN_ATTEMPTS,
    )
    private val startedAgentRequests = ConcurrentHashMap<String, Long>()
    private val claimedAgentRooms = ConcurrentHashMap<String, Long>()
    private val injectedAnswerSignatures = ConcurrentHashMap<String, Long>()
    private val historyPersistenceInFlight = BoundedRunIdSet(
        capacity = HANDLED_RUN_ID_CAPACITY,
        ttlMillis = HANDLED_RUN_ID_TTL_MS,
    )
    private val pendingDrainRunning = AtomicBoolean(false)
    private val pendingAckDrainRunning = AtomicBoolean(false)
    private val pendingAckRetryScheduled = AtomicBoolean(false)
    private val pendingAckRetryBudget = BoundedRetryBudget(PENDING_ACK_RETRY_ATTEMPTS)
    private val activeAgentRun = AtomicReference<ActiveAgentRun?>()
    @Volatile
    private var lastBreenoThinkingEnabledOverride: Boolean? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Breeno")
        val logger = hooks.logger
        return hooks.install {
            hookOutboundMessage(hooks, classLoader)
            hookInboundMessage(hooks, classLoader)
            hookCdmTextRequest(hooks, classLoader)
            hookAIChatDataCenter(hooks, classLoader)
            schedulePendingResultDrains(logger, classLoader)
        }
    }

    private fun hookOutboundMessage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val managerClass = HookSupport.findClassOrNull(classLoader, MESSAGE_QUEUE_MANAGER_CLASS)
        val messageClass = HookSupport.findClassOrNull(classLoader, MESSAGE_CLASS)
        if (managerClass == null || messageClass == null) {
            hooks.missing(
                id = "breeno.outbound-message",
                description = "MessageQueueManager.c",
                detail = "未找到 MessageQueueManager/Message，跳过出站接管"
            )
            return
        }
        val method = HookSupport.findMethod(
            managerClass,
            "c",
            messageClass,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaObjectType,
            Boolean::class.javaPrimitiveType!!
        )
        if (method == null) {
            hooks.missing(
                id = "breeno.outbound-message",
                description = "MessageQueueManager.c",
                detail = "未找到 MessageQueueManager.c(Message,boolean,Integer,boolean)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.outbound-message",
            executable = method,
            description = "Breeno MessageQueueManager.c"
        ) { chain ->
            val message = chain.args.getOrNull(0)
            if (maybeHandleCustomModelRequest(logger, classLoader, message)) {
                return@intercept null
            }
            try {
                logger.debug { "outbound: ${summarizeOutboundMessage(message)}" }
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_outbound_log_failed") {
                    "记录出站消息失败，type=${exception.safeLogType()}"
                }
            }
            chain.proceed()
        }
    }

    private fun hookInboundMessage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val processorClass = HookSupport.findClassOrNull(classLoader, MESSAGE_PROCESSOR_CLASS)
        if (processorClass == null) {
            hooks.missing(
                id = "breeno.inbound-message",
                description = "MessageProcessor.B",
                detail = "未找到 MessageProcessor，跳过入站接管"
            )
            return
        }
        val method = HookSupport.findMethod(
            processorClass,
            "B",
            String::class.java,
            String::class.java
        )
        if (method == null) {
            hooks.missing(
                id = "breeno.inbound-message",
                description = "MessageProcessor.B",
                detail = "未找到 MessageProcessor.B(String,String)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.inbound-message",
            executable = method,
            description = "Breeno MessageProcessor.B"
        ) { chain ->
            val content = chain.args.getOrNull(1) as? String
            val filteredContent = filterClaimedNativeDirectives(logger, content)
            try {
                if (filteredContent == null && content != null) {
                    logger.debug { "inbound suppressed" }
                } else {
                    logger.debug { "inbound: ${summarizeInboundMessage(filteredContent ?: content)}" }
                }
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_inbound_log_failed") {
                    "记录入站消息失败，type=${exception.safeLogType()}"
                }
            }
            when {
                filteredContent == null && content != null -> null
                filteredContent != null && filteredContent != content -> {
                    val args = chain.args.toTypedArray()
                    args[1] = filteredContent
                    chain.proceed(args)
                }
                else -> chain.proceed()
            }
        }
    }

    private fun hookCdmTextRequest(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val cdmNodeClass = HookSupport.findClassOrNull(classLoader, CDM_NODE_CLASS)
        val dmParameterClass = HookSupport.findClassOrNull(classLoader, DM_PARAMETER_CLASS)
        if (cdmNodeClass == null || dmParameterClass == null) {
            hooks.missing(
                id = "breeno.cdm-text-request",
                description = "CdmNode.o",
                detail = "未找到 CdmNode/DmParameter，跳过文本请求观测"
            )
            return
        }
        val method = HookSupport.findMethod(cdmNodeClass, "o", dmParameterClass)
        if (method == null) {
            hooks.missing(
                id = "breeno.cdm-text-request",
                description = "CdmNode.o",
                detail = "未找到 CdmNode.o(DmParameter)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.cdm-text-request",
            executable = method,
            description = "Breeno CdmNode.o"
        ) { chain ->
            val parameter = chain.args.getOrNull(0)
            try {
                logger.debug { "CDM request: ${summarizeDmParameter(parameter)}" }
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_cdm_log_failed") {
                    "记录 CdmNode 请求失败，type=${exception.safeLogType()}"
                }
            }
            try {
                cacheCdmImages(logger, parameter)
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_cdm_image_cache_failed") {
                    "缓存 CdmNode 图片引用失败，type=${exception.safeLogType()}"
                }
            }
            chain.proceed()
        }
    }

    private fun hookAIChatDataCenter(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val dataCenterClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_DATA_CENTER_CLASS)
        val viewBeanClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_VIEW_BEAN_CLASS)
        if (dataCenterClass == null || viewBeanClass == null) {
            hooks.missing(
                id = "breeno.ai-chat-data-center",
                description = "AIChatDataCenter.r",
                detail = "未找到 AIChatDataCenter/AIChatViewBean，跳过对话 UI 接管"
            )
            return
        }
        val method = HookSupport.findMethod(dataCenterClass, "r", viewBeanClass)
        if (method == null) {
            hooks.missing(
                id = "breeno.ai-chat-data-center",
                description = "AIChatDataCenter.r",
                detail = "未找到 AIChatDataCenter.r(AIChatViewBean)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.ai-chat-data-center",
            executable = method,
            description = "Breeno AIChatDataCenter.r"
        ) { chain ->
            val bean = chain.args.getOrNull(0)
            when (invokeInt(bean, "getChatType")) {
                AI_CHAT_TYPE_QUERY -> {
                    handleAIChatQuery(logger, classLoader, bean)
                    chain.proceed()
                }
                AI_CHAT_TYPE_ANSWER -> {
                    if (shouldBlockAIChatAnswer(logger, bean)) {
                        null
                    } else {
                        chain.proceed()
                    }
                }
                else -> chain.proceed()
            }
        }
    }

    private fun handleAIChatQuery(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        bean: Any?
    ) {
        if (bean == null) return
        val text = invokeString(bean, "getContent")?.trim().orEmpty()
        if (text.isBlank()) return
        val roomId = invokeString(bean, "getRoomId").orEmpty()
        val recordId = invokeString(bean, "getRecordId").orEmpty()
        val prompt = resolveCustomModelPrompt(text)
        if (prompt.isNullOrBlank()) {
            if (roomId.isNotBlank()) claimedAgentRooms.remove(roomId)
            return
        }
        val stableRecordId = recordId.ifBlank { newCompactId() }

        val request = TextRequest(
            runId = newCompactId(),
            text = text,
            imageSnapshot = cachedImageSnapshotFor(recordId, roomId),
            recordId = stableRecordId,
            originalRecordId = stableRecordId,
            sessionId = "",
            roomId = roomId,
            thinkingEnabledOverride = currentBreenoThinkingEnabledOverride(classLoader),
            queryPayload = invokeString(bean, "getPayload"),
            queryClientResult = serializeForBreenoHistory(
                classLoader,
                HookSupport.invokeNoArgs(bean, "getClientResult"),
            ),
        )
        val handled = startAgentRequest(
            logger = logger,
            classLoader = classLoader,
            request = request,
            prompt = prompt,
            logSource = "aichat"
        )
        if (handled && roomId.isNotBlank()) {
            rememberClaimedAgentRoom(roomId)
        }
    }

    private fun shouldBlockAIChatAnswer(logger: ModuleLogger, bean: Any?): Boolean {
        val roomId = invokeString(bean, "getRoomId").orEmpty()
        if (roomId.isBlank() || !isClaimedAgentRoom(roomId)) return false
        val content = invokeString(bean, "getContent").orEmpty()
        if (isOwnInjectedAnswer(roomId, content) || hasClientLocalData(bean, INJECTED_MARKER_KEY)) {
            return false
        }
        logger.debug { "Breeno native AIChat answer blocked: contentChars=${content.length}" }
        return true
    }

    private fun filterClaimedNativeDirectives(
        logger: ModuleLogger,
        content: String?
    ): String? {
        if (content.isNullOrBlank()) return content
        if (claimedAgentRooms.isEmpty()) return content
        if (content.length > MAX_INBOUND_DIRECTIVE_CHARS) {
            logger.warnThrottled("breeno_native_directive_too_large") {
                "入站指令超过解析上限，保留原始消息"
            }
            return content
        }
        return try {
            val json = JSONObject(content)
            val roomId = json.optString("roomId")
            if (roomId.isBlank() || !isClaimedAgentRoom(roomId, NATIVE_DIRECTIVE_SUPPRESS_TTL_MS)) {
                return content
            }
            if (json.optJSONObject("extend")?.optString(INJECTED_MARKER_KEY) == "true") {
                return content
            }
            val directives = json.optJSONArray("directives") ?: return content
            val kept = JSONArray()
            var removed = 0
            for (index in 0 until directives.length()) {
                val directive = directives.optJSONObject(index)
                if (directive == null) {
                    kept.put(directives.opt(index))
                    continue
                }
                val header = directive.optJSONObject("header")
                val namespace = header?.optString("namespace").orEmpty()
                val name = header?.optString("name").orEmpty()
                if (shouldSuppressNativeDirective(namespace, name)) {
                    removed++
                } else {
                    kept.put(directive)
                }
            }
            if (removed == 0) return content
            logger.debug {
                "native directives suppressed: " +
                    "removed=$removed, kept=${kept.length()}"
            }
            if (kept.length() == 0) {
                null
            } else {
                json.put("directives", kept).toString()
            }
        } catch (exception: Exception) {
            logger.warnThrottled("breeno_native_directive_filter_failed") {
                "过滤原生指令失败，type=${exception.safeLogType()}"
            }
            content
        }
    }

    private fun shouldSuppressNativeDirective(namespace: String, name: String): Boolean =
        when (namespace) {
            "MyAI" -> name == "LoadingStateCard" || name == "StreamTextCard"
            "App",
            "AnalogClick",
            "System",
            "SystemScreen",
            "Sms",
            "PhoneCall",
            "Ocr" -> true
            "SpeechSynthesizer" -> true
            "Recommend" -> true
            "Tracking" -> name == "BreenoFeedback"
            "SpeechRecognizer" -> name == "ExpectSpeech"
            else -> false
        }

    private fun summarizeOutboundMessage(message: Any?): String {
        if (message == null) return "message=null"
        val events = HookSupport.invokeNoArgs(message, "getEvents") as? Iterable<*>
        val eventSummaries = events
            ?.mapNotNull { event ->
                val header = HookSupport.invokeNoArgs(event ?: return@mapNotNull null, "getHeader")
                val namespace = invokeString(header, "getNamespace")
                val name = invokeString(header, "getName")
                protocolEventLabel(namespace, name)
            }
            .orEmpty()
        return "eventCount=${eventSummaries.size}, " +
            "events=${eventSummaries.joinToString(prefix = "[", postfix = "]")}"
    }

    private fun maybeHandleCustomModelRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        message: Any?
    ): Boolean {
        val request = extractTextRequest(classLoader, message) ?: return false
        val prompt = resolveCustomModelPrompt(request.text) ?: return false
        if (prompt.isBlank()) return false

        return startAgentRequest(
            logger = logger,
            classLoader = classLoader,
            request = request,
            prompt = prompt,
            logSource = "text"
        )
    }

    private fun startAgentRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        prompt: String,
        logSource: String
    ): Boolean {
        val requestKey = agentRequestKey(request, prompt)
        if (!markAgentRequestStarted(requestKey)) {
            logger.debug {
                "Breeno custom model duplicate skipped: source=$logSource, " +
                    "promptChars=${prompt.length}"
            }
            return true
        }
        var scheduled = false
        return runCatching {
            val renderRequest = request.copy(text = prompt)
            val streamRenderer = BreenoStreamRenderer(
                logger = logger,
                classLoader = classLoader,
                request = renderRequest
            )
            val runState = ActiveAgentRun(
                renderer = streamRenderer
            )
            val backgroundTask = Runnable {
                if (!runState.awaitActivation() || activeAgentRun.get() !== runState) {
                    return@Runnable
                }
                var ackRunId: String? = null
                val modelResponse = runCatching {
                    val baseConfig = AgentModelClient.loadConfig()
                    val config = request.thinkingEnabledOverride
                        ?.let { baseConfig.copy(thinkingEnabled = it) }
                        ?: baseConfig
                    if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) {
                        error("请先在 Eta 设置中启用“小布自定义模型”")
                    }
                    val context = AgentAppContext.resolve()
                        ?: error("无法获取小布进程 Context")
                    val images = when (
                        val resolution = BreenoRequestImages.resolve(context, request.imageSnapshot)
                    ) {
                        is BreenoRequestImages.Resolution.Success -> resolution.images
                        is BreenoRequestImages.Resolution.Failure -> {
                            logger.warnThrottled("breeno_${resolution.code.value}") {
                                "图片处理失败: code=${resolution.code.value}, images=${resolution.imageCount}, " +
                                    "estimated=${resolution.estimatedBytes}, " +
                                    "limit=${resolution.maxBytes}"
                            }
                            error(resolution.message)
                        }
                    }
                    val result = AgentRuntimeClient(context, logger).run(
                        request = AgentRuntimeWire.RunRequest(
                            runId = request.runId,
                            prompt = prompt,
                            config = config,
                            images = images,
                            handoff = request.toRuntimeHandoff(prompt)
                        )
                    ) { event ->
                        if (activeAgentRun.get() === runState) {
                            streamRenderer.onEvent(event)
                        }
                    }
                    ackRunId = result.runId.ifBlank { null }
                    if (!result.ok) {
                        error(result.error ?: "Agent Runtime 调用失败")
                    }
                    AgentModelClient.ModelResponse.Text(
                        content = result.content,
                        reasoningContent = result.reasoningContent
                    )
                }.getOrElse { throwable ->
                    AgentModelClient.ModelResponse.Text(
                        "小布自定义模型调用失败：${throwable.message ?: throwable.javaClass.simpleName}"
                    )
                }
                Handler(Looper.getMainLooper()).post {
                    if (activeAgentRun.get() !== runState) {
                        streamRenderer.cancel()
                        ackRuntimeResult(logger, ackRunId ?: request.runId)
                        logger.debug { "Breeno obsolete Agent result skipped" }
                        return@post
                    }
                    val deliveredRunId = ackRunId ?: request.runId
                    var deliveryMarked = false
                    runCatching {
                        if (!markRunDelivered(deliveredRunId)) {
                            streamRenderer.cancel()
                            if (acknowledgeableRuntimeRunIds.contains(deliveredRunId)) {
                                ackRuntimeResult(logger, deliveredRunId)
                            } else if (ackRunId != null) {
                                persistHistoryAndAck(
                                    logger = logger,
                                    classLoader = classLoader,
                                    request = request.copy(text = prompt),
                                    response = modelResponse,
                                    runId = deliveredRunId,
                                )
                            }
                            logger.debug { "Breeno Agent result already delivered" }
                            return@runCatching
                        }
                        deliveryMarked = true
                        val roomId = request.roomId
                            .ifBlank { currentRoomId(classLoader) }
                        val injectedRequest = request.copy(
                            text = prompt,
                            roomId = roomId
                        )
                        rememberInjectedAnswer(injectedRequest, modelResponse.content)
                        if (!streamRenderer.finish(modelResponse, injectedRequest)) {
                            injectModelResponse(classLoader, injectedRequest, modelResponse)
                        }
                        if (ackRunId == null) {
                            persistChatHistory(logger, classLoader, injectedRequest, modelResponse)
                        } else {
                            persistHistoryAndAck(
                                logger = logger,
                                classLoader = classLoader,
                                request = injectedRequest,
                                response = modelResponse,
                                runId = deliveredRunId,
                            )
                        }
                        logger.info(
                            "Breeno custom model injected: ${modelResponse.summary()}"
                        )
                    }.onFailure { throwable ->
                        if (deliveryMarked) handledRuntimeRunIds.remove(deliveredRunId)
                        logger.error(
                            "Breeno: 注入自定义模型响应失败，type=${throwable.safeLogType()}"
                        )
                    }
                    activeAgentRun.compareAndSet(runState, null)
                }
            }
            val future = FutureTask(backgroundTask, Unit)
            runState.future = future
            try {
                modelExecutor.execute(future)
            } catch (throwable: RejectedExecutionException) {
                runState.cancelBeforeActivation()
                future.cancel(false)
                streamRenderer.cancel()
                throw throwable
            }
            scheduled = true
            val previousRun = activeAgentRun.getAndSet(runState)
            try {
                previousRun?.cancel(logger)
            } finally {
                runState.activate()
            }
            logger.info(
                "Breeno custom model takeover: source=$logSource, promptChars=${prompt.length}, " +
                    "imageInputs=${request.imageSnapshot.inputCount}, " +
                    "thinking=${request.thinkingEnabledOverride}"
            )
            true
        }.getOrElse { throwable ->
            if (!scheduled) startedAgentRequests.remove(requestKey)
            logger.error(
                "Breeno: 接管自定义模型请求失败，放行原请求，type=${throwable.safeLogType()}"
            )
            scheduled
        }
    }

    private fun injectModelResponse(
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text
    ) {
        injectStreamTextCard(
            classLoader = classLoader,
            request = request,
            content = response.content,
            reasoningContent = response.reasoningContent
        )
    }

    private fun resolveCustomModelPrompt(text: String): String? {
        text.removeExperimentalPrefixOrNull()?.let { return it }
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) return null
        if (Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX)) return null
        return text.trim()
    }

    private fun schedulePendingResultDrains(
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val handler = Handler(Looper.getMainLooper())
        longArrayOf(800L, 2_500L, 6_000L, 15_000L, 30_000L).forEach { delayMs ->
            handler.postDelayed({
                drainPendingRuntimeResults(logger, classLoader)
            }, delayMs)
        }
    }

    private fun drainPendingRuntimeResults(
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        if (!pendingDrainRunning.compareAndSet(false, true)) return
        val context = AgentAppContext.resolve()
        if (context == null) {
            pendingDrainRunning.set(false)
            return
        }
        val accepted = executeAgentBackground(logger, "breeno_pending_drain_rejected") {
            val completedRuns = runCatching {
                AgentRuntimeClient(context, logger).drainCompletedRuns()
            }.getOrElse { throwable ->
                logger.warnThrottled("breeno_pending_drain_failed") {
                    "Breeno: 拉取 Agent 未交付结果失败，type=${throwable.safeLogType()}"
                }
                emptyList()
            }
            val breenoRuns = completedRuns.filter { it.handoff.source == BREENO_HANDOFF_SOURCE }
            if (breenoRuns.isEmpty()) {
                pendingDrainRunning.set(false)
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        breenoRuns.forEach { completedRun ->
                            injectCompletedRun(logger, classLoader, completedRun)
                        }
                    } finally {
                        pendingDrainRunning.set(false)
                    }
                }
            }
        }
        if (!accepted) pendingDrainRunning.set(false)
    }

    private fun injectCompletedRun(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        completedRun: AgentRuntimeWire.CompletedRun
    ) {
        val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
        val request = textRequestFromHandoff(completedRun.handoff, runId)
        if (request == null) {
            logger.debug { "Breeno: 忽略非小布 Agent 未交付结果" }
            return
        }
        val result = completedRun.result
        val content = if (result.ok) {
            result.content
        } else {
            "小布自定义模型调用失败：${result.error ?: "Agent Runtime 调用失败"}"
        }
        val response = AgentModelClient.ModelResponse.Text(
            content = content,
            reasoningContent = if (result.ok) result.reasoningContent else ""
        )
        var deliveryMarked = false
        runCatching {
            if (!markRunDelivered(runId)) {
                if (acknowledgeableRuntimeRunIds.contains(runId)) {
                    ackRuntimeResult(logger, runId)
                } else {
                    persistHistoryAndAck(logger, classLoader, request, response, runId)
                }
                logger.debug { "Breeno pending Agent result already delivered" }
                return@runCatching
            }
            deliveryMarked = true
            rememberInjectedAnswer(request, content)
            injectModelResponse(
                classLoader,
                request,
                response
            )
            persistHistoryAndAck(
                logger = logger,
                classLoader = classLoader,
                request = request,
                response = response,
                runId = runId,
            )
            logger.info("Breeno pending Agent result injected: ${response.summary()}")
        }.onFailure { throwable ->
            if (deliveryMarked) handledRuntimeRunIds.remove(runId)
            logger.error(
                "Breeno: 注入未交付 Agent 结果失败，type=${throwable.safeLogType()}"
            )
        }
    }

    private fun markRunDelivered(runId: String): Boolean =
        runId.isBlank() || handledRuntimeRunIds.add(runId)

    private fun markAgentRequestStarted(key: String): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(startedAgentRequests, now, AGENT_REQUEST_DEDUP_WINDOW_MS)
        val previous = startedAgentRequests.putIfAbsent(key, now)
        return previous == null || now - previous > AGENT_REQUEST_DEDUP_WINDOW_MS
    }

    private fun agentRequestKey(request: TextRequest, prompt: String): String {
        val anchor = request.roomId
            .ifBlank { request.recordId }
            .ifBlank { request.originalRecordId }
            .ifBlank { request.sessionId }
        return breenoRequestDedupKey(
            anchor = anchor.ifBlank { "global" },
            prompt = prompt.trim(),
        )
    }

    private fun rememberClaimedAgentRoom(roomId: String) {
        val now = System.currentTimeMillis()
        pruneTimedMap(claimedAgentRooms, now, CLAIMED_ROOM_TTL_MS)
        claimedAgentRooms[roomId] = now
    }

    private fun isClaimedAgentRoom(
        roomId: String,
        ttlMs: Long = CLAIMED_ROOM_TTL_MS
    ): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(claimedAgentRooms, now, ttlMs)
        val claimedAt = claimedAgentRooms[roomId] ?: return false
        return now - claimedAt <= ttlMs
    }

    private fun rememberInjectedAnswer(request: TextRequest, content: String) {
        val roomId = request.roomId.ifBlank { return }
        val now = System.currentTimeMillis()
        pruneTimedMap(injectedAnswerSignatures, now, INJECTED_ANSWER_TTL_MS)
        injectedAnswerSignatures[answerSignature(roomId, content)] = now
    }

    private fun isOwnInjectedAnswer(roomId: String, content: String): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(injectedAnswerSignatures, now, INJECTED_ANSWER_TTL_MS)
        val injectedAt = injectedAnswerSignatures[answerSignature(roomId, content)] ?: return false
        return now - injectedAt <= INJECTED_ANSWER_TTL_MS
    }

    private fun answerSignature(roomId: String, content: String): String =
        "$roomId:${content.length}:${content.hashCode()}"

    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private fun pruneTimedMap(
        map: ConcurrentHashMap<String, Long>,
        now: Long,
        ttlMs: Long
    ) {
        map.entries.removeIf { (_, createdAt) -> now - createdAt > ttlMs }
        if (map.size > 256) map.clear()
    }

    private fun TextRequest.toRuntimeHandoff(userText: String): AgentRuntimeWire.EntryHandoff =
        AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = BREENO_HANDOFF_SOURCE,
            dismissEntrySurfaceOnForegroundOperation = true,
            payload = AgentExternalArchivePayload(
                userText = userText,
                conversationKey = sessionId
                    .ifBlank { roomId }
                    .ifBlank { originalRecordId }
                    .ifBlank { recordId }
                    .ifBlank { runId },
                title = archiveTitle(userText),
                thinkingEnabled = thinkingEnabledOverride,
                adapterPayload = JSONObject()
                    .put("recordId", recordId)
                    .put("originalRecordId", originalRecordId)
                    .put("sessionId", sessionId)
                    .put("roomId", roomId)
                    .apply {
                        thinkingEnabledOverride?.let { put("thinkingEnabledOverride", it) }
                    },
            ).toJson()
        )

    private fun archiveTitle(userText: String): String {
        val firstLine = userText.lineSequence().firstOrNull().orEmpty().trim()
        return if (firstLine.isBlank()) {
            "小布对话"
        } else {
            "小布：${firstLine.take(BREENO_ARCHIVE_TITLE_CHARS)}"
        }
    }

    private fun textRequestPayloadFromHandoffPayload(raw: String): TextRequestPayload {
        val archivePayload = AgentExternalArchivePayload.from(raw)
        if (archivePayload != null) {
            return TextRequestPayload(
                userText = archivePayload.userText,
                adapterPayload = archivePayload.adapterPayload,
            )
        }
        val legacyPayload = JSONObject(raw)
        return TextRequestPayload(
            userText = legacyPayload.optString("userText"),
            adapterPayload = legacyPayload,
        )
    }

    private fun textRequestFromPayload(payload: TextRequestPayload, runId: String): TextRequest {
        val recordId = payload.adapterPayload.optString("recordId")
        return TextRequest(
            runId = runId,
            text = payload.userText,
            imageSnapshot = BreenoRequestImages.Empty,
            recordId = recordId,
            originalRecordId = payload.adapterPayload.optString("originalRecordId").ifBlank { recordId },
            sessionId = payload.adapterPayload.optString("sessionId"),
            roomId = payload.adapterPayload.optString("roomId"),
            thinkingEnabledOverride = payload.adapterPayload.optionalBoolean("thinkingEnabledOverride")
        )
    }

    private fun textRequestFromHandoff(
        handoff: AgentRuntimeWire.EntryHandoff,
        runId: String
    ): TextRequest? {
        if (handoff.source != BREENO_HANDOFF_SOURCE) return null
        return runCatching {
            textRequestFromPayload(
                payload = textRequestPayloadFromHandoffPayload(handoff.payload),
                runId = runId.ifBlank { handoff.id },
            )
        }.getOrNull()
    }

    private fun ackRuntimeResult(logger: ModuleLogger, runId: String) {
        if (runId.isBlank()) return
        handledRuntimeRunIds.add(runId)
        acknowledgeableRuntimeRunIds.add(runId)
        // 新的交付事件可以重新唤醒此前耗尽的有限重试；内部失败回队不会重置预算。
        pendingAckRetryBudget.reset()
        val enqueueResult = pendingRuntimeAcks.enqueue(runId)
        if (enqueueResult == PendingAckState.EnqueueResult.OVERFLOW) {
            logger.warnThrottled("breeno_ack_pending_overflow") {
                "Breeno: 待确认结果已达上限，将从 Runtime 持久队列恢复"
            }
        }
        scheduleRuntimeAckDrain(logger)
    }

    private fun scheduleRuntimeAckDrain(logger: ModuleLogger) {
        if (!pendingRuntimeAcks.hasWork()) return
        if (!pendingAckDrainRunning.compareAndSet(false, true)) return
        try {
            modelExecutor.execute {
                drainRuntimeAcks(logger)
            }
        } catch (_: RejectedExecutionException) {
            pendingAckDrainRunning.set(false)
            logger.warnThrottled("breeno_ack_rejected") {
                "Breeno: Agent 后台队列已满，将重试结果确认"
            }
            scheduleRuntimeAckRetry(logger)
        }
    }

    private fun drainRuntimeAcks(logger: ModuleLogger) {
        var deferRemainingWork = false
        try {
            val context = AgentAppContext.resolve()
            if (context == null) {
                deferRemainingWork = true
                return
            }
            val client = AgentRuntimeClient(context, logger)
            var processed = 0
            while (processed < PENDING_ACK_BATCH_SIZE) {
                val runId = pendingRuntimeAcks.poll() ?: break
                try {
                    if (client.ackResult(runId)) {
                        processed++
                        pendingAckRetryBudget.reset()
                    } else {
                        pendingRuntimeAcks.enqueue(runId)
                        deferRemainingWork = true
                        logger.warnThrottled("breeno_ack_unavailable") {
                            "Breeno: Agent Runtime 暂不可用，将重试结果确认"
                        }
                        break
                    }
                } catch (exception: Exception) {
                    pendingRuntimeAcks.enqueue(runId)
                    deferRemainingWork = true
                    logger.warnThrottled("breeno_ack_failed") {
                        "Breeno: 确认 Agent 结果失败，将重试，type=${exception.safeLogType()}"
                    }
                    break
                }
            }

            if (pendingRuntimeAcks.pendingCount() == 0 && pendingRuntimeAcks.takeRescanRequest()) {
                val completedRuns = runCatching {
                    client.drainCompletedRuns()
                }.getOrElse { throwable ->
                    pendingRuntimeAcks.requestRescan()
                    deferRemainingWork = true
                    logger.warnThrottled("breeno_ack_rescan_failed") {
                        "Breeno: 恢复待确认结果失败，将重试，type=${throwable.safeLogType()}"
                    }
                    emptyList()
                }
                val ackableRuns = completedRuns
                    .asSequence()
                    .filter { it.handoff.source == BREENO_HANDOFF_SOURCE }
                    .map { completedRun ->
                        completedRun.result.runId.ifBlank { completedRun.handoff.id }
                    }
                    .filter { acknowledgeableRuntimeRunIds.contains(it) }
                    .distinct()
                    .take(PENDING_ACK_BATCH_SIZE)
                    .toList()
                ackableRuns.forEach { recoveredRunId ->
                    runCatching {
                        check(client.ackResult(recoveredRunId)) {
                            "Agent Runtime ACK 未提交"
                        }
                    }.onFailure { throwable ->
                        pendingRuntimeAcks.enqueue(recoveredRunId)
                        deferRemainingWork = true
                        logger.warnThrottled("breeno_ack_rescan_item_failed") {
                            "Breeno: 确认恢复结果失败，将重试，type=${throwable.safeLogType()}"
                        }
                    }
                }
                if (ackableRuns.isNotEmpty() && !deferRemainingWork) {
                    pendingRuntimeAcks.clearRescanRequests()
                } else {
                    deferRemainingWork = pendingRuntimeAcks.hasWork()
                }
            }
        } finally {
            pendingAckDrainRunning.set(false)
            if (pendingRuntimeAcks.hasWork()) {
                if (deferRemainingWork || pendingRuntimeAcks.pendingCount() == 0) {
                    scheduleRuntimeAckRetry(logger)
                } else {
                    scheduleRuntimeAckDrain(logger)
                }
            } else {
                pendingAckRetryBudget.reset()
            }
        }
    }

    private fun scheduleRuntimeAckRetry(logger: ModuleLogger) {
        if (!pendingRuntimeAcks.hasWork()) return
        if (!pendingAckRetryScheduled.compareAndSet(false, true)) return
        if (!pendingAckRetryBudget.tryAcquire()) {
            pendingAckRetryScheduled.set(false)
            logger.warnThrottled("breeno_ack_retry_exhausted") {
                "Breeno: Agent 结果确认重试已达上限，等待后续事件继续处理"
            }
            return
        }
        val posted = Handler(Looper.getMainLooper()).postDelayed(
            {
                pendingAckRetryScheduled.set(false)
                scheduleRuntimeAckDrain(logger)
            },
            PENDING_ACK_RETRY_DELAY_MS,
        )
        if (!posted) {
            pendingAckRetryScheduled.set(false)
            logger.warnThrottled("breeno_ack_retry_post_failed") {
                "Breeno: 无法调度 Agent 结果确认重试"
            }
        }
    }

    private fun executeAgentBackground(
        logger: ModuleLogger,
        rejectionKey: String,
        task: () -> Unit,
    ): Boolean =
        try {
            modelExecutor.execute { task() }
            true
        } catch (_: RejectedExecutionException) {
            logger.warnThrottled(rejectionKey) {
                "Breeno: Agent 后台队列已满，已跳过非关键任务"
            }
            false
        }

    private fun extractTextRequest(classLoader: ClassLoader, message: Any?): TextRequest? {
        if (message == null) return null
        val events = HookSupport.invokeNoArgs(message, "getEvents") as? Iterable<*> ?: return null
        val eventList = events.asSequence().take(MAX_PROTOCOL_EVENTS).toList()
        val recordId = invokeString(message, "getRecordId").orEmpty()
        val originalRecordId = invokeString(message, "getOriginalRecordId").orEmpty()
        val sessionId = invokeString(message, "getSessionId").orEmpty()
        val roomId = invokeString(message, "getRoomId").orEmpty()
        var text: String? = null
        var imageSnapshot = BreenoRequestImages.Empty
        var messageThinkingEnabledOverride: Boolean? = null
        for (event in eventList) {
            val header = HookSupport.invokeNoArgs(event ?: continue, "getHeader")
            val namespace = invokeString(header, "getNamespace")
            val name = invokeString(header, "getName")
            val payload = HookSupport.invokeNoArgs(event, "getPayload")
            val capturedImages = BreenoRequestImages.capturePayload(namespace, name, payload)
            if (!capturedImages.isEmpty) {
                imageSnapshot = BreenoRequestImages.merge(imageSnapshot, capturedImages)
            }
            if (namespace == "Nlp" && name == "Text" && text == null) {
                text = invokeString(payload, "getText")
            }
            if (namespace == "Client" && name == "ThinkingModeSwitch") {
                messageThinkingEnabledOverride =
                    thinkingEnabledFromMode(invokeString(payload, "getThinkMode"))
                        ?: messageThinkingEnabledOverride
            }
        }
        if (messageThinkingEnabledOverride != null) {
            lastBreenoThinkingEnabledOverride = messageThinkingEnabledOverride
        }
        val thinkingEnabledOverride = messageThinkingEnabledOverride
            ?: lastBreenoThinkingEnabledOverride
            ?: currentBreenoThinkingEnabledOverride(classLoader)
        val requestText = text ?: return null
        return TextRequest(
            runId = newCompactId(),
            text = requestText,
            imageSnapshot = BreenoRequestImages.merge(
                imageSnapshot,
                cachedImageSnapshotFor(recordId, originalRecordId, sessionId, roomId),
            ),
            recordId = recordId,
            originalRecordId = originalRecordId,
            sessionId = sessionId,
            roomId = roomId,
            thinkingEnabledOverride = thinkingEnabledOverride
        )
    }

    private fun currentBreenoThinkingEnabledOverride(classLoader: ClassLoader): Boolean? =
        singletonInstance(classLoader, AI_CHAT_FAST_MODE_STATE_MANAGER_CLASS)
            ?.let { manager -> invokeCompatible(manager, "h") as? Boolean }
            ?.let { fastModeEnabled -> !fastModeEnabled }

    private fun thinkingEnabledFromMode(mode: String?): Boolean? =
        when (mode?.trim()?.lowercase()) {
            "origin" -> true
            "fast" -> false
            else -> null
        }

    private class ActiveAgentRun(
        private val renderer: BreenoStreamRenderer
    ) {
        private val activationGate = TaskAdmissionGate()

        @Volatile
        var future: Future<*>? = null

        fun awaitActivation(): Boolean = activationGate.awaitAdmission()

        fun activate() {
            activationGate.admit()
        }

        fun cancelBeforeActivation() {
            activationGate.cancel()
        }

        fun cancel(logger: ModuleLogger) {
            activationGate.cancel()
            renderer.cancel()
            future?.let { task ->
                task.cancel(true)
                (task as? Runnable)?.let(modelExecutor::remove)
            }
            logger.debug { "Breeno active Agent run replaced" }
        }
    }

    private class BreenoStreamRenderer(
        private val logger: ModuleLogger,
        private val classLoader: ClassLoader,
        private val request: TextRequest
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val uniqueId = System.currentTimeMillis().toString()
        private val pendingReasoning = StringBuilder()
        private val flushRunnable = Runnable {
            flushScheduled = false
            flushPending(isFinal = false)
        }

        private var flushScheduled = false
        private var created = false
        private var disabled = false
        private var finished = false
        private var streamedReasoningChars = 0
        private var reasoningState: String? = null

        fun onEvent(event: AgentEvent) {
            if (finished || disabled) return
            when (event) {
                is AgentEvent.AssistantBlockDelta ->
                    if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                        if (event.delta.isBlank()) return
                        reasoningState = BREENO_REASONING_STATE
                        pendingReasoning.append(event.delta)
                        scheduleFlush(force = pendingReasoning.length >= BREENO_STREAM_FLUSH_CHARS)
                    }

                is AgentEvent.ToolStarted -> {
                    if (!created && pendingReasoning.isEmpty()) return
                    reasoningState = "正在使用${event.name.toBreenoToolLabel()}"
                    scheduleFlush(force = true)
                }

                is AgentEvent.ToolFinished -> {
                    if (!created && pendingReasoning.isEmpty()) return
                    reasoningState = BREENO_REASONING_STATE
                    scheduleFlush(force = true)
                }

                else -> Unit
            }
        }

        fun finish(
            response: AgentModelClient.ModelResponse.Text,
            fallbackRequest: TextRequest
        ): Boolean {
            if (disabled) return false
            finished = true
            handler.removeCallbacks(flushRunnable)
            flushScheduled = false
            if (!flushPending(isFinal = false)) return false

            val finalRequest = fallbackRequest.copy(text = request.text)
            val missingReasoning = response.reasoningContent.drop(streamedReasoningChars)
            val finalState = if (response.reasoningContent.isNotBlank()) {
                BREENO_REASONING_STATE
            } else {
                reasoningState
            }
            return sendFrame(
                request = finalRequest,
                content = response.content,
                reasoningContent = missingReasoning,
                reasoningState = finalState,
                isFinal = true
            )
        }

        fun cancel() {
            finished = true
            handler.removeCallbacks(flushRunnable)
        }

        private fun scheduleFlush(force: Boolean) {
            if (force) {
                handler.removeCallbacks(flushRunnable)
                flushScheduled = false
                flushPending(isFinal = false)
                return
            }
            if (flushScheduled) return
            flushScheduled = true
            handler.postDelayed(flushRunnable, BREENO_STREAM_FLUSH_DELAY_MS)
        }

        private fun flushPending(isFinal: Boolean): Boolean {
            val reasoningDelta = pendingReasoning.toString()
            if (reasoningDelta.isBlank() && !isFinal) return true
            pendingReasoning.clear()
            return sendFrame(
                request = request,
                content = "",
                reasoningContent = reasoningDelta,
                reasoningState = reasoningState,
                isFinal = isFinal
            )
        }

        private fun sendFrame(
            request: TextRequest,
            content: String,
            reasoningContent: String,
            reasoningState: String?,
            isFinal: Boolean
        ): Boolean {
            if (disabled) return false
            if (content.isBlank() && reasoningContent.isBlank() && reasoningState.isNullOrBlank() && !isFinal) {
                return true
            }
            val roomId = request.roomId.ifBlank { currentRoomId(classLoader) }
            if (roomId.isBlank()) return true
            val frameRequest = request.copy(roomId = roomId)
            return runCatching {
                val wasCreated = created
                rememberInjectedAnswer(frameRequest, content)
                injectStreamTextCard(
                    classLoader = classLoader,
                    request = frameRequest,
                    content = content,
                    reasoningContent = reasoningContent,
                    reasoningState = reasoningState,
                    isFinal = isFinal,
                    type = if (created) 0 else 2,
                    uniqueId = uniqueId
                )
                created = true
                streamedReasoningChars += reasoningContent.length
                if (!wasCreated || isFinal) {
                    logger.debug {
                        "Breeno stream frame injected: type=${if (wasCreated) 0 else 2}, " +
                            "final=$isFinal, content=${content.length}, " +
                            "reasoning=${reasoningContent.length}"
                    }
                }
                true
            }.getOrElse { throwable ->
                disabled = true
                logger.warnThrottled("breeno_stream_injection_failed") {
                    "Breeno: 流式注入失败，回退最终注入，type=${throwable.safeLogType()}"
                }
                false
            }
        }

        private fun String.toBreenoToolLabel(): String =
            when (this) {
                "terminal",
                "run_command" -> "系统工具"
                "open_app" -> "应用工具"
                "screenshot" -> "屏幕工具"
                else -> "工具"
            }
    }

    private fun injectStreamTextCard(
        classLoader: ClassLoader,
        request: TextRequest,
        content: String,
        reasoningContent: String,
        reasoningState: String? = if (reasoningContent.isNotBlank()) BREENO_REASONING_STATE else null,
        isFinal: Boolean = true,
        type: Int = 2,
        uniqueId: String = System.currentTimeMillis().toString()
    ) {
        val directiveClass = Class.forName(DIRECTIVE_CLASS, false, classLoader)
        val headerClass = Class.forName(DIRECTIVE_HEADER_CLASS, false, classLoader)
        val payloadClass = Class.forName(DIRECTIVE_PAYLOAD_CLASS, false, classLoader)
        val streamTextCardClass = Class.forName(STREAM_TEXT_CARD_CLASS, false, classLoader)

        val header = headerClass.getDeclaredConstructor().newInstance()
        invokeCompatible(header, "setId", newCompactId())
        invokeCompatible(header, "setNamespace", "MyAI")
        invokeCompatible(header, "setName", "StreamTextCard")
        invokeCompatible(header, "setNamespaceVersion", "2.0.0")
        invokeCompatible(header, "setVersion", "3.2")

        val payload = streamTextCardClass.getDeclaredConstructor().newInstance()
        invokeCompatible(payload, "setContent", content)
        invokeCompatible(payload, "setRoomId", request.roomId)
        invokeCompatible(payload, "setFinal", isFinal)
        invokeCompatible(payload, "setType", type)
        invokeCompatible(payload, "setQuery", request.text)
        invokeCompatible(payload, "setHtml", false)
        invokeCompatible(payload, "setCharPerSec", 50)
        if (reasoningContent.isNotBlank()) {
            invokeCompatible(payload, "setReasoningContent", reasoningContent)
        }
        if (!reasoningState.isNullOrBlank()) {
            invokeCompatible(payload, "setReasoningState", reasoningState)
        }

        val directive = directiveClass.getDeclaredConstructor().newInstance()
        invokeCompatible(directive, "setHeader", header)
        val setPayload = directiveClass.getDeclaredMethod("setPayload", payloadClass).apply {
            isAccessible = true
        }
        setPayload.invoke(directive, payload)

        val directives = arrayListOf(directive)
        val origin = buildInjectedDownstreamJson(
            header = header,
            content = content,
            reasoningContent = reasoningContent,
            reasoningState = reasoningState,
            request = request,
            isFinal = isFinal,
            type = type,
            uniqueId = uniqueId
        )
        val agent = getAgent(classLoader)
            ?: error("HeytapSpeechEngine.mAgent is null")
        invokeCompatible(agent, "j", directives, origin)
    }

    private fun getAgent(classLoader: ClassLoader): Any? {
        val engineClass = Class.forName(HEYTAP_SPEECH_ENGINE_CLASS, false, classLoader)
        val engine = getHeytapSpeechEngineInstance(engineClass)
        if (engine == null) return null
        return runCatching { invokeCompatible(engine, "getMAgent") }.getOrNull()
            ?: runCatching { invokeCompatible(engine, "getAgent") }.getOrNull()
            ?: runCatching {
                engine.javaClass.getDeclaredField("mAgent").apply { isAccessible = true }.get(engine)
            }.getOrNull()
    }

    private fun getHeytapSpeechEngineInstance(engineClass: Class<*>): Any? {
        val staticInstance = runCatching {
            engineClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                .takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                ?.invoke(null)
        }.getOrNull()
        if (staticInstance != null) return staticInstance

        val companion = listOf("Companion", "INSTANCE")
            .firstNotNullOfOrNull { fieldName ->
                runCatching {
                    engineClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
                }.getOrNull()
            }
            ?: engineClass.declaredFields.firstNotNullOfOrNull { field ->
                runCatching {
                    if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) return@runCatching null
                    field.isAccessible = true
                    field.get(null)?.takeIf { it.javaClass.name.contains("HeytapSpeechEngine") }
                }.getOrNull()
            }
        return companion?.let { invokeCompatible(it, "getInstance") }
    }

    private fun buildInjectedDownstreamJson(
        header: Any,
        content: String,
        reasoningContent: String,
        reasoningState: String?,
        request: TextRequest,
        isFinal: Boolean,
        type: Int,
        uniqueId: String
    ): String {
        val directive = buildStreamTextCardJson(
            headerId = invokeString(header, "getId") ?: newCompactId(),
            content = content,
            reasoningContent = reasoningContent,
            reasoningState = reasoningState,
            request = request,
            isFinal = isFinal,
            type = type
        )
        return JSONObject()
            .put("version", "3.0")
            .put("originalRecordId", request.originalRecordId.ifBlank { request.recordId })
            .put("recordId", request.recordId)
            .put("sessionId", request.sessionId)
            .put("roomId", request.roomId)
            .put("uniqueId", uniqueId)
            .put("sequenceId", 0)
            .put("extend", JSONObject().put(INJECTED_MARKER_KEY, "true"))
            .put("directives", JSONArray().put(directive))
            .toString()
    }

    private fun persistChatHistory(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
        onFinished: (Boolean) -> Unit = {},
    ) {
        runCatching {
            val roomId = request.roomId.ifBlank { currentRoomId(classLoader) }
            if (roomId.isBlank()) {
                logger.warnThrottled("breeno_history_room_missing") {
                    "Breeno: 跳过历史写入，roomId 为空"
                }
                onFinished(false)
                return
            }
            val recordId = request.recordId
                .ifBlank { request.originalRecordId }
                .ifBlank { newCompactId() }
            val agentName = currentAgentName(classLoader).ifBlank { BREENO_DEFAULT_AGENT_NAME }
            val repository = singletonInstance(classLoader, AI_CHAT_REPOSITORY_CLASS)
                ?: error("AIChatRepository.INSTANCE is null")
            val queryRecord = newInsertRecord(
                classLoader = classLoader,
                recordId = recordId,
                content = request.text,
                type = RECORD_TYPE_QUERY,
                payload = request.queryPayload,
                clientResult = request.queryClientResult,
            )
            val answerRecord = newInsertRecord(
                classLoader = classLoader,
                recordId = recordId,
                content = response.content,
                type = RECORD_TYPE_ANSWER,
                payload = buildHistoryPayloadJson(
                    response = response,
                    request = request.copy(recordId = recordId, roomId = roomId)
                ),
                clientResult = null,
            )
            insertHistoryRecord(
                classLoader = classLoader,
                repository = repository,
                roomId = roomId,
                agentName = agentName,
                record = queryRecord,
                logger = logger,
                label = "query",
            ) { queryResult ->
                runCatching {
                    insertHistoryRecord(
                        classLoader = classLoader,
                        repository = repository,
                        roomId = roomId,
                        agentName = agentName,
                        record = answerRecord,
                        logger = logger,
                        label = "answer",
                    ) { answerResult ->
                        if (answerResult.saved && answerResult.uniqueId != null) {
                            updateInjectedAnswerUniqueId(
                                classLoader = classLoader,
                                roomId = roomId,
                                content = response.content,
                                uniqueId = answerResult.uniqueId,
                            )
                        }
                        val historySaved = queryResult.saved && answerResult.saved
                        if (historySaved) {
                            logger.debug { "Breeno history persisted" }
                        } else {
                            logger.warnThrottled("breeno_history_persist_rejected") {
                                "Breeno: 服务端未完整保存历史记录"
                            }
                        }
                        onFinished(historySaved)
                    }
                }.onFailure { throwable ->
                    logger.warnThrottled("breeno_history_answer_dispatch_failed") {
                        "Breeno: 提交回答历史失败，type=${throwable.safeLogType()}"
                    }
                    onFinished(false)
                }
            }
            logger.debug { "Breeno history persist requested" }
        }.onFailure { throwable ->
            logger.warnThrottled("breeno_history_persist_failed") {
                "Breeno: 写入历史记录失败，type=${throwable.safeLogType()}"
            }
            onFinished(false)
        }
    }

    private fun persistHistoryAndAck(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
        runId: String,
    ) {
        if (runId.isBlank()) {
            persistChatHistory(logger, classLoader, request, response)
            return
        }
        if (!historyPersistenceInFlight.add(runId)) return
        persistChatHistory(logger, classLoader, request, response) { saved ->
            historyPersistenceInFlight.remove(runId)
            if (saved) {
                ackRuntimeResult(logger, runId)
            } else {
                logger.warnThrottled("breeno_history_pending_${runId.hashCode()}") {
                    "Breeno: 历史保存未完成，保留 Runtime 结果以便恢复"
                }
            }
        }
    }

    private fun insertHistoryRecord(
        classLoader: ClassLoader,
        repository: Any,
        roomId: String,
        agentName: String,
        record: Any,
        logger: ModuleLogger,
        label: String,
        onResult: (HistoryInsertResult) -> Unit,
    ) {
        invokeCompatible(
            repository,
            "r",
            roomId,
            agentName,
            record,
            newHistoryCallback(classLoader, logger, label, onResult),
        )
    }

    private fun buildHistoryPayloadJson(
        response: AgentModelClient.ModelResponse.Text,
        request: TextRequest
    ): String =
        JSONObject()
            .put(
                "uiDirectives",
                JSONArray().put(
                    buildStreamTextCardJson(
                        headerId = newCompactId(),
                        content = response.content,
                        reasoningContent = response.reasoningContent,
                        reasoningState = if (response.reasoningContent.isNotBlank()) {
                            BREENO_REASONING_STATE
                        } else {
                            null
                        },
                        request = request,
                        isFinal = true,
                        type = 2
                    )
                )
            )
            .toString()

    private fun buildStreamTextCardJson(
        headerId: String,
        content: String,
        reasoningContent: String,
        reasoningState: String?,
        request: TextRequest,
        isFinal: Boolean,
        type: Int
    ): JSONObject =
        JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("id", headerId)
                    .put("namespace", "MyAI")
                    .put("name", "StreamTextCard")
                    .put("namespaceVersion", "2.0.0")
                    .put("version", "3.2")
            )
            .put(
                "payload",
                JSONObject()
                    .put("content", content)
                    .put("roomId", request.roomId)
                    .put("isFinal", isFinal)
                    .put("type", type)
                    .put("query", request.text)
                    .put("isHtml", false)
                    .put("charPerSec", 50)
                    .apply {
                        if (reasoningContent.isNotBlank()) {
                            put("reasoningContent", reasoningContent)
                        }
                        if (!reasoningState.isNullOrBlank()) {
                            put("reasoningState", reasoningState)
                        }
                    }
            )

    private fun newInsertRecord(
        classLoader: ClassLoader,
        recordId: String,
        content: String,
        type: String,
        payload: String?,
        clientResult: String?,
    ): Any {
        val insertRecordClass = Class.forName(INSERT_RECORD_CLASS, false, classLoader)
        return insertRecordClass.getDeclaredConstructor().newInstance().also { record ->
            invokeCompatible(record, "setRecordId", recordId)
            invokeCompatible(record, "setOriginRecordId", recordId)
            invokeCompatible(record, "setContent", content)
            invokeCompatible(record, "setType", type)
            invokeCompatible(record, "setCancelFlag", false)
            if (payload != null) {
                invokeCompatible(record, "setPayload", payload)
            }
            if (clientResult != null) {
                invokeCompatible(record, "setClientResult", clientResult)
            }
        }
    }

    private fun currentRoomId(classLoader: ClassLoader): String =
        singletonInstance(classLoader, AI_CHAT_ROOM_ID_MANAGER_CLASS)
            ?.let { manager -> invokeCompatible(manager, "x") as? String }
            .orEmpty()

    private fun currentAgentName(classLoader: ClassLoader): String =
        singletonInstance(classLoader, AI_CHAT_ROOM_ID_MANAGER_CLASS)
            ?.let { manager -> invokeCompatible(manager, "v") as? String }
            .orEmpty()

    private fun singletonInstance(classLoader: ClassLoader, className: String): Any? =
        runCatching {
            Class.forName(className, false, classLoader)
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull()

    private fun newHistoryCallback(
        classLoader: ClassLoader,
        logger: ModuleLogger,
        label: String,
        onResult: (HistoryInsertResult) -> Unit,
    ): Any {
        val functionClass = Class.forName(KOTLIN_FUNCTION1_CLASS, false, classLoader)
        val unit = Class.forName(KOTLIN_UNIT_CLASS, false, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    val result = args?.firstOrNull()
                    val insertResult = parseHistoryInsertResult(result)
                    logger.debug {
                        "Breeno history callback[$label]: " +
                            "saved=${insertResult.saved}, " +
                            "resultType=${result?.javaClass?.simpleName.toSafeLogToken()}"
                    }
                    onResult(insertResult)
                    unit
                }
                "toString" -> "BreenoHistoryCallback($label)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    private fun parseHistoryInsertResult(result: Any?): HistoryInsertResult =
        runCatching {
            val response = result?.let { invokeCompatible(it, "a") }
                ?: return@runCatching HistoryInsertResult.FAILED
            val saved = invokeCompatible(response, "isSuccess") as? Boolean ?: false
            HistoryInsertResult(
                saved = saved,
                uniqueId = if (saved) invokeCompatible(response, "getData") as? String else null,
            )
        }.getOrDefault(HistoryInsertResult.FAILED)

    private fun updateInjectedAnswerUniqueId(
        classLoader: ClassLoader,
        roomId: String,
        content: String,
        uniqueId: String,
    ) {
        if (uniqueId.isBlank()) return
        val dataCenter = singletonInstance(classLoader, AI_CHAT_DATA_CENTER_CLASS) ?: return
        val bean = invokeCompatible(dataCenter, "q0", roomId) ?: return
        if (invokeInt(bean, "getChatType") != AI_CHAT_TYPE_ANSWER) return
        if (invokeString(bean, "getContent").orEmpty() != content) return
        invokeCompatible(bean, "setUniqueId", uniqueId)
    }

    private fun serializeForBreenoHistory(classLoader: ClassLoader, value: Any?): String? {
        if (value == null) return null
        return runCatching {
            Class.forName(JSON_UTIL_CLASS, false, classLoader)
                .getDeclaredMethod("f", Any::class.java)
                .apply { isAccessible = true }
                .invoke(null, value) as? String
        }.getOrNull()
    }

    private fun summarizeInboundMessage(content: String?): String {
        if (content.isNullOrBlank()) return "content=blank"
        val json = JSONObject(content)
        val directives = json.optJSONArray("directives")
        return "contentChars=${content.length}, " +
            "directives=${summarizeDirectives(directives)}"
    }

    private fun summarizeDirectives(directives: JSONArray?): String {
        if (directives == null) return "[]"
        val names = (0 until directives.length()).map { index ->
            val directive = directives.optJSONObject(index)
            val header = directive?.optJSONObject("header")
            val payload = directive?.optJSONObject("payload")
            val namespace = header?.optString("namespace").orEmpty()
            val name = header?.optString("name").orEmpty()
            val finalMark = if (payload?.has("isFinal") == true) {
                ", isFinal=${payload.optBoolean("isFinal")}"
            } else {
                ""
            }
            "${protocolEventLabel(namespace, name)}$finalMark"
        }
        return names.joinToString(prefix = "[", postfix = "]")
    }

    private fun summarizeDmParameter(parameter: Any?): String {
        if (parameter == null) return "parameter=null"
        val data = invokeString(parameter, "getData")
        return "requestType=${invokeString(parameter, "getRequestType").toSafeLogToken()}, " +
            "dataChars=${data?.length ?: 0}, " +
            "hasRoute=${!invokeString(parameter, "getRoute").isNullOrBlank()}"
    }

    private fun cacheCdmImages(logger: ModuleLogger, parameter: Any?) {
        if (parameter == null) return
        val data = invokeString(parameter, "getData")
        val snapshot = BreenoRequestImages.captureText(data, "cdm.data")
        if (snapshot.isEmpty) return
        val keys = listOfNotNull(
            invokeString(parameter, "getRecordId"),
            invokeString(parameter, "getCurrentRecordId"),
            invokeString(parameter, "getSessionId")
        ).filter { it.isNotBlank() }.distinct()
        when (cdmImageCache.store(keys, snapshot)) {
            BreenoRequestImages.SnapshotCache.StoreResult.STORED -> logger.debug {
                "Breeno request image references cached: keys=${keys.size}, " +
                    "inputs=${snapshot.inputCount}"
            }
            BreenoRequestImages.SnapshotCache.StoreResult.STORED_FAILURE ->
                logger.warnThrottled("breeno_image_cache_size_limit") {
                    "Breeno: 图片引用缓存数据超过上限，已保存显式失败状态"
                }
            BreenoRequestImages.SnapshotCache.StoreResult.EMPTY -> Unit
            BreenoRequestImages.SnapshotCache.StoreResult.TOO_MANY_ALIASES ->
                logger.warnThrottled("breeno_image_cache_alias_limit") {
                    "Breeno: 图片引用缓存别名超过上限，已拒绝缓存"
                }
            BreenoRequestImages.SnapshotCache.StoreResult.TOO_LARGE ->
                logger.warnThrottled("breeno_image_cache_size_limit") {
                    "Breeno: 图片引用缓存容量不足，无法保存失败状态"
                }
        }
    }

    private fun cachedImageSnapshotFor(vararg keys: String): BreenoRequestImages.Snapshot =
        cdmImageCache.consume(keys.asList())

    private fun invokeString(target: Any?, methodName: String): String? =
        HookSupport.invokeNoArgs(target ?: return null, methodName) as? String

    private fun invokeInt(target: Any?, methodName: String): Int? =
        (HookSupport.invokeNoArgs(target ?: return null, methodName) as? Number)?.toInt()

    private fun protocolEventLabel(namespace: String?, name: String?): String =
        "${namespace.toSafeLogToken()}.${name.toSafeLogToken()}"

    private fun hasClientLocalData(bean: Any?, key: String): Boolean =
        runCatching {
            bean != null && invokeCompatible(bean, "getClientLocalData", key) != null
        }.getOrDefault(false)

    private fun String.removeExperimentalPrefixOrNull(): String? =
        when {
            startsWith(EXPERIMENTAL_PREFIX) -> removePrefix(EXPERIMENTAL_PREFIX).trim()
            startsWith(EXPERIMENTAL_ADB_PREFIX) -> removePrefix(EXPERIMENTAL_ADB_PREFIX).trim()
            else -> null
        }

    private fun invokeCompatible(target: Any, methodName: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(target.javaClass, methodName, args)
            ?: error("Method not found: ${target.javaClass.name}.$methodName/${args.size}")
        return method.invoke(target, *args)
    }

    private fun findCompatibleMethod(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName &&
                    method.parameterTypes.size == args.size &&
                    method.parameterTypes.zip(args).all { (type, arg) ->
                        arg == null || type.wrapPrimitive().isAssignableFrom(arg.javaClass)
                    }
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.wrapPrimitive(): Class<*> =
        when (this) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            else -> this
        }

    private fun newCompactId(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun AgentModelClient.ModelResponse.summary(): String =
        when (this) {
            is AgentModelClient.ModelResponse.Text ->
                "text(content=${content.length}, reasoning=${reasoningContent.length})"
        }

    private data class TextRequest(
        val runId: String,
        val text: String,
        val imageSnapshot: BreenoRequestImages.Snapshot,
        val recordId: String,
        val originalRecordId: String,
        val sessionId: String,
        val roomId: String,
        val thinkingEnabledOverride: Boolean? = null,
        val queryPayload: String? = null,
        val queryClientResult: String? = null,
    )

    private data class TextRequestPayload(
        val userText: String,
        val adapterPayload: JSONObject,
    )

    private data class HistoryInsertResult(
        val saved: Boolean,
        val uniqueId: String?,
    ) {
        companion object {
            val FAILED = HistoryInsertResult(saved = false, uniqueId = null)
        }
    }

}

internal class PendingAckState(
    private val capacity: Int,
    private val rescanAttempts: Int,
) {
    init {
        require(capacity > 0)
        require(rescanAttempts > 0)
    }

    enum class EnqueueResult {
        ADDED,
        DUPLICATE,
        OVERFLOW,
    }

    private val pendingRunIds = LinkedHashSet<String>()
    private var remainingRescanAttempts = 0

    @Synchronized
    fun enqueue(runId: String): EnqueueResult {
        require(runId.isNotBlank())
        if (runId in pendingRunIds) return EnqueueResult.DUPLICATE
        if (pendingRunIds.size >= capacity) {
            remainingRescanAttempts = maxOf(remainingRescanAttempts, rescanAttempts)
            return EnqueueResult.OVERFLOW
        }
        pendingRunIds += runId
        return EnqueueResult.ADDED
    }

    @Synchronized
    fun poll(): String? {
        val iterator = pendingRunIds.iterator()
        if (!iterator.hasNext()) return null
        return iterator.next().also { iterator.remove() }
    }

    @Synchronized
    fun requestRescan() {
        remainingRescanAttempts = maxOf(remainingRescanAttempts, rescanAttempts)
    }

    @Synchronized
    fun takeRescanRequest(): Boolean {
        if (remainingRescanAttempts == 0) return false
        remainingRescanAttempts--
        return true
    }

    @Synchronized
    fun clearRescanRequests() {
        remainingRescanAttempts = 0
    }

    @Synchronized
    fun pendingCount(): Int = pendingRunIds.size

    @Synchronized
    fun hasWork(): Boolean = pendingRunIds.isNotEmpty() || remainingRescanAttempts > 0
}

internal class BoundedRunIdSet(
    private val capacity: Int,
    private val ttlMillis: Long = Long.MAX_VALUE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0)
        require(ttlMillis > 0L)
    }

    private val runIds = LinkedHashMap<String, Long>(capacity, 0.75f, true)

    @Synchronized
    fun add(runId: String): Boolean {
        require(runId.isNotBlank())
        val now = nowMillis()
        purgeExpired(now)
        if (runIds[runId] != null) {
            runIds[runId] = now
            return false
        }
        if (runIds.size >= capacity) {
            val iterator = runIds.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        runIds[runId] = now
        return true
    }

    @Synchronized
    fun contains(runId: String): Boolean {
        purgeExpired(nowMillis())
        return runIds.containsKey(runId)
    }

    @Synchronized
    fun remove(runId: String) {
        runIds.remove(runId)
    }

    @Synchronized
    fun size(): Int {
        purgeExpired(nowMillis())
        return runIds.size
    }

    private fun purgeExpired(now: Long) {
        val iterator = runIds.entries.iterator()
        while (iterator.hasNext()) {
            val recordedAt = iterator.next().value
            if (now >= recordedAt && now - recordedAt >= ttlMillis) {
                iterator.remove()
            }
        }
    }
}

internal fun breenoRequestDedupKey(anchor: String, prompt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateFramed(anchor)
    digest.updateFramed(prompt)
    val bytes = digest.digest()
    val chars = CharArray(bytes.size * 2)
    val hex = "0123456789abcdef"
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = hex[value ushr 4]
        chars[index * 2 + 1] = hex[value and 0x0f]
    }
    return chars.concatToString()
}

internal class BoundedRetryBudget(private val maxAttempts: Int) {
    private val attempts = AtomicInteger(0)

    init {
        require(maxAttempts > 0)
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = attempts.get()
            if (current >= maxAttempts) return false
            if (attempts.compareAndSet(current, current + 1)) return true
        }
    }

    fun reset() {
        attempts.set(0)
    }
}

private fun MessageDigest.updateFramed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

internal class TaskAdmissionGate {
    private val latch = CountDownLatch(1)
    private val state = AtomicInteger(WAITING)

    fun admit() {
        if (state.compareAndSet(WAITING, ADMITTED)) {
            latch.countDown()
        }
    }

    fun cancel() {
        if (state.compareAndSet(WAITING, CANCELLED)) {
            latch.countDown()
        }
    }

    fun awaitAdmission(): Boolean =
        try {
            latch.await()
            state.get() == ADMITTED
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private companion object {
        const val WAITING = 0
        const val ADMITTED = 1
        const val CANCELLED = 2
    }
}
