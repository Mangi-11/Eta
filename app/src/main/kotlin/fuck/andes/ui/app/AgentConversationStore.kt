package fuck.andes.ui.app

import android.content.Context
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.ToolSummaryMessageUi
import fuck.andes.ui.model.UserMessageUi
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object AgentConversationStore {
    data class Snapshot(
        val selectedConversationId: String,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
    )

    fun load(context: Context, defaultThinkingEnabled: Boolean): Snapshot {
        val raw = prefs(context).getString(KEY_STATE, null).orEmpty()
        val parsed = runCatching {
            val root = JSONObject(raw)
            val conversations = linkedMapOf<String, AgentChatHomeUiState>()
            val titles = mutableMapOf<String, String>()
            val updatedAt = mutableMapOf<String, Long>()
            val array = root.optJSONArray("conversations") ?: JSONArray()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").ifBlank { newConversationId() }
                conversations[id] = AgentChatHomeUiState(
                    messages = json.optJSONArray("messages").toMessages(),
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = json.optBoolean("thinking_enabled", defaultThinkingEnabled),
                )
                titles[id] = json.optString("title").ifBlank { "新对话" }
                updatedAt[id] = json.optLong("updated_at").takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            }
            val fallback = fallbackSnapshot(defaultThinkingEnabled)
            if (conversations.isEmpty()) {
                fallback
            } else {
                val selected = root.optString("selected_id")
                    .takeIf { it in conversations }
                    ?: conversations.keys.first()
                Snapshot(
                    selectedConversationId = selected,
                    conversationsById = conversations,
                    titles = titles,
                    updatedAt = updatedAt,
                )
            }
        }.getOrElse {
            fallbackSnapshot(defaultThinkingEnabled)
        }
        return parsed
    }

    fun save(
        context: Context,
        selectedConversationId: String,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
    ) {
        val root = JSONObject()
            .put("version", 1)
            .put("selected_id", selectedConversationId)
        val conversations = JSONArray()
        conversationsById.entries
            .sortedByDescending { (id, _) -> updatedAt[id] ?: 0L }
            .take(MAX_STORED_CONVERSATIONS)
            .forEach { (id, state) ->
                conversations.put(
                    JSONObject()
                        .put("id", id)
                        .put("title", titles[id] ?: "新对话")
                        .put("updated_at", updatedAt[id] ?: System.currentTimeMillis())
                        .put("thinking_enabled", state.thinkingEnabled)
                        .put("messages", state.messages.toJsonArray())
                )
            }
        root.put("conversations", conversations)
        prefs(context).edit().putString(KEY_STATE, root.toString()).apply()
    }

    private fun JSONArray?.toMessages(): List<AgentChatMessageUi> {
        if (this == null) return emptyList()
        val messages = mutableListOf<AgentChatMessageUi>()
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val id = json.optString("id").ifBlank { "msg-${UUID.randomUUID()}" }
            when (json.optString("type")) {
                "user" -> messages += UserMessageUi(
                    id = id,
                    content = json.optString("content"),
                )

                "assistant" -> messages += AgentMessageUi(
                    id = id,
                    content = json.optString("content"),
                    isStreaming = false,
                    renderMarkdown = json.optBoolean("render_markdown", true),
                    usage = json.optJSONObject("usage")?.toUsage(),
                )

                "thinking" -> messages += ThinkingMessageUi(
                    id = id,
                    content = json.optString("content"),
                    isStreaming = false,
                    elapsedSeconds = json.optionalInt("elapsed_seconds"),
                    collapsed = true,
                )

                "tool" -> messages += ToolActivityMessageUi(
                    id = id,
                    toolName = json.optString("tool_name"),
                    status = json.optString("status").toToolStatus(),
                    argumentsSummary = json.optString("arguments_summary"),
                    resultSummary = json.optString("result_summary").ifBlank { null },
                    imageCount = json.optInt("image_count"),
                )

                "tool_summary" -> messages += ToolSummaryMessageUi(
                    id = id,
                    tools = json.optJSONArray("tools").toStringList(),
                )
            }
        }
        return messages
    }

    private fun List<AgentChatMessageUi>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            takeLast(MAX_STORED_MESSAGES_PER_CONVERSATION).forEach { message ->
                message.toJsonOrNull()?.let(array::put)
            }
        }

    private fun AgentChatMessageUi.toJsonOrNull(): JSONObject? = when (this) {
        is UserMessageUi -> JSONObject()
            .put("type", "user")
            .put("id", id)
            .put("content", content.clipStoredText())

        is AgentMessageUi -> {
            val content = content.clipStoredText()
            if (content.isBlank() && isStreaming) {
                null
            } else {
                JSONObject()
                    .put("type", "assistant")
                    .put("id", id)
                    .put("content", content)
                    .put("render_markdown", renderMarkdown)
                    .also { json -> usage?.takeUnless { it.isEmpty }?.let { json.put("usage", it.toJson()) } }
            }
        }

        is ThinkingMessageUi -> JSONObject()
            .put("type", "thinking")
            .put("id", id)
            .put("content", content.clipStoredText())
            .also { json -> elapsedSeconds?.let { json.put("elapsed_seconds", it) } }

        is ToolActivityMessageUi -> JSONObject()
            .put("type", "tool")
            .put("id", id)
            .put("tool_name", toolName)
            .put("status", status.name)
            .put("arguments_summary", argumentsSummary.clipStoredText())
            .put("image_count", imageCount)
            .also { json -> resultSummary?.let { json.put("result_summary", it.clipStoredText()) } }

        is ToolSummaryMessageUi -> JSONObject()
            .put("type", "tool_summary")
            .put("id", id)
            .put("tools", JSONArray().also { tools.forEach(it::put) })

        else -> null
    }

    private fun JSONObject.toUsage(): TokenUsageUi =
        TokenUsageUi(
            contextTokens = optionalInt("context"),
            inputTokens = optionalInt("input"),
            outputTokens = optionalInt("output"),
            reasoningTokens = optionalInt("reasoning"),
            cachedTokens = optionalInt("cached"),
        )

    private fun TokenUsageUi.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("context", it) }
            inputTokens?.let { json.put("input", it) }
            outputTokens?.let { json.put("output", it) }
            reasoningTokens?.let { json.put("reasoning", it) }
            cachedTokens?.let { json.put("cached", it) }
        }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.takeUnless { it == ToolActivityStatusUi.Running }
            ?: ToolActivityStatusUi.Failed

    private fun String.clipStoredText(): String =
        if (length > MAX_STORED_MESSAGE_CHARS) takeLast(MAX_STORED_MESSAGE_CHARS) else this

    private fun fallbackSnapshot(defaultThinkingEnabled: Boolean): Snapshot {
        val id = newConversationId()
        return Snapshot(
            selectedConversationId = id,
            conversationsById = mapOf(id to emptyChatState(defaultThinkingEnabled)),
            titles = mapOf(id to "新对话"),
            updatedAt = mapOf(id to System.currentTimeMillis()),
        )
    }

    private fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
        AgentChatHomeUiState(
            messages = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = thinkingEnabled,
        )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun newConversationId(): String = "conv-${UUID.randomUUID()}"

    private const val PREFS_NAME = "agent_conversations"
    private const val KEY_STATE = "state"
    private const val MAX_STORED_CONVERSATIONS = 50
    private const val MAX_STORED_MESSAGES_PER_CONVERSATION = 120
    private const val MAX_STORED_MESSAGE_CHARS = 16_000
}
