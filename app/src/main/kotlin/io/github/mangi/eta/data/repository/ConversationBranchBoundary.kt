package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ConversationBranchMessage
import org.json.JSONArray

internal class ConversationBranchUnavailable : IllegalStateException("conversation_branch_unavailable")

internal object ConversationBranchBoundary {
    fun eligibleMessageIds(messages: List<ConversationBranchMessage>, running: Boolean = false): Set<String> = buildSet {
        var candidate: String? = null
        for (message in messages) {
            when (message.type) {
                "user" -> {
                    // 运行中的补充输入仍属于原轮次，不能将补充前的过渡正文当成最终结果。
                    if (!message.id.contains("-supplement-")) candidate?.let(::add)
                    candidate = null
                }
                "assistant" -> candidate = message.id.takeIf { message.hasContent }
                "system_notice" -> if (message.noticeCode != "context_compaction") candidate = null
                "tool_summary" -> Unit
                else -> candidate = null
            }
        }
        if (!running) candidate?.let(::add)
    }

    fun resolve(
        messages: List<ConversationBranchMessage>,
        journal: List<ConversationMessage>,
        roleplay: RoleplayMessageState,
        targetId: String,
    ): List<ConversationMessage> = resolveOrNull(messages, journal, roleplay, targetId)
        ?: throw ConversationBranchUnavailable()

    fun resolveOrNull(
        messages: List<ConversationBranchMessage>,
        journal: List<ConversationMessage>,
        roleplay: RoleplayMessageState,
        targetId: String,
    ): List<ConversationMessage>? {
        if (targetId !in eligibleMessageIds(messages)) return null
        val transcriptId = roleplay.links[targetId]?.transcriptMessageId
        val matches = journal.withIndex().filter { (_, message) ->
            message.role == "assistant" && message.messageId.isNotBlank() &&
                if (transcriptId != null) message.messageId == transcriptId
                else targetId == message.messageId || targetId.startsWith("${message.messageId}-")
        }
        val longest = matches.maxOfOrNull { it.value.messageId.length }
        val exact = matches.filter { it.value.messageId.length == longest }
        val end = exact.singleOrNull()?.index ?: return null
        val tail = journal.drop(end + 1).takeWhile { it.role != "user" }
        if (tail.any { it.role == "assistant" || it.role == "tool" }) return null
        val prefix = journal.take(end + 1)
        val last = prefix.lastOrNull() ?: return null
        if (last.role != "assistant" || (last.content.isBlank() && last.contentJson.isBlank())) {
            return null
        }
        val turns = prefix.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
        if (prefix.any { it.contextSummary && it.summaryThroughUserTurn > turns }) return null
        if (!hasClosedTools(prefix)) return null
        return prefix
    }

    private fun hasClosedTools(messages: List<ConversationMessage>): Boolean {
        val pending = mutableSetOf<String>()
        for (message in messages) {
            when (message.role) {
                "assistant" -> {
                    if (pending.isNotEmpty()) return false
                    if (message.toolCallsJson.isNotBlank()) {
                        val calls = try { JSONArray(message.toolCallsJson) } catch (_: org.json.JSONException) {
                            return false
                        }
                        for (index in 0 until calls.length()) {
                            val id = calls.optJSONObject(index)?.optString("id").orEmpty()
                            if (id.isBlank() || !pending.add(id)) return false
                        }
                    }
                }
                "tool" -> if (!pending.remove(message.toolCallId)) return false
                "user" -> if (pending.isNotEmpty()) return false
            }
        }
        return pending.isEmpty()
    }

    fun remapId(branchId: String, messageId: String): String {
        if (messageId.isBlank()) return messageId
        val prefix = listOf("assistant-", "user-", "greeting-").firstOrNull(messageId::startsWith).orEmpty()
        return "$prefix$branchId-${messageId.removePrefix(prefix)}"
    }
}
