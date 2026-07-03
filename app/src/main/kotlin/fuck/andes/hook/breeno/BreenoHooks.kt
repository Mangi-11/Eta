package fuck.andes.hook.breeno

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger

import android.os.Handler
import android.os.Looper
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val MAX_TEXT_CHARS = 240
    private const val RAW_LOG_CHUNK_CHARS = 3_200
    private val modelExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "FuckAndes-AgentBridge").apply { isDaemon = true }
    }
    private val cdmImageCache = ConcurrentHashMap<String, List<AgentModelClient.ModelImage>>()
    private val deliveredRunIds = ConcurrentHashMap.newKeySet<String>()
    private val startedAgentRequests = ConcurrentHashMap<String, Long>()
    private val claimedAgentRooms = ConcurrentHashMap<String, Long>()
    private val injectedAnswerSignatures = ConcurrentHashMap<String, Long>()
    private val pendingDrainRunning = AtomicBoolean(false)
    private val activeAgentRun = AtomicReference<ActiveAgentRun?>()
    @Volatile
    private var lastBreenoThinkingEnabledOverride: Boolean? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        hookOutboundMessage(module, logger, classLoader)
        hookInboundMessage(module, logger, classLoader)
        hookCdmTextRequest(module, logger, classLoader)
        hookAIChatDataCenter(module, logger, classLoader)
        schedulePendingResultDrains(logger, classLoader)
        logger.info("Breeno: 小布观测 Hook 已安装")
    }

    private fun hookOutboundMessage(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val managerClass = HookSupport.findClassOrNull(classLoader, MESSAGE_QUEUE_MANAGER_CLASS)
        val messageClass = HookSupport.findClassOrNull(classLoader, MESSAGE_CLASS)
        if (managerClass == null || messageClass == null) {
            logger.warn("Breeno: 未找到 MessageQueueManager/Message，跳过出站观测")
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
            logger.warn("Breeno: 未找到 MessageQueueManager.c(Message,boolean,Integer,boolean)")
            return
        }

        HookSupport.hookMethod(module, logger, method, "Breeno MessageQueueManager.c") { chain ->
            val message = chain.args.getOrNull(0)
            if (maybeHandleCustomModelRequest(logger, classLoader, message)) {
                return@hookMethod null
            }
            runCatching {
                logger.info("Breeno outbound: ${summarizeOutboundMessage(message)}")
            }.onFailure { throwable ->
                logger.warnThrottled(
                    "breeno_outbound_log_failed",
                    "Breeno: 记录出站消息失败: ${throwable.message}"
                )
            }
            chain.proceed()
        }
    }

    private fun hookInboundMessage(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val processorClass = HookSupport.findClassOrNull(classLoader, MESSAGE_PROCESSOR_CLASS)
        if (processorClass == null) {
            logger.warn("Breeno: 未找到 MessageProcessor，跳过入站观测")
            return
        }
        val method = HookSupport.findMethod(
            processorClass,
            "B",
            String::class.java,
            String::class.java
        )
        if (method == null) {
            logger.warn("Breeno: 未找到 MessageProcessor.B(String,String)")
            return
        }

        HookSupport.hookMethod(module, logger, method, "Breeno MessageProcessor.B") { chain ->
            val messageId = chain.args.getOrNull(0) as? String
            val content = chain.args.getOrNull(1) as? String
            val filteredContent = filterClaimedNativeDirectives(logger, messageId, content)
            runCatching {
                if (filteredContent == null && content != null) {
                    logger.info("Breeno inbound suppressed: messageId=$messageId")
                } else {
                    logger.info("Breeno inbound: ${summarizeInboundMessage(messageId, filteredContent ?: content)}")
                    logRelevantDirectivePayloads(logger, messageId, filteredContent ?: content)
                }
            }.onFailure { throwable ->
                logger.warnThrottled(
                    "breeno_inbound_log_failed",
                    "Breeno: 记录入站消息失败: ${throwable.message}"
                )
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
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val cdmNodeClass = HookSupport.findClassOrNull(classLoader, CDM_NODE_CLASS)
        val dmParameterClass = HookSupport.findClassOrNull(classLoader, DM_PARAMETER_CLASS)
        if (cdmNodeClass == null || dmParameterClass == null) {
            logger.warn("Breeno: 未找到 CdmNode/DmParameter，跳过文本请求观测")
            return
        }
        val method = HookSupport.findMethod(cdmNodeClass, "o", dmParameterClass)
        if (method == null) {
            logger.warn("Breeno: 未找到 CdmNode.o(DmParameter)")
            return
        }

        HookSupport.hookMethod(module, logger, method, "Breeno CdmNode.o") { chain ->
            val parameter = chain.args.getOrNull(0)
            runCatching {
                val requestType = invokeString(parameter, "getRequestType")
                if (requestType == "text") {
                    logger.info("Breeno text request: ${summarizeDmParameter(parameter)}")
                }
                cacheCdmImages(logger, parameter)
            }.onFailure { throwable ->
                logger.warnThrottled(
                    "breeno_cdm_log_failed",
                    "Breeno: 记录 CdmNode 文本请求失败: ${throwable.message}"
                )
            }
            chain.proceed()
        }
    }

    private fun hookAIChatDataCenter(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val dataCenterClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_DATA_CENTER_CLASS)
        val viewBeanClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_VIEW_BEAN_CLASS)
        if (dataCenterClass == null || viewBeanClass == null) {
            logger.warn("Breeno: 未找到 AIChatDataCenter/AIChatViewBean，跳过对话 UI 接管")
            return
        }
        val method = HookSupport.findMethod(dataCenterClass, "r", viewBeanClass)
        if (method == null) {
            logger.warn("Breeno: 未找到 AIChatDataCenter.r(AIChatViewBean)")
            return
        }

        HookSupport.hookMethod(module, logger, method, "Breeno AIChatDataCenter.r") { chain ->
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
            images = cachedImagesFor(recordId, roomId),
            recordId = stableRecordId,
            originalRecordId = stableRecordId,
            sessionId = "",
            roomId = roomId,
            thinkingEnabledOverride = currentBreenoThinkingEnabledOverride(classLoader)
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
        logger.info(
            "Breeno native AIChat answer blocked: " +
                "roomId=$roomId, recordId=${invokeString(bean, "getRecordId").orEmpty()}, " +
                "content=\"${content.compact()}\""
        )
        return true
    }

    private fun filterClaimedNativeDirectives(
        logger: ModuleLogger,
        messageId: String?,
        content: String?
    ): String? {
        if (content.isNullOrBlank()) return content
        return runCatching {
            val json = JSONObject(content)
            val roomId = json.optString("roomId")
            if (roomId.isBlank() || !isClaimedAgentRoom(roomId, NATIVE_DIRECTIVE_SUPPRESS_TTL_MS)) {
                return@runCatching content
            }
            if (json.optJSONObject("extend")?.optString(INJECTED_MARKER_KEY) == "true") {
                return@runCatching content
            }
            val directives = json.optJSONArray("directives") ?: return@runCatching content
            val kept = JSONArray()
            val removed = mutableListOf<String>()
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
                    removed += "$namespace.$name"
                } else {
                    kept.put(directive)
                }
            }
            if (removed.isEmpty()) return@runCatching content
            logger.info(
                "Breeno native directives suppressed: " +
                    "messageId=$messageId, roomId=$roomId, removed=$removed, kept=${kept.length()}"
            )
            if (kept.length() == 0) {
                null
            } else {
                json.put("directives", kept).toString()
            }
        }.getOrElse { throwable ->
            logger.warnThrottled(
                "breeno_native_directive_filter_failed",
                "Breeno: 过滤原生指令失败: ${throwable.message ?: throwable.javaClass.simpleName}"
            )
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
                val payload = HookSupport.invokeNoArgs(event, "getPayload")
                val namespace = invokeString(header, "getNamespace")
                val name = invokeString(header, "getName")
                val payloadSummary = summarizePayload(payload)
                "$namespace.$name$payloadSummary"
            }
            ?.joinToString(prefix = "[", postfix = "]")
            ?: "[]"
        return "messageId=${invokeString(message, "getMessageId")}, " +
            "recordId=${invokeString(message, "getRecordId")}, " +
            "originalRecordId=${invokeString(message, "getOriginalRecordId")}, " +
            "sessionId=${invokeString(message, "getSessionId")}, " +
            "roomId=${invokeString(message, "getRoomId")}, " +
            "seq=${HookSupport.invokeNoArgs(message, "getSequenceId")}, " +
            "events=$eventSummaries"
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
        if (!markAgentRequestStarted(request, prompt)) {
            logger.info(
                "Breeno custom model duplicate skipped: source=$logSource, " +
                    "text=\"${prompt.compact()}\", recordId=${request.recordId}, roomId=${request.roomId}"
            )
            return true
        }
        return runCatching {
            val renderRequest = request.copy(text = prompt)
            val streamRenderer = BreenoStreamRenderer(
                logger = logger,
                classLoader = classLoader,
                request = renderRequest
            )
            val runState = ActiveAgentRun(
                runId = request.runId,
                renderer = streamRenderer
            )
            activeAgentRun.getAndSet(runState)?.cancel(logger, replacementRunId = request.runId)
            val future = modelExecutor.submit {
                if (activeAgentRun.get() !== runState) return@submit
                var ackRunId: String? = null
                val modelResponse = runCatching {
                    val baseConfig = AgentModelClient.loadConfig()
                    val config = request.thinkingEnabledOverride
                        ?.let { baseConfig.copy(thinkingEnabled = it) }
                        ?: baseConfig
                    if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) {
                        error("请先在 FuckAndes 设置中启用“小布自定义模型”")
                    }
                    val context = AgentAppContext.resolve()
                        ?: error("无法获取小布进程 Context")
                    val result = AgentRuntimeClient(context, logger).run(
                        request = AgentRuntimeWire.RunRequest(
                            runId = request.runId,
                            prompt = prompt,
                            config = config,
                            images = request.images,
                            handoff = request.toRuntimeHandoff(prompt)
                        )
                    ) { event ->
                        if (activeAgentRun.get() === runState) {
                            logger.info("Agent event: ${event.toLogLine()}")
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
                        ackRunId?.let { runId -> ackRuntimeResult(logger, runId) }
                        logger.info(
                            "Breeno obsolete Agent result skipped: " +
                                "runId=${request.runId}, replacement=${activeAgentRun.get()?.runId.orEmpty()}"
                        )
                        return@post
                    }
                    val deliveredRunId = ackRunId ?: request.runId
                    var deliveryMarked = false
                    runCatching {
                        if (!markRunDelivered(deliveredRunId)) {
                            streamRenderer.cancel()
                            ackRunId?.let { runId -> ackRuntimeResult(logger, runId) }
                            logger.info("Breeno Agent result already delivered: runId=$deliveredRunId")
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
                        persistChatHistory(logger, classLoader, injectedRequest, modelResponse)
                        ackRunId?.let { runId ->
                            ackRuntimeResult(logger, runId)
                        }
                        logger.info(
                            "Breeno custom model injected: ${modelResponse.summary()}, " +
                                "runId=${request.runId}, " +
                                "recordId=${request.recordId}, sessionId=${request.sessionId}, " +
                                "roomId=${request.roomId}"
                        )
                    }.onFailure { throwable ->
                        if (deliveryMarked) deliveredRunIds.remove(deliveredRunId)
                        logger.error("Breeno: 注入自定义模型响应失败", throwable)
                    }
                    activeAgentRun.compareAndSet(runState, null)
                }
            }
            runState.future = future
            logger.info(
                "Breeno custom model takeover: source=$logSource, text=\"${prompt.compact()}\", " +
                    "images=${request.images.size}, thinking=${request.thinkingEnabledOverride}, " +
                    "recordId=${request.recordId}, " +
                    "originalRecordId=${request.originalRecordId}, roomId=${request.roomId}"
            )
            true
        }.getOrElse { throwable ->
            logger.error("Breeno: 接管自定义模型请求失败，放行原请求", throwable)
            false
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
        modelExecutor.execute {
            val completedRuns = runCatching {
                AgentRuntimeClient(context, logger).drainCompletedRuns()
            }.getOrElse { throwable ->
                logger.warn("Breeno: 拉取 Agent 未交付结果失败: ${throwable.message ?: throwable.javaClass.simpleName}")
                emptyList()
            }
            val breenoRuns = completedRuns.filter { it.handoff.source == BREENO_HANDOFF_SOURCE }
            if (breenoRuns.isEmpty()) {
                pendingDrainRunning.set(false)
                return@execute
            }
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

    private fun injectCompletedRun(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        completedRun: AgentRuntimeWire.CompletedRun
    ) {
        val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
        val request = textRequestFromHandoff(completedRun.handoff, runId)
        if (request == null) {
            logger.warn(
                "Breeno: 忽略非小布 Agent 未交付结果: " +
                    "runId=$runId, source=${completedRun.handoff.source}"
            )
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
                ackRuntimeResult(logger, runId)
                logger.info("Breeno pending Agent result already delivered: runId=$runId")
                return@runCatching
            }
            deliveryMarked = true
            rememberInjectedAnswer(request, content)
            injectModelResponse(
                classLoader,
                request,
                response
            )
            persistChatHistory(
                logger = logger,
                classLoader = classLoader,
                request = request,
                response = response
            )
            ackRuntimeResult(logger, runId)
            logger.info(
                "Breeno pending Agent result injected: runId=$runId, " +
                    "recordId=${request.recordId}, sessionId=${request.sessionId}, roomId=${request.roomId}"
            )
        }.onFailure { throwable ->
            if (deliveryMarked) deliveredRunIds.remove(runId)
            logger.error("Breeno: 注入未交付 Agent 结果失败", throwable)
        }
    }

    private fun markRunDelivered(runId: String): Boolean =
        runId.isBlank() || deliveredRunIds.add(runId)

    private fun markAgentRequestStarted(request: TextRequest, prompt: String): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(startedAgentRequests, now, AGENT_REQUEST_DEDUP_WINDOW_MS)
        val key = agentRequestKey(request, prompt)
        val previous = startedAgentRequests.putIfAbsent(key, now)
        return previous == null || now - previous > AGENT_REQUEST_DEDUP_WINDOW_MS
    }

    private fun agentRequestKey(request: TextRequest, prompt: String): String {
        val anchor = request.roomId
            .ifBlank { request.recordId }
            .ifBlank { request.originalRecordId }
            .ifBlank { request.sessionId }
        return "${anchor.ifBlank { "global" }}:${prompt.trim()}"
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
            images = emptyList(),
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
        val context = AgentAppContext.resolve() ?: return
        modelExecutor.execute {
            AgentRuntimeClient(context, logger).ackResult(runId)
        }
    }

    private fun extractTextRequest(classLoader: ClassLoader, message: Any?): TextRequest? {
        if (message == null) return null
        val events = HookSupport.invokeNoArgs(message, "getEvents") as? Iterable<*> ?: return null
        val eventList = events.toList()
        val recordId = invokeString(message, "getRecordId").orEmpty()
        val originalRecordId = invokeString(message, "getOriginalRecordId").orEmpty()
        val sessionId = invokeString(message, "getSessionId").orEmpty()
        val roomId = invokeString(message, "getRoomId").orEmpty()
        val messageThinkingEnabledOverride = extractThinkingEnabledOverride(eventList)
        if (messageThinkingEnabledOverride != null) {
            lastBreenoThinkingEnabledOverride = messageThinkingEnabledOverride
        }
        val thinkingEnabledOverride = messageThinkingEnabledOverride
            ?: lastBreenoThinkingEnabledOverride
            ?: currentBreenoThinkingEnabledOverride(classLoader)
        for (event in eventList) {
            val header = HookSupport.invokeNoArgs(event ?: continue, "getHeader")
            val namespace = invokeString(header, "getNamespace")
            val name = invokeString(header, "getName")
            if (namespace != "Nlp" || name != "Text") continue
            val payload = HookSupport.invokeNoArgs(event, "getPayload")
            val text = invokeString(payload, "getText") ?: continue
            val images = mergeRequestImages(
                BreenoRequestImages.fromMessage(AgentAppContext.resolve(), message),
                cachedImagesFor(recordId, originalRecordId, sessionId, roomId)
            )
            return TextRequest(
                runId = newCompactId(),
                text = text,
                images = images,
                recordId = recordId,
                originalRecordId = originalRecordId,
                sessionId = sessionId,
                roomId = roomId,
                thinkingEnabledOverride = thinkingEnabledOverride
            )
        }
        return null
    }

    private fun extractThinkingEnabledOverride(events: Iterable<*>): Boolean? =
        events.firstNotNullOfOrNull { event ->
            val header = HookSupport.invokeNoArgs(event ?: return@firstNotNullOfOrNull null, "getHeader")
            val namespace = invokeString(header, "getNamespace")
            val name = invokeString(header, "getName")
            if (namespace != "Client" || name != "ThinkingModeSwitch") {
                return@firstNotNullOfOrNull null
            }
            val payload = HookSupport.invokeNoArgs(event, "getPayload")
            thinkingEnabledFromMode(invokeString(payload, "getThinkMode"))
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
        val runId: String,
        private val renderer: BreenoStreamRenderer
    ) {
        @Volatile
        var future: Future<*>? = null

        fun cancel(logger: ModuleLogger, replacementRunId: String) {
            renderer.cancel()
            future?.cancel(true)
            logger.info("Breeno active Agent run replaced: runId=$runId, replacement=$replacementRunId")
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
                is AgentEvent.AssistantReasoningDelta -> {
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
                    logger.info(
                        "Breeno stream frame injected: type=${if (wasCreated) 0 else 2}, " +
                            "final=$isFinal, content=${content.length}, " +
                            "reasoning=${reasoningContent.length}, roomId=$roomId"
                    )
                }
                true
            }.getOrElse { throwable ->
                disabled = true
                logger.warn(
                    "Breeno: 流式注入失败，回退最终注入: " +
                        (throwable.message ?: throwable.javaClass.simpleName)
                )
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
        response: AgentModelClient.ModelResponse.Text
    ) {
        runCatching {
            val roomId = request.roomId.ifBlank { currentRoomId(classLoader) }
            if (roomId.isBlank()) {
                logger.warn("Breeno: 跳过历史写入，roomId 为空")
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
                payload = null
            )
            val answerRecord = newInsertRecord(
                classLoader = classLoader,
                recordId = recordId,
                content = response.content,
                type = RECORD_TYPE_ANSWER,
                payload = buildHistoryPayloadJson(
                    response = response,
                    request = request.copy(recordId = recordId, roomId = roomId)
                )
            )
            invokeCompatible(
                repository,
                "r",
                roomId,
                agentName,
                queryRecord,
                newHistoryCallback(classLoader, logger, "query")
            )
            invokeCompatible(
                repository,
                "r",
                roomId,
                agentName,
                answerRecord,
                newHistoryCallback(classLoader, logger, "answer")
            )
            logger.info(
                "Breeno history persist requested: recordId=$recordId, " +
                    "roomId=$roomId, agent=$agentName"
            )
        }.onFailure { throwable ->
            logger.warn("Breeno: 写入历史记录失败: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
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
        payload: String?
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
        label: String
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
                    logger.info(
                        "Breeno history callback[$label]: " +
                            (result?.javaClass?.name ?: "null")
                    )
                    unit
                }
                "toString" -> "BreenoHistoryCallback($label)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    private fun summarizePayload(payload: Any?): String {
        if (payload == null) return ""
        val text = invokeString(payload, "getText")
        if (!text.isNullOrBlank()) {
            return "{text=\"${text.compact()}\"}"
        }
        return "{payload=${payload.javaClass.name.substringAfterLast('.')}}"
    }

    private fun summarizeInboundMessage(messageId: String?, content: String?): String {
        if (content.isNullOrBlank()) return "messageId=$messageId, content=blank"
        val json = JSONObject(content)
        val directives = json.optJSONArray("directives")
        return "messageId=$messageId, " +
            "originalRecordId=${json.optString("originalRecordId")}, " +
            "recordId=${json.optString("recordId")}, " +
            "sessionId=${json.optString("sessionId")}, " +
            "roomId=${json.optString("roomId")}, " +
            "uniqueId=${json.optString("uniqueId")}, " +
            "seq=${json.opt("sequenceId")}, " +
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
                ", isFinal=${payload.opt("isFinal")}"
            } else {
                ""
            }
            val state = payload?.optString("state").orEmpty()
            val stateMark = if (state.isNotBlank()) ", state=$state" else ""
            "$namespace.$name$finalMark$stateMark"
        }
        return names.joinToString(prefix = "[", postfix = "]")
    }

    private fun logRelevantDirectivePayloads(logger: ModuleLogger, messageId: String?, content: String?) {
        if (content.isNullOrBlank()) return
        val json = JSONObject(content)
        val directives = json.optJSONArray("directives") ?: return
        for (index in 0 until directives.length()) {
            val directive = directives.optJSONObject(index) ?: continue
            val header = directive.optJSONObject("header")
            val namespace = header?.optString("namespace").orEmpty()
            val name = header?.optString("name").orEmpty()
            if (!isRelevantToolDirective(namespace, name)) continue
            val payload = directive.optJSONObject("payload")
            val raw = JSONObject()
                .put("messageId", messageId)
                .put("originalRecordId", json.optString("originalRecordId"))
                .put("recordId", json.optString("recordId"))
                .put("sessionId", json.optString("sessionId"))
                .put("roomId", json.optString("roomId"))
                .put("uniqueId", json.optString("uniqueId"))
                .put("header", header ?: JSONObject())
                .put("payload", payload ?: JSONObject())
                .toString()
            logChunks(logger, "Breeno directive payload $namespace.$name", raw)
        }
    }

    private fun isRelevantToolDirective(namespace: String, name: String): Boolean =
        namespace in setOf(
            "App",
            "AnalogClick",
            "Sms",
            "PhoneCall",
            "Ocr",
            "SystemScreen",
            "System",
            "Command",
            "MyAI"
        ) || name.contains("Execution", ignoreCase = true) ||
            name.contains("Launch", ignoreCase = true)

    private fun logChunks(logger: ModuleLogger, prefix: String, raw: String) {
        if (raw.length <= RAW_LOG_CHUNK_CHARS) {
            logger.info("$prefix: $raw")
            return
        }
        raw.chunked(RAW_LOG_CHUNK_CHARS).forEachIndexed { index, chunk ->
            logger.info("$prefix[${index + 1}]: $chunk")
        }
    }

    private fun summarizeDmParameter(parameter: Any?): String {
        if (parameter == null) return "parameter=null"
        return "sessionId=${invokeString(parameter, "getSessionId")}, " +
            "recordId=${invokeString(parameter, "getRecordId")}, " +
            "currentRecordId=${invokeString(parameter, "getCurrentRecordId")}, " +
            "route=${invokeString(parameter, "getRoute")?.compact()}, " +
            "data=${invokeString(parameter, "getData")?.compact()}"
    }

    private fun cacheCdmImages(logger: ModuleLogger, parameter: Any?) {
        if (parameter == null) return
        val data = invokeString(parameter, "getData")
        val images = BreenoRequestImages.fromText(AgentAppContext.resolve(), data, "cdm.data")
        if (images.isEmpty()) return
        if (cdmImageCache.size > 32) cdmImageCache.clear()
        val keys = listOfNotNull(
            invokeString(parameter, "getRecordId"),
            invokeString(parameter, "getCurrentRecordId"),
            invokeString(parameter, "getSessionId")
        ).filter { it.isNotBlank() }.distinct()
        keys.forEach { key -> cdmImageCache[key] = images }
        logger.info(
            "Breeno request images cached: keys=${keys.size}, images=${images.size}, " +
                BreenoRequestImages.summary(images)
        )
    }

    private fun cachedImagesFor(vararg keys: String): List<AgentModelClient.ModelImage> =
        keys.asSequence()
            .filter { it.isNotBlank() }
            .flatMap { key -> cdmImageCache.remove(key).orEmpty().asSequence() }
            .toList()

    private fun mergeRequestImages(
        direct: List<AgentModelClient.ModelImage>,
        cached: List<AgentModelClient.ModelImage>
    ): List<AgentModelClient.ModelImage> =
        (direct + cached)
            .distinctBy { image -> "${image.mimeType}:${image.bytes}:${image.width}x${image.height}:${image.dataUrl.take(80)}" }
            .take(4)

    private fun invokeString(target: Any?, methodName: String): String? =
        HookSupport.invokeNoArgs(target ?: return null, methodName) as? String

    private fun invokeInt(target: Any?, methodName: String): Int? =
        (HookSupport.invokeNoArgs(target ?: return null, methodName) as? Number)?.toInt()

    private fun hasClientLocalData(bean: Any?, key: String): Boolean =
        runCatching {
            bean != null && invokeCompatible(bean, "getClientLocalData", key) != null
        }.getOrDefault(false)

    private fun String.compact(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_TEXT_CHARS) it.take(MAX_TEXT_CHARS) + "..." else it }

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
        val images: List<AgentModelClient.ModelImage>,
        val recordId: String,
        val originalRecordId: String,
        val sessionId: String,
        val roomId: String,
        val thinkingEnabledOverride: Boolean? = null
    )

    private data class TextRequestPayload(
        val userText: String,
        val adapterPayload: JSONObject,
    )
}
