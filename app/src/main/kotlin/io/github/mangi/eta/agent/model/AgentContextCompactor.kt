package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    private val mainTools: JSONArray? = null,
    private val mainSessionId: String? = null,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
) {
    data class CompactionOutcome(
        val messages: JSONArray,
        val archiveId: String,
        val coveredUserTurns: Int,
        val usage: AgentTokenUsage?,
        val llmMs: Long,
    )

    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): JSONArray = compactWithOutcome(messages, systemCount, sensitiveIds).messages

    fun compactWithOutcome(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): CompactionOutcome {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) {
            throw SignedToolContext.compactionFailure(messages)
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        // 最新用户请求及其后尚在进行的工具链必须可以继续；长任务允许压缩该请求之后的已完成批次。
        val end = (history.size downTo 1).firstOrNull { canSplit(history, it) }
            ?: throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val foldedSlice = history.take(end)
        val source = JSONArray(foldedSlice.filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可压缩的历史内容。")
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[图片观察已省略]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }

        // 沿用当前会话前缀与工具目录发起摘要请求以复用缓存；不投影屏幕上下文与待消费截图。
        val mainPrefixMessages = if (enableCacheReuse) {
            val rawPrefix = JSONArray()
            for (index in 0 until systemCount) {
                rawPrefix.put(JSONObject(messages.getJSONObject(index).toString()).apply {
                    remove("_eta_assistant_screen_context")
                })
            }
            for (index in 0 until end) {
                val item = history[index]
                if (item.optBoolean("_eta_observation")) continue
                rawPrefix.put(JSONObject(item.toString()).apply {
                    remove("_eta_assistant_screen_context")
                })
            }
            messageProjector?.invoke(rawPrefix) ?: rawPrefix
        } else {
            null
        }

        val summaryResult = AgentContextSummarizer(
            config = config,
            provider = provider,
            controller = controller,
            roleplay = roleplay,
            mainPrefixMessages = mainPrefixMessages,
            mainTools = mainTools,
            mainSessionId = mainSessionId,
            hasProtectedUser = protectedUser != null,
        ).summarizeWithUsage(safe)

        val previousSummaries = foldedSlice.filter { it.optBoolean("_eta_context_summary") }
        val maxExistingArchiveSeq = previousSummaries.maxOfOrNull { item ->
            val rawId = item.optString("_eta_compaction_archive_id")
            ARCHIVE_ID_REGEX.find(rawId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        } ?: 0
        val nextArchiveSeq = maxOf(maxExistingArchiveSeq, previousSummaries.size) + 1
        val archiveId = "fold-$nextArchiveSeq"
        val previousCovered = previousSummaries.sumOf { it.optInt("_eta_compacted_users") }
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }

        val archiveHeader = buildString {
            append("[Eta 上下文摘要 · 归档 ").append(archiveId)
            if (covered > 0) {
                append(" · 覆盖第 1–").append(covered).append(" 轮用户对话")
                if (previousCovered > 0 && covered > previousCovered) {
                    append("（本次新增第 ").append(previousCovered + 1).append("–").append(covered).append(" 轮）")
                }
            }
            append("：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。")
            append("如需核对原文细节、完整命令输出、路径、标识或数值，可调用 ")
            append(AgentConversationToolCatalog.READ_HISTORY)
            append(" 按 query 检索或按 message_index 分页展开。]\n")
        }

        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(
            AgentConversationCodec.toJsonObject(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "$archiveHeader${summaryResult.summary}",
                    contextSummary = true,
                    compactedUserTurns = covered,
                    summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
                    compactionArchiveId = archiveId,
                ),
            ),
        )
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // 新摘要改变了前缀；旧 opaque items 不再代表同一份 Provider 上下文。
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(
                GeminiEphemeralState.withoutState(
                    AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems),
                ),
            )
        }
        if (result.toString().length >= messages.toString().length) {
            throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
        }
        return CompactionOutcome(
            messages = result,
            archiveId = archiveId,
            coveredUserTurns = covered,
            usage = summaryResult.usage,
            llmMs = summaryResult.llmMs,
        )
    }

    companion object {
        private val ARCHIVE_ID_REGEX = Regex("^fold-(\\d+)$")

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
