package fuck.andes.agent.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Provider JSON 与 Eta 稳定会话 DTO 之间的唯一转换和容量边界。 */
internal object AgentConversationCodec {
    internal const val MAX_IPC_TRANSCRIPT_CHARS = 96_000
    internal const val MAX_DRAIN_TRANSCRIPT_CHARS = 16_000
    internal const val MAX_STORAGE_TRANSCRIPT_CHARS = 1_000_000

    private const val MAX_CONTENT_CHARS = 64_000
    private const val MAX_REASONING_CHARS = 64_000
    private const val MAX_TOOL_ARGUMENT_CHARS = 32_000
    private const val MAX_TOOL_CALLS_PER_MESSAGE = 64
    private const val IMAGE_OMITTED_TEXT = "[图片观察已在当前回合使用，未写入持久会话]"
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeTranscriptForIpc(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages, MAX_IPC_TRANSCRIPT_CHARS)

    fun encodeTranscriptForDrain(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages, MAX_DRAIN_TRANSCRIPT_CHARS)

    fun encodeTranscriptForStorage(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages, MAX_STORAGE_TRANSCRIPT_CHARS)

    fun messagesForIpc(
        messages: List<AgentModelClient.ConversationMessage>,
    ): List<AgentModelClient.ConversationMessage> =
        decodeTranscript(encodeTranscriptForIpc(messages))

