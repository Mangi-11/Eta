package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

/** loader 由 Runtime 绑定当前会话；分页与检索只限制返回内容，不修改持久历史。 */
internal class ConversationHistoryTool(
    private val loader: () -> List<AgentModelClient.ConversationMessage>,
) : AgentModelClient.ToolExecutor {
    private var readSnapshot: List<AgentModelClient.ConversationMessage>? = null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = JSONObject(toolCall.argumentsJson)
        val query = args.optString("query")
        require(query.length <= 500)
        var index = args.optInt("message_index", 0)
        var offset = args.optInt("offset", 0)
        var remaining = args.optInt("max_chars", MAX_PAGE_CHARS)
        require(index >= 0 && offset >= 0 && remaining in MIN_PAGE_CHARS..MAX_PAGE_CHARS)
        // 续读固定同一份历史，不能追着本工具新产生的日志无限读取；从零开始可刷新以包含最新轮次。
        if (readSnapshot == null || (index == 0 && offset == 0)) readSnapshot = loader()
        val history = checkNotNull(readSnapshot)
        require(index <= history.size)
        val entries = JSONArray()
        while (index < history.size && remaining > 0 && entries.length() < MAX_ENTRIES_PER_PAGE) {
            val message = history[index]
            val text = AgentConversationCodec.toJsonObject(message).toString()
            if (query.isBlank()) {
                require(offset <= text.length)
                var end = minOf(text.length, offset + remaining)
                if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
                if (end == offset && offset < text.length) break
                entries.put(
                    buildEntry(
                        index = index,
                        message = message,
                        offset = offset,
                        text = text.substring(offset, end),
                        complete = end == text.length,
                        totalChars = text.length,
                    ),
                )
                remaining -= end - offset
                if (end < text.length) {
                    offset = end
                    break
                }
                index++
                offset = 0
                continue
            }

            if (offset > text.length) {
                index++
                offset = 0
                continue
            }
            val matchPos = text.indexOf(query, startIndex = offset, ignoreCase = true)
            if (matchPos < 0) {
                index++
                offset = 0
                continue
            }
            if (offset == 0 && text.length <= remaining && text.length <= SEARCH_FULL_MESSAGE_THRESHOLD) {
                entries.put(
                    buildEntry(
                        index = index,
                        message = message,
                        offset = 0,
                        text = text,
                        complete = true,
                        totalChars = text.length,
                        matchOffset = matchPos,
                    ),
                )
                remaining -= text.length
                index++
                offset = 0
                continue
            }

            val windowBudget = minOf(remaining, SEARCH_SNIPPET_CHARS)
            if (windowBudget < query.length && entries.length() > 0) break
            var sliceStart = (matchPos - windowBudget / 3).coerceAtLeast(offset)
            sliceStart = minOf(sliceStart, matchPos)
            if (sliceStart > offset && sliceStart < text.length && text[sliceStart].isLowSurrogate()) {
                sliceStart--
            }
            var sliceEnd = minOf(text.length, sliceStart + windowBudget)
            if (sliceEnd < text.length && sliceEnd > sliceStart && text[sliceEnd - 1].isHighSurrogate()) {
                sliceEnd--
            }
            if (sliceEnd <= sliceStart) break
            entries.put(
                buildEntry(
                    index = index,
                    message = message,
                    offset = sliceStart,
                    text = text.substring(sliceStart, sliceEnd),
                    complete = sliceStart == 0 && sliceEnd == text.length,
                    totalChars = text.length,
                    matchOffset = matchPos,
                    snippet = sliceStart > 0 || sliceEnd < text.length,
                ),
            )
            remaining -= sliceEnd - sliceStart
            val hasAnotherMatch = sliceEnd < text.length &&
                text.indexOf(query, startIndex = sliceEnd, ignoreCase = true) >= 0
            if (hasAnotherMatch) {
                offset = sliceEnd
                if (remaining < MIN_SEARCH_CONTINUATION_BUDGET) break
            } else {
                index++
                offset = 0
            }
        }
        return AgentModelClient.ToolResult(
            JSONObject()
                .put("ok", true)
                .put("total_messages", history.size)
                .put("entries", entries)
                .put("has_more", index < history.size)
                .put("next_message_index", index)
                .put("next_offset", offset)
                .toString(),
        )
    }

    private fun buildEntry(
        index: Int,
        message: AgentModelClient.ConversationMessage,
        offset: Int,
        text: String,
        complete: Boolean,
        totalChars: Int,
        matchOffset: Int? = null,
        snippet: Boolean = false,
    ): JSONObject =
        JSONObject()
            .put("message_index", index)
            .put("role", message.role)
            .put("offset", offset)
            .put("total_chars", totalChars)
            .put("text", text)
            .put("complete", complete)
            .apply {
                if (message.compactionArchiveId.isNotBlank()) {
                    put("archive_id", message.compactionArchiveId)
                }
                if (matchOffset != null) put("match_offset", matchOffset)
                if (snippet) put("snippet", true)
            }

    private companion object {
        const val MIN_PAGE_CHARS = 256
        const val MAX_PAGE_CHARS = 8_000
        const val MAX_ENTRIES_PER_PAGE = 20
        const val SEARCH_FULL_MESSAGE_THRESHOLD = 1_600
        const val SEARCH_SNIPPET_CHARS = 1_200
        const val MIN_SEARCH_CONTINUATION_BUDGET = 128
    }
}
