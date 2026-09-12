package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅为不认识完整载荷字段的旧客户端提供内联预览；禁止用于存储或新模型请求。 */
internal object AgentLegacyConversationProjection {
    const val DIRECT_CHARS = 96_000
    const val DRAIN_CHARS = 16_000
    private val json = Json { encodeDefaults = false }
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    fun encode(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeTranscriptForStorage(messages),
        ).toMutableList()
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

}