    fun decodeTranscript(raw: String?): List<AgentModelClient.ConversationMessage> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<AgentModelClient.ConversationMessage>>(raw)
            }.getOrDefault(emptyList())
        }

    fun toJsonObject(message: AgentModelClient.ConversationMessage): JSONObject =
        JSONObject()
            .put("role", message.role)
            .also { target ->
                when {
                    message.contentJson.isNotBlank() ->
                        target.put("content", JSONTokener(message.contentJson).nextValue())
                    else -> target.put("content", message.content)
                }
                if (message.toolCallId.isNotBlank()) {
                    target.put("tool_call_id", message.toolCallId)
                }
                if (message.reasoningContent.isNotBlank()) {
                    target.put("reasoning_content", message.reasoningContent)
                }
                if (message.toolCallsJson.isNotBlank()) {
                    target.put("tool_calls", JSONTokener(message.toolCallsJson).nextValue())
                }
            }

    fun fromJsonObject(message: JSONObject): AgentModelClient.ConversationMessage {
        val contentValue = message.opt("content")
        return AgentModelClient.ConversationMessage(
            role = message.optString("role"),
            content = (contentValue as? String).orEmpty(),
            contentJson = if (
                contentValue == null ||
                contentValue == JSONObject.NULL ||
                contentValue is String
            ) {
                ""
            } else {
                contentValue.toString()
            },
            toolCallId = message.optString("tool_call_id"),
            reasoningContent = message.optString("reasoning_content"),
            toolCallsJson = message.optJSONArray("tool_calls")?.toString().orEmpty(),
        )
    }

    fun userTextMessage(text: String): JSONObject =
        JSONObject()
            .put("role", "user")
            .put("content", text)

    fun userMessage(
        text: String,
        images: List<AgentModelClient.ModelImage>,
    ): JSONObject {
        if (images.isEmpty()) return userTextMessage(text)

        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text)
        )
        images.forEach { image ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image.dataUrl))
            )
        }
        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    fun assistantHistoryMessage(
        source: JSONObject,
        toolCalls: List<AgentModelClient.ToolCall>,
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", source.opt("content") ?: JSONObject.NULL)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEach { call -> array.put(call.toHistoryJson()) }
                        },
                    )
                }
                if (source.has("reasoning_content") && !source.isNull("reasoning_content")) {
                    message.put("reasoning_content", source.optString("reasoning_content"))
                }
            }

    fun toolResultMessage(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCall.id)
            .put("content", result.content)

    fun parseToolCalls(message: JSONObject): List<AgentModelClient.ToolCall> {
        val rawCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        val usedIds = mutableSetOf<String>()
        return buildList {
            for (index in 0 until rawCalls.length()) {
                val rawCall = rawCalls.optJSONObject(index) ?: JSONObject()
                val function = rawCall.optJSONObject("function")
                val arguments = function?.opt("arguments")
                val candidateId = rawCall.optString("id").ifBlank { "tool_call_$index" }
                val stableId = if (usedIds.add(candidateId)) {
                    candidateId
                } else {
                    generateSequence(1) { it + 1 }
                        .map { suffix -> "${candidateId}_$suffix" }
                        .first(usedIds::add)
                }
                add(
                    AgentModelClient.ToolCall(
                        id = stableId,
                        name = function?.optString("name")?.trim().orEmpty().ifBlank { "unknown_tool" },
                        argumentsJson = when (arguments) {
                            is JSONObject -> arguments.toString()
                            is String -> arguments.ifBlank { "{}" }
                            else -> "{}"
                        },
                    )
                )
            }
        }
    }

    private fun AgentModelClient.ToolCall.toHistoryJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", argumentsJson),
            )

    fun transcript(
        messages: JSONArray,
        startIndex: Int,
    ): List<AgentModelClient.ConversationMessage> =
        buildList {
            for (index in startIndex until messages.length()) {
                messages.optJSONObject(index)
                    ?.let(::fromJsonObject)
                    ?.let(::sanitizeMessage)
                    ?.let(::add)
            }
        }

    fun durableMessage(message: JSONObject): AgentModelClient.ConversationMessage =
        sanitizeMessage(fromJsonObject(message))

    private fun encodeBounded(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = messages.map(::sanitizeMessage).toMutableList()
        var encoded = json.encodeToString(bounded)
        if (encoded.length <= maxChars) return encoded

        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        while (bounded.size > 1) {
            bounded.removeAt(0)
            while (bounded.firstOrNull()?.role == "tool") bounded.removeAt(0)
            encoded = json.encodeToString(listOf(notice) + bounded)
            if (encoded.length <= maxChars) return encoded
        }

        val last = bounded.lastOrNull() ?: return "[]"
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

    private fun sanitizeMessage(
        message: AgentModelClient.ConversationMessage,
    ): AgentModelClient.ConversationMessage =
        message.copy(
            role = message.role.take(32),
            content = message.content.take(MAX_CONTENT_CHARS),
            contentJson = sanitizeContentJson(message.contentJson),
            toolCallId = message.toolCallId.take(256),
            reasoningContent = message.reasoningContent.take(MAX_REASONING_CHARS),
            toolCallsJson = sanitizeToolCallsJson(message.toolCallsJson),
        )

    private fun sanitizeContentJson(raw: String): String {
        if (raw.isBlank()) return ""
        val content = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val sanitized = when (content) {
            is JSONArray -> sanitizeContentArray(content)
            is JSONObject -> sanitizeContentObject(content)
            else -> return ""
        }
        return sanitized.toString().takeIf { it.length <= MAX_CONTENT_CHARS }
            ?: JSONArray()
                .put(JSONObject().put("type", "text").put("text", IMAGE_OMITTED_TEXT))
                .toString()
    }

    private fun sanitizeContentArray(source: JSONArray): JSONArray {
        val target = JSONArray()
        var omittedImage = false
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optString("type") == "image_url" || item.has("source")) {
                omittedImage = true
                continue
            }
            target.put(sanitizeContentObject(item))
        }
        if (omittedImage) {
            target.put(JSONObject().put("type", "text").put("text", IMAGE_OMITTED_TEXT))
        }
        return target
    }

    private fun sanitizeContentObject(source: JSONObject): JSONObject =
        JSONObject(source.toString()).also { target ->
            target.remove("image_url")
            target.remove("source")
            if (target.has("text")) {
                target.put("text", target.optString("text").take(MAX_CONTENT_CHARS / 2))
            }
        }

    private fun sanitizeToolCallsJson(raw: String): String {
        if (raw.isBlank()) return ""
        val source = runCatching { JSONArray(raw) }.getOrNull() ?: return ""
        val target = JSONArray()
        for (index in 0 until minOf(source.length(), MAX_TOOL_CALLS_PER_MESSAGE)) {
            val call = source.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function")
            val arguments = function?.optString("arguments").orEmpty()
            target.put(
                JSONObject()
                    .put("id", call.optString("id").take(256))
                    .put("type", call.optString("type").ifBlank { "function" })
                    .put(
                        "function",
                        JSONObject()
                            .put("name", function?.optString("name").orEmpty().take(128))
                            .put(
                                "arguments",
                                if (arguments.length <= MAX_TOOL_ARGUMENT_CHARS) {
                                    arguments
                                } else {
                                    JSONObject().put("_eta_truncated", true).toString()
                                },
                            ),
                    ),
            )
        }
        return target.toString()
    }
}
