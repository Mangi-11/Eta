package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayHistoryProjection
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

internal object RoleplayConversationReducer {
    fun restorePendingRewrite(state: AgentChatUiState, runId: String, messageId: String?): AgentChatUiState =
        if (state.roleplay == null || messageId == null || runId in state.appliedRuntimeRunIds) state else state.copy(
            roleplayMessages = state.roleplayMessages.copy(
                pendingRewrites = state.roleplayMessages.pendingRewrites + (runId to messageId),
            ),
        )

    fun linkRun(state: AgentChatUiState, runId: String): AgentChatUiState {
        if (state.roleplay == null) return state
        val links = state.roleplayMessages.links.toMutableMap()
        val journal = state.journal.ifEmpty { state.history }
        journal.filter { it.messageId.isNotBlank() }.forEach { entry ->
            if (entry.role == "user") {
                state.messages.filterIsInstance<UserMessageUi>().firstOrNull { it.id == entry.messageId }?.let {
                    links[it.id] = RoleplayMessageLink(entry.messageId)
                }
            } else if (entry.role == "assistant") {
                state.messages.filterIsInstance<AgentMessageUi>()
                    .filter { it.id == entry.messageId || it.id.startsWith("${entry.messageId}-") }
                    .forEachIndexed { index, message ->
                        // 展示窗口可能从同批正文中间开始，既有块序号不能按窗口下标重新编号。
                        links.putIfAbsent(message.id, RoleplayMessageLink(entry.messageId, index))
                    }
            }
        }
        val fallback = state.messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == "assistant-$runId" }
        val last = journal.lastOrNull { it.role == "assistant" && it.messageId.startsWith("assistant-$runId-") }
        if (fallback != null && last != null) links[fallback.id] = RoleplayMessageLink(last.messageId)
        return decorate(state.copy(roleplayMessages = state.roleplayMessages.copy(links = links)))
    }

    fun decorate(state: AgentChatUiState): AgentChatUiState {
        if (state.roleplay == null) return state
        return state.copy(messages = state.messages.map { message ->
            if (message !is AgentMessageUi) message else {
                val revision = state.roleplayMessages.revisions[message.id]
                message.copy(
                    characterEditable = message.id in state.roleplayMessages.links,
                    candidateCount = revision?.candidates?.size ?: 1,
                    selectedCandidate = revision?.selected ?: 0,
                )
            }
        })
    }

    fun edit(state: AgentChatUiState, messageId: String, content: String): AgentChatUiState? {
        if (state.roleplay == null || messageId !in state.roleplayMessages.links || content.isBlank()) return null
        val original = state.messageContent(messageId) ?: return null
        val previous = state.roleplayMessages.revisions[messageId]
            ?: RoleplayReplyRevision(original, listOf(original), 0)
        val updated = previous.copy(candidates = previous.candidates + content, selected = previous.candidates.size)
        return applyRevision(state, messageId, updated)
    }

    fun select(state: AgentChatUiState, messageId: String, index: Int): AgentChatUiState? {
        val revision = state.roleplayMessages.revisions[messageId] ?: return null
        if (index !in revision.candidates.indices) return null
        return applyRevision(state, messageId, revision.copy(selected = index))
    }

    private fun applyRevision(state: AgentChatUiState, id: String, revision: RoleplayReplyRevision): AgentChatUiState? {
        val updated = state.copy(
            messages = state.messages.map {
                when {
                    it.id == id && it is UserMessageUi -> it.copy(content = revision.content, isEdited = true)
                    it.id == id && it is AgentMessageUi -> it.copy(content = revision.content)
                    else -> it
                }
            },
            roleplayMessages = state.roleplayMessages.copy(revisions = state.roleplayMessages.revisions + (id to revision)),
        )
        // 编辑旧正文后从完整历史重建模型投影，原 journal 和真实工具批次保持不变。
        val history = projectJournal(updated) ?: return null
        return decorate(updated.copy(history = history))
    }

    fun requiredOriginalContentIds(state: AgentChatUiState): Set<String> {
        if (state.roleplay == null) return emptySet()
        val visibleIds = state.messages.asSequence()
            .filter { it is UserMessageUi || it is AgentMessageUi }
            .mapTo(mutableSetOf()) { it.id }
        val relevantIds = visibleIds + state.roleplayMessages.revisions.keys + state.roleplayMessages.pendingRewrites.values
        val transcriptIds = relevantIds.mapNotNullTo(mutableSetOf()) {
            state.roleplayMessages.links[it]?.transcriptMessageId
        }
        return state.roleplayMessages.links.entries.asSequence()
            .filter { (id, link) ->
                link.transcriptMessageId in transcriptIds && id !in visibleIds && id !in state.roleplayMessages.revisions
            }
            .mapTo(mutableSetOf()) { it.key }
    }

    fun projectJournal(state: AgentChatUiState): List<ConversationMessage>? =
        RoleplayHistoryProjection.project(state.journal.ifEmpty { state.history }, state.roleplayMessages) {
            state.messageContent(it)
        }

    fun rewriteHistory(state: AgentChatUiState, messageId: String): List<ConversationMessage>? {
        val link = state.roleplayMessages.links[messageId] ?: return null
        val history = projectJournal(state) ?: return null
        val index = history.indexOfFirst { it.messageId == link.transcriptMessageId }
        if (index < 0) return null
        val siblings = state.roleplayMessages.links.entries
            .filter { it.value.transcriptMessageId == link.transcriptMessageId && it.value.blockOrder < link.blockOrder }
            .sortedBy { it.value.blockOrder }
            .map { (id, _) -> state.messageContent(id) ?: return null }
        return history.take(index) + if (siblings.isEmpty()) emptyList() else listOf(
            ConversationMessage("assistant", siblings.joinToString("\n\n")),
        )
    }

    private fun AgentChatUiState.messageContent(id: String): String? =
        roleplayMessages.revisions[id]?.content ?: when (val message = messages.firstOrNull { it.id == id }) {
            is UserMessageUi -> message.content
            is AgentMessageUi -> message.content
            else -> roleplayOriginalContents[id]
        }

    fun applyRewrite(state: AgentChatUiState, runId: String, result: AgentRuntimeWire.RunResult): AgentChatUiState {
        if (runId in state.appliedRuntimeRunIds) return state
        val expectedTarget = state.roleplayMessages.pendingRewrites[runId]
        val target = result.rewriteTargetMessageId ?: expectedTarget
        val targetMatches = expectedTarget == null || target == expectedTarget
        val edited = if (result.ok && result.content.isNotBlank() && target != null && targetMatches) edit(state, target, result.content) else null
        val base = edited ?: state
        val messages = if (edited != null) base.messages else base.messages + SystemNoticeMessageUi(
            id = "rewrite-result-$runId", code = SystemNoticeCode.RuntimeFailed,
            detail = result.error ?: "重新生成未完成，已保留原回复。",
        )
        return base.copy(
            messages = messages,
            roleplayMessages = base.roleplayMessages.copy(pendingRewrites = base.roleplayMessages.pendingRewrites - runId),
            appliedRuntimeRunIds = base.appliedRuntimeRunIds + runId,
            isStreaming = false,
        )
    }
}
