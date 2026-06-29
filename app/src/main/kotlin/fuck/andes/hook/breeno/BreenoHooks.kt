package fuck.andes.hook.breeno

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.agent.tool.AgentLocalTools
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger

import android.os.Handler
import android.os.Looper
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
    private const val EXPERIMENTAL_PREFIX = "/agent "
    private const val EXPERIMENTAL_ADB_PREFIX = "/agent%20"
    private const val MAX_TEXT_CHARS = 240
    private const val RAW_LOG_CHUNK_CHARS = 3_200
    private val modelExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FuckAndes-AgentModel").apply { isDaemon = true }
    }
    private val cdmImageCache = ConcurrentHashMap<String, List<AgentModelClient.ModelImage>>()

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        hookOutboundMessage(module, logger, classLoader)
        hookInboundMessage(module, logger, classLoader)
        hookCdmTextRequest(module, logger, classLoader)
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
            Integer::class.java,
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
            runCatching {
                logger.info("Breeno inbound: ${summarizeInboundMessage(messageId, content)}")
                logRelevantDirectivePayloads(logger, messageId, content)
            }.onFailure { throwable ->
                logger.warnThrottled(
                    "breeno_inbound_log_failed",
                    "Breeno: 记录入站消息失败: ${throwable.message}"
                )
            }
            chain.proceed()
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
        val request = extractTextRequest(message) ?: return false
        val prompt = resolveCustomModelPrompt(request.text) ?: return false
        if (prompt.isBlank()) return false

        return runCatching {
            BreenoExecutionOverlay.show("小布 Agent", "收到指令，准备调用模型")
            val runController = AgentRunController()
            modelExecutor.execute {
                val modelResponse = runCatching {
                    val config = AgentModelClient.loadConfig()
                    if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) {
                        error("请先在 FuckAndes 设置中启用“小布自定义模型”")
                    }
                    AgentModelClient.complete(
                        config,
                        prompt,
                        AgentLocalTools(logger),
                        images = request.images,
                        runController = runController
                    ) { event ->
                        logger.info("Agent event: ${event.toLogLine()}")
                        BreenoExecutionOverlay.update("小布 Agent", event.toOverlayStatus())
                    }
                }.getOrElse { throwable ->
                    val event = AgentEvent.RunFailed(throwable.message ?: throwable.javaClass.simpleName)
                    logger.info("Agent event: ${event.toLogLine()}")
                    BreenoExecutionOverlay.update("小布 Agent", event.toOverlayStatus())
                    BreenoExecutionOverlay.finish("调用失败：${throwable.message ?: throwable.javaClass.simpleName}")
                    AgentModelClient.ModelResponse.Text(
                        "小布自定义模型调用失败：${throwable.message ?: throwable.javaClass.simpleName}"
                    )
                }
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        injectModelResponse(classLoader, request.copy(text = prompt), modelResponse)
                        BreenoExecutionOverlay.finish("已返回结果")
                        logger.info(
                            "Breeno custom model injected: ${modelResponse.summary()}, " +
                                "recordId=${request.recordId}, sessionId=${request.sessionId}, " +
                                "roomId=${request.roomId}"
                        )
                    }.onFailure { throwable ->
                        logger.error("Breeno: 注入自定义模型响应失败", throwable)
                    }
                }
            }
            logger.info(
                "Breeno custom model takeover: text=\"${prompt.compact()}\", " +
                    "images=${request.images.size}, recordId=${request.recordId}, roomId=${request.roomId}"
            )
            true
        }.getOrElse { throwable ->
            BreenoExecutionOverlay.dismiss()
            logger.error("Breeno: 接管自定义模型请求失败，放行原请求", throwable)
            false
        }
    }

    private fun injectModelResponse(
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text
    ) {
        injectStreamTextCard(classLoader, request, response.content)
    }

    private fun resolveCustomModelPrompt(text: String): String? {
        text.removeExperimentalPrefixOrNull()?.let { return it }
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) return null
        if (Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX)) return null
        return text.trim()
    }

    private fun extractTextRequest(message: Any?): TextRequest? {
        if (message == null) return null
        val events = HookSupport.invokeNoArgs(message, "getEvents") as? Iterable<*> ?: return null
        val recordId = invokeString(message, "getRecordId").orEmpty()
        val originalRecordId = invokeString(message, "getOriginalRecordId").orEmpty()
        val sessionId = invokeString(message, "getSessionId").orEmpty()
        val roomId = invokeString(message, "getRoomId").orEmpty()
        for (event in events) {
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
                text = text,
                images = images,
                recordId = recordId,
                originalRecordId = originalRecordId,
                sessionId = sessionId,
                roomId = roomId
            )
        }
        return null
    }

    private fun injectStreamTextCard(
        classLoader: ClassLoader,
        request: TextRequest,
        content: String
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
        invokeCompatible(payload, "setFinal", java.lang.Boolean.TRUE)
        invokeCompatible(payload, "setType", Integer.valueOf(2))
        invokeCompatible(payload, "setQuery", request.text)
        invokeCompatible(payload, "setHtml", java.lang.Boolean.FALSE)
        invokeCompatible(payload, "setCharPerSec", Integer.valueOf(50))

        val directive = directiveClass.getDeclaredConstructor().newInstance()
        invokeCompatible(directive, "setHeader", header)
        val setPayload = directiveClass.getDeclaredMethod("setPayload", payloadClass).apply {
            isAccessible = true
        }
        setPayload.invoke(directive, payload)

        val directives = arrayListOf(directive)
        val origin = buildInjectedDownstreamJson(header, content, request)
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
        request: TextRequest
    ): String {
        val uniqueId = System.currentTimeMillis().toString()
        val directive = JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("id", invokeString(header, "getId"))
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
                    .put("isFinal", true)
                    .put("type", 2)
                    .put("query", request.text)
                    .put("isHtml", false)
                    .put("charPerSec", 50)
            )
        return JSONObject()
            .put("version", "3.0")
            .put("originalRecordId", request.originalRecordId.ifBlank { request.recordId })
            .put("recordId", request.recordId)
            .put("sessionId", request.sessionId)
            .put("roomId", request.roomId)
            .put("uniqueId", uniqueId)
            .put("sequenceId", 0)
            .put("directives", JSONArray().put(directive))
            .toString()
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

    private fun AgentEvent.toOverlayStatus(): String =
        when (this) {
            is AgentEvent.RunStarted -> "准备工具：$toolCount 个"
            is AgentEvent.RoundStarted -> "第 $round 轮思考"
            is AgentEvent.ProviderRequestStarted -> "正在请求模型"
            is AgentEvent.ProviderResponseStarted -> "模型已响应：HTTP $httpCode"
            is AgentEvent.AssistantTextDelta -> "正在生成回答"
            is AgentEvent.ProviderToolCallDelta -> "正在生成工具参数"
            is AgentEvent.AssistantReceived -> {
                if (toolNames.isEmpty()) "正在整理回答" else "计划执行：${toolNames.joinToString("、") { it.toToolLabel() }}"
            }
            is AgentEvent.ToolStarted -> "执行工具：${name.toToolLabel()}"
            is AgentEvent.ToolFinished -> "工具完成：${name.toToolLabel()}"
            is AgentEvent.ToolImagesAttached -> "已读取图片：$imageCount 张"
            is AgentEvent.RunFinished -> "正在返回结果"
            is AgentEvent.RunFailed -> "调用失败"
        }

    private fun String.toToolLabel(): String =
        when (this) {
            "observe_screen" -> "观察屏幕"
            "tap" -> "点击"
            "tap_element" -> "点击元素"
            "long_press" -> "长按"
            "swipe" -> "滑动"
            "scroll" -> "滚动"
            "input_text" -> "输入文字"
            "press_key" -> "按键"
            "search_apps" -> "搜索应用"
            "launch_app" -> "打开应用"
            "open_uri" -> "打开链接"
            "terminal" -> "终端"
            "run_command" -> "执行命令"
            "read_file" -> "读取文件"
            "write_file" -> "写入文件"
            "list_directory" -> "列出目录"
            else -> this
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
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> this
        }

    private fun newCompactId(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun AgentModelClient.ModelResponse.summary(): String =
        when (this) {
            is AgentModelClient.ModelResponse.Text -> "text"
        }

    private data class TextRequest(
        val text: String,
        val images: List<AgentModelClient.ModelImage>,
        val recordId: String,
        val originalRecordId: String,
        val sessionId: String,
        val roomId: String
    )
}
