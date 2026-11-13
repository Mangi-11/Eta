package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object AnthropicMessagesProvider : AgentProviderClient {
    private const val DEFAULT_MAX_TOKENS = 4096
    private const val ADAPTIVE_MODEL_DEFAULT_MAX_TOKENS = 16_384
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val TOP_LEVEL_COMBINATORS = listOf("oneOf", "anyOf", "allOf")
    private const val TRANSIENT_BLOCK = "_eta_transient"
    private val COMPACTION_PROTECTED_FIELDS = setOf(
        "model", "stream", "messages", "input", "instructions", "system",
        "system_instruction", "tools", "tool_choice",
    )

    override val id: String = "anthropic_messages"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.ANTHROPIC_MESSAGES,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .add("anthropic-version", config.anthropicVersion)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-api-key", config.apiKey)
                }
                ProviderRequestHeaders.mergeInto(this, config.baseUrl, config.customHeaders, request.sessionId)
            }
            .build()
        val httpRequest = Request.Builder()
            .url(ProviderUrls.anthropicMessagesUrl(config.baseUrl))
            .headers(headers)
            .post(
                buildRequestJson(config, request.messages, request.effectiveTools, request.purpose)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        val call = AgentHttpClient.modelClient(request.purpose).newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    val errorBody = response.peekBody(16_384).string()
                    throw AgentModelFailure.http(response.code, errorBody)
                }
                val assistant = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    ): JSONObject {
        val systemParts = mutableListOf<String>()
        val anthropicMessages = JSONArray()
        // 当前轮（最后一条真实用户消息之后）的助手消息都原样带回签名块：工具循环中逐轮保持
        // 同一前缀，缓存才能在每一轮续用；压缩指令消息不推进当前轮起点，以免丢失当前轮签名缓存。
        val currentTurnStart = (messages.length() - 1 downTo 0).firstOrNull { index ->
            messages.optJSONObject(index)?.let {
                it.optString("role") == "user" &&
                    !it.optBoolean("_eta_observation") &&
                    !it.optBoolean("_eta_compaction_prompt")
            } == true
        } ?: -1
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> providerMessageText(message.opt("content"))
                    .takeIf { it.isNotBlank() }
                    ?.let(systemParts::add)
                "user" -> appendTurn(
                    anthropicMessages,
                    "user",
                    convertUserContent(message.opt("content")).also { blocks ->
                        if (message.optBoolean("_eta_observation")) {
                            for (block in 0 until blocks.length()) blocks.optJSONObject(block)?.put(TRANSIENT_BLOCK, true)
                        }
                    },
                )
                "assistant" -> appendTurn(
                    anthropicMessages,
                    "assistant",
                    convertAssistantContent(message, index > currentTurnStart),
                )
                "tool" -> appendTurn(
                    anthropicMessages,
                    "user",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.optString("tool_call_id"))
                            .put("content", message.optString("content"))
                    ),
                )
            }
        }

        // Anthropic 修改 tool_choice 会使整个 messages 缓存失效；压缩主路径保持与对话一致的 tool_choice，
        // 靠指令禁止调用工具并在流中拦截，从而兼顾 tools/system/messages 全链路缓存命中。
        val customBody = if (purpose == ProviderRequestPurpose.COMPACTION) {
            config.customBody.filterNot { it.key in COMPACTION_PROTECTED_FIELDS }
        } else {
            config.customBody
        }
        return JSONObject()
            .put("model", config.model)
            .put("max_tokens", defaultMaxTokens(config.model))
            .put("stream", true)
            .put("messages", anthropicMessages)
            .also { request ->
                val system = systemParts.joinToString("\n\n").trim()
                if (system.isNotBlank()) {
                    request.put("system", JSONArray().put(cacheBreakpoint(JSONObject().put("type", "text").put("text", system))))
                }
                convertTools(tools)?.let { converted ->
                    converted.optJSONObject(converted.length() - 1)?.let(::cacheBreakpoint)
                    request.put("tools", converted)
                }
                markConversationBreakpoint(anthropicMessages)
                RequestBodyMerge.mergeCustomBody(request, customBody)
                val explicitMaxTokens = request.opt("max_tokens")
                    .takeIf { customBody.any { body -> body.key == "max_tokens" } }
                ProviderReasoning.applyAnthropicRequest(request, config)
                if (explicitMaxTokens != null) request.put("max_tokens", explicitMaxTokens)
            }
    }

    /**
     * Anthropic 只缓存显式标记的前缀。工具目录与系统提示各设一个断点，跨会话复用；
     * 最后一条消息再设一个随轮前移的断点，下一轮按最长匹配前缀读取，覆盖整个工具循环。
     */
    private fun cacheBreakpoint(block: JSONObject): JSONObject =
        block.put("cache_control", JSONObject().put("type", "ephemeral"))

    private fun markConversationBreakpoint(messages: JSONArray) {
        val content = messages.optJSONObject(messages.length() - 1)?.optJSONArray("content") ?: return
        val blocks = (0 until content.length()).mapNotNull(content::optJSONObject)
        // 观察截图只活一轮，断点落在它之前才能被下一轮命中；思考块不可标记，空文本标记会被拒绝。
        val target = blocks.lastOrNull { block ->
            !block.optBoolean(TRANSIENT_BLOCK) && when (block.optString("type")) {
                "text" -> block.optString("text").isNotEmpty()
                "image", "tool_result", "tool_use", "document" -> true
                else -> false
            }
        }
        target?.let(::cacheBreakpoint)
        // 标记只服务于选断点，发送前从所有消息中清除。
        for (message in 0 until messages.length()) {
            val blocksOfMessage = messages.optJSONObject(message)?.optJSONArray("content") ?: continue
            for (block in 0 until blocksOfMessage.length()) blocksOfMessage.optJSONObject(block)?.remove(TRANSIENT_BLOCK)
        }
    }

    /**
     * 同一批工具结果、随后的观察图片与用户补充必须落在同一条 user 消息里，且 tool_result 在前。
     * 官方接口会合并连续同角色消息，但兼容端点不一定这样做，会把第二条 tool_result 判为孤立块。
     */
    private fun appendTurn(target: JSONArray, role: String, content: JSONArray) {
        val previous = target.optJSONObject(target.length() - 1)
        if (previous?.optString("role") != role) {
            target.put(JSONObject().put("role", role).put("content", content))
            return
        }
        val merged = previous.getJSONArray("content")
        for (index in 0 until content.length()) merged.put(content.get(index))
    }

    private fun defaultMaxTokens(model: String): Int =
        if (model.trim().lowercase() in setOf("claude-fable-5-1", "claude-opus-5-5")) {
            ADAPTIVE_MODEL_DEFAULT_MAX_TOKENS
        } else {
            DEFAULT_MAX_TOKENS
        }

    private fun convertUserContent(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text")
                            .takeIf { it.isNotBlank() }
                            ?.let { out.put(JSONObject().put("type", "text").put("text", it)) }
                        "image_url" -> convertImageBlock(item)?.let(out::put)
                    }
                }
                if (out.length() == 0) out.put(JSONObject().put("type", "text").put("text", ""))
            }
            else -> JSONArray().put(JSONObject().put("type", "text").put("text", providerMessageText(content)))
        }

    private fun convertAssistantContent(message: JSONObject, preserveOpaque: Boolean): JSONArray {
        if (preserveOpaque) {
            AnthropicEphemeralState.contentBlocks(message)?.let { return JSONArray(it.toString()) }
        }
        val content = JSONArray()
        providerMessageText(message.opt("content"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { content.put(JSONObject().put("type", "text").put("text", it)) }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                content.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", toolCall.optString("id").ifBlank { "tool_call_$index" })
                        .put("name", function.optString("name"))
                        .put("input", parseJsonObject(function.optString("arguments")))
                )
            }
        }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        return content
    }

    private fun convertTools(tools: JSONArray): JSONArray? {
        if (tools.length() == 0) return null
        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            converted.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("input_schema", inputSchema(function.optJSONObject("parameters")))
            )
        }
        return converted.takeIf { it.length() > 0 }
    }

    /**
     * input_schema 顶层不接受 oneOf/anyOf/allOf，否则整个请求 400。只在发送副本上移除；
     * 参数交给对应工具执行，执行结果随工具回合返回模型。
     */
    private fun inputSchema(parameters: JSONObject?): JSONObject {
        parameters ?: return JSONObject().put("type", "object")
        if (TOP_LEVEL_COMBINATORS.none(parameters::has)) return parameters
        return JSONObject(parameters.toString()).apply { TOP_LEVEL_COMBINATORS.forEach(::remove) }
    }

    private fun convertImageBlock(item: JSONObject): JSONObject? {
        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val meta = url.substring(5, comma)
        val mediaType = meta.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", data)
            )
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = linkedMapOf<Int, AnthropicBlock>()
        var sawMessageStop = false
        var finishReason: String? = null
        var stopDetails: JSONObject? = null
        var usage: AgentTokenUsage? = null

        readProviderSse(stream, runController) { event, payload ->
            val result = processEvent(
                event = event,
                payload = payload.trim(),
                blocks = blocks,
                content = content,
                reasoning = reasoning,
                onEvent = onEvent,
            )
            if (result.messageStop) sawMessageStop = true
            result.finishReason?.let { finishReason = it }
            result.stopDetails?.let { stopDetails = it }
            result.usage?.let { delta ->
                val merged = usage?.mergedWith(delta) ?: delta
                usage = merged
                onEvent(ProviderEvent.Usage(merged, merged.contextTokens))
            }
            !sawMessageStop
        }
        if (!sawMessageStop) throw AgentModelFailure.incompleteStream("Anthropic SSE 流未正常结束")
        if (blocks.values.any { it.type in setOf("text", "thinking", "redacted_thinking", "tool_use") && !it.stopped }) {
            throw AgentModelFailure.incompleteStream("Anthropic SSE 内容块未正常结束")
        }
        val toolCalls = blocks.values
            .filter { it.type == "tool_use" && it.name.isNotBlank() }
            .sortedBy { it.index }
        if (toolCalls.isNotEmpty() && blocks.values.any { it.type == "thinking" && it.signature.isEmpty() }) {
            throw AgentModelFailure.incompleteStream("Anthropic SSE 工具回合缺少思考签名")
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoning.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
                stopDetails?.let { message.put("stop_details", it) }
                if (toolCalls.isNotEmpty()) {
                    val toolCallIds = toolCalls.mapIndexed { position, block ->
                        block.index to block.id.ifBlank { "tool_call_$position" }
                    }.toMap()
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEachIndexed { position, block ->
                                array.put(block.toToolCallJson(position))
                            }
                        }
                    )
                    AnthropicEphemeralState.attachContentBlocks(
                        message,
                        JSONArray().also { array ->
                            blocks.values.sortedBy { it.index }
                                .mapNotNull { block -> block.toContentBlockJson(toolCallIds[block.index]) }
                                .forEach(array::put)
                        },
                    )
                }
            }
    }

    private fun processEvent(
        event: String,
        payload: String,
        blocks: MutableMap<Int, AnthropicBlock>,
        content: StringBuilder,
        reasoning: StringBuilder,
        onEvent: (ProviderEvent) -> Unit
    ): EventResult {
        if (payload == "[DONE]") return EventResult(messageStop = true)
        val json = JSONObject(payload)
        val type = json.optString("type").ifBlank { event }

        fun appendVisibleDelta(block: AnthropicBlock, text: String) {
            if (text.isEmpty()) return
            val kind = when (block.type) {
                "text" -> {
                    block.text.append(text)
                    content.append(text)
                    AssistantBlockKind.TEXT
                }
                "thinking" -> {
                    block.thinking.append(text)
                    reasoning.append(text)
                    AssistantBlockKind.THINKING
                }
                else -> return
            }
            onEvent(ProviderEvent.BlockDelta(kind, block.index, text))
        }

        return when (type) {
            "error" -> throw AgentModelFailure.stream(
                json.optJSONObject("error") ?: JSONObject(),
                "Anthropic SSE 返回错误",
            )
            "message_start" -> {
                EventResult(usage = parseUsage(json.optJSONObject("message")?.optJSONObject("usage")))
            }
            "content_block_start" -> {
                val index = json.optInt("index")
                val block = json.optJSONObject("content_block") ?: JSONObject()
                val item = AnthropicBlock(
                    index = index,
                    type = block.optString("type"),
                    id = block.optString("id"),
                    name = block.optString("name"),
                    initialContent = JSONObject(block.toString()),
                    signature = StringBuilder(block.optString("signature")),
                )
                block.optJSONObject("input")
                    ?.takeIf { it.length() > 0 }
                    ?.let { item.arguments.append(it.toString()) }
                blocks[index] = item
                when (item.type) {
                    "text" -> {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, index))
                        appendVisibleDelta(item, (block.opt("text") as? String).orEmpty())
                    }
                    "thinking" -> {
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, index))
                        appendVisibleDelta(item, (block.opt("thinking") as? String).orEmpty())
                    }
                    "tool_use" -> onEvent(
                        ProviderEvent.BlockStart(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = index,
                            blockId = item.id.ifBlank { null },
                            name = item.name.ifBlank { null },
                        )
                    )
                }
                EventResult()
            }
            "content_block_delta" -> {
                val index = json.optInt("index")
                val delta = json.optJSONObject("delta") ?: JSONObject()
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "text") }
                        appendVisibleDelta(block, (delta.opt("text") as? String).orEmpty())
                    }
                    "thinking_delta" -> {
                        val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "thinking") }
                        appendVisibleDelta(block, (delta.opt("thinking") as? String).orEmpty())
                    }
                    "signature_delta" -> {
                        blocks[index]?.signature?.append(delta.optString("signature"))
                    }
                    "input_json_delta" -> {
                        val partial = delta.optString("partial_json")
                        if (partial.isNotEmpty()) {
                            val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "tool_use") }
                            block.arguments.append(partial)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = index,
                                    delta = partial,
                                )
                            )
                        }
                    }
                }
                EventResult()
            }
            "content_block_stop" -> {
                val index = json.optInt("index")
                val block = blocks[index] ?: return EventResult()
                if (block.stopped) return EventResult()
                block.stopped = true
                val kind = when (block.type) {
                    "text" -> AssistantBlockKind.TEXT
                    "thinking" -> AssistantBlockKind.THINKING
                    "tool_use" -> AssistantBlockKind.TOOL_CALL
                    else -> return EventResult()
                }
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = kind,
                        index = index,
                        blockId = block.id.ifBlank { null },
                        name = block.name.ifBlank { null },
                        content = block.content(),
                    )
                )
                EventResult()
            }
            "message_delta" -> {
                val delta = json.optJSONObject("delta")
                EventResult(
                    finishReason = delta?.optString("stop_reason")?.takeIf { it.isNotBlank() },
                    stopDetails = delta?.optJSONObject("stop_details"),
                    usage = parseUsage(json.optJSONObject("usage"))
                )
            }
            "message_stop" -> EventResult(messageStop = true)
            else -> EventResult()
        }
    }

    private data class AnthropicBlock(
        val index: Int,
        var type: String = "",
        var id: String = "",
        var name: String = "",
        var stopped: Boolean = false,
        val initialContent: JSONObject = JSONObject(),
        val signature: StringBuilder = StringBuilder(),
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun content(): String =
            when (type) {
                "text" -> text.toString()
                "thinking" -> thinking.toString()
                else -> arguments.toString()
            }

        fun toContentBlockJson(toolCallId: String?): JSONObject? = when (type) {
            "text" -> JSONObject(initialContent.toString()).put("text", text.toString())
            "thinking" -> JSONObject(initialContent.toString())
                .put("thinking", thinking.toString())
                .also { content ->
                    if (signature.isNotEmpty()) content.put("signature", signature.toString())
                    else content.remove("signature")
                }
            "redacted_thinking" -> JSONObject(initialContent.toString())
            "tool_use" -> JSONObject(initialContent.toString())
                .put("id", toolCallId ?: id)
                .put("name", name)
                .put("input", AnthropicMessagesProvider.parseJsonObject(arguments.toString()))
            else -> null
        }

        fun toToolCallJson(position: Int): JSONObject =
            JSONObject()
                .put("id", id.ifBlank { "tool_call_$position" })
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString().ifBlank { "{}" })
                )
    }

    private data class EventResult(
        val messageStop: Boolean = false,
        val finishReason: String? = null,
        val stopDetails: JSONObject? = null,
        val usage: AgentTokenUsage? = null,
    )

    /**
     * Anthropic 不返回 total_tokens；本次请求实际占用的上下文是未缓存输入与缓存读写之和，
     * 自动压缩与输入框用量都以它为准。
     */
    private fun parseUsage(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        val input = usage.firstInt("input_tokens")
        val cacheRead = usage.firstInt("cache_read_input_tokens")
        val cacheWrite = usage.firstInt("cache_creation_input_tokens")
        return AgentTokenUsage(
            contextTokens = input?.let { it + (cacheRead ?: 0) + (cacheWrite ?: 0) },
            inputTokens = input,
            outputTokens = usage.firstInt("output_tokens"),
            reasoningTokens = usage.firstInt("thinking_output_tokens"),
            cachedTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
        ).takeUnless { it.isEmpty }
    }

    /**
     * message_start 给出输入用量，message_delta 给出累计输出，部分兼容端点只在 delta 中补发部分字段；
     * 后到的非空字段覆盖先到的，避免输出用量把输入与上下文占用清空。
     */
    private fun AgentTokenUsage.mergedWith(later: AgentTokenUsage) = AgentTokenUsage(
        contextTokens = later.contextTokens ?: contextTokens,
        inputTokens = later.inputTokens ?: inputTokens,
        outputTokens = later.outputTokens ?: outputTokens,
        reasoningTokens = later.reasoningTokens ?: reasoningTokens,
        cachedTokens = later.cachedTokens ?: cachedTokens,
        cacheWriteTokens = later.cacheWriteTokens ?: cacheWriteTokens,
    )

    private fun parseJsonObject(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val raw = opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
            cacheWriteTokens?.let { json.put("cache_write_tokens", it) }
        }


}

/** 只在当前 Agent run 的工具回合保留 Anthropic 签名块；稳定会话 DTO 不序列化该字段。 */
internal object AnthropicEphemeralState {
    private const val CONTENT_BLOCKS_KEY = "_eta_anthropic_content_blocks"

    fun contentBlocks(message: JSONObject): JSONArray? = message.optJSONArray(CONTENT_BLOCKS_KEY)

    fun attachContentBlocks(message: JSONObject, blocks: JSONArray) {
        message.put(CONTENT_BLOCKS_KEY, JSONArray(blocks.toString()))
    }

    fun copyContentBlocks(source: JSONObject, target: JSONObject) {
        contentBlocks(source)?.let { attachContentBlocks(target, it) }
    }

    fun hasPendingToolResponse(messages: JSONArray): Boolean {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "assistant") {
                return contentBlocks(message) != null &&
                    AgentConversationCodec.parseToolCalls(message).isNotEmpty()
            }
        }
        return false
    }

    fun withoutContentBlocks(message: JSONObject): JSONObject =
        if (contentBlocks(message) == null) message else JSONObject(message.toString()).apply {
            remove(CONTENT_BLOCKS_KEY)
        }
}
