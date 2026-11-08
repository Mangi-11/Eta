package io.github.mangi.eta.agent.roleplay

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage

/** 正文修订只改变模型投影，原始 journal 和已经发生的工具批次保持不变。 */
internal object RoleplayHistoryProjection {
    fun project(
        journal: List<ConversationMessage>,
        state: RoleplayMessageState,
        content: (String) -> String?,
    ): List<ConversationMessage>? = buildList {
        val grouped = state.links.entries.groupBy { it.value.transcriptMessageId }
        if (journal.any { it.contextSummary }) add(ConversationMessage(
            role = "system", content = "早期历史仅保留摘要；正文修订只应用于仍有原文的消息，不能恢复已缺失的历史。",
        ))
        journal.forEach { entry ->
            val links = grouped[entry.messageId].orEmpty().sortedBy { it.value.blockOrder }
            if (links.none { it.key in state.revisions }) add(entry) else {
                // 缺失块意味着本轮投影无法完整重建，不能把剩余片段冒充完整正文。
                val body = links.map { (id, _) -> content(id) ?: return null }.joinToString("\n\n")
                if (entry.role == "user") add(ConversationMessage(
                    role = "system",
                    content = "以下历史用户正文经用户修订；后续已有工具记录来自原请求，不能视为修订后请求已被执行。",
                ))
                add(entry.copy(content = body, contentJson = ""))
            }
        }
    }
}
