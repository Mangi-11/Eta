package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

/** 将 Runtime outbox 的结果幂等折叠回 App 会话。 */
internal object AgentPendingResultRecovery {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        result: AgentRuntimeWire.RunResult,
        promptSupplement: AgentUiHandoffPayload.Supplement? = null,
        supplements: List<AgentUiHandoffPayload.Supplement>,
    ): Outcome {
        if (result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || runId in state.roleplayMessages.pendingRewrites) {
            return Outcome(RoleplayConversationReducer.applyRewrite(state, runId, result), runId in state.appliedRuntimeRunIds)
        }
        val stateWithSupplements = state.copy(messages = mergeSupplements(
            runId, listOfNotNull(promptSupplement) + supplements, state.messages,
        ))
        val content = result.content.takeIf { result.ok && it.isNotBlank() }
        val history = AgentRuntimeHistoryReducer.apply(
            state = stateWithSupplements,
            runId = runId,
            snapshot = result.contextSnapshot?.let { snapshot ->
                snapshot.copy(consumedTranscriptMessages = snapshot.consumedTranscriptMessages?.let {
                    it + if (promptSupplement != null) 1 else 0
                })
            },
            retainPendingSupplements = !result.ok || result.contextSnapshot != null,
            additions = listOfNotNull(
                promptSupplement?.let { supplement ->
                    AgentModelClient.buildUserHistoryMessage(
                        text = supplement.text,
                        images = emptyList(),
                    ).copy(messageId = supplementMessageId(runId, supplement.index))
                }
            ) + result.transcript,
        )
        if (history.alreadyApplied) return Outcome(state, alreadyApplied = true)
        if (result.operation == AgentRuntimeWire.OP_COMPACT) {
            return Outcome(history.state.copy(isStreaming = false, isCompacting = false,
                messages = AgentRunMessageProjector.mergeCompactionResultNotice(
                    runId = runId,
                    messages = state.messages,
                    ok = result.ok,
                    detail = if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩失败",
                )), false)
        }

        val messagesWithResult = stateWithSupplements.messages
            .filterNot { it is SystemNoticeMessageUi && it.id == interruptedNoticeId(runId) }
            .toMutableList()
            .also { messages ->
            val assistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages, includeNotices = true)
            val resultId = AgentRunMessageProjector.resultFallbackId(runId, messages)
            val targetRound = (messages.getOrNull(assistantIndex) as? AgentMessageUi)
                ?.id
                ?.assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            val completedMessage: AgentChatMessageUi = when {
                content != null -> AgentMessageUi(
                    id = resultId,
                    content = if (sameRoundBlocks > 1) {
                        (messages[assistantIndex] as AgentMessageUi).content.ifBlank { content }
                    } else {
                        content
                    },
                    isStreaming = false,
                    renderMarkdown = true,
                )
                result.ok -> SystemNoticeMessageUi(
                    id = resultId,
                    code = SystemNoticeCode.EmptyResult,
                )
                else -> SystemNoticeMessageUi(
                    id = resultId,
                    code = SystemNoticeCode.RuntimeFailed,
                    detail = result.error,
                )
            }
            if (assistantIndex >= 0) {
                messages[assistantIndex] = completedMessage.copyWithId(messages[assistantIndex].id)
            } else {
                messages += completedMessage
            }
        }
        return Outcome(
            state = history.state.copy(
                messages = mergeSupplements(
                    runId = runId,
                    supplements = listOfNotNull(promptSupplement) + supplements,
                    messages = messagesWithResult,
                    beforeLatestAssistant = true,
                ),
                history = history.state.history,
                appliedRuntimeRunIds = history.state.appliedRuntimeRunIds,
                isStreaming = false,
            ).let { RoleplayConversationReducer.linkRun(it, runId) },
            alreadyApplied = false,
        )
    }

    private fun AgentChatMessageUi.copyWithId(id: String): AgentChatMessageUi = when (this) {
        is AgentMessageUi -> copy(id = id)
        is SystemNoticeMessageUi -> copy(id = id)
        else -> this
    }

    fun mergeSupplements(
        runId: String,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        messages: List<AgentChatMessageUi>,
        beforeLatestAssistant: Boolean = false,
    ): List<AgentChatMessageUi> {
        var updated = messages
        supplements.sortedBy { it.index }.forEach { supplement ->
            val id = supplementMessageId(runId, supplement.index)
            if (updated.any { it.id == id }) return@forEach
            val userMessage = UserMessageUi(id = id, content = supplement.text)
            val assistantIndex = updated.indexOfLast {
                it is AgentMessageUi &&
                    it.isAssistantForRun(runId) &&
                    (beforeLatestAssistant || it.isStreaming)
            }
            updated = if (assistantIndex >= 0) {
                updated.toMutableList().also { it.add(assistantIndex, userMessage) }
            } else {
                updated + userMessage
            }
        }
        return updated
    }

    private fun AgentChatMessageUi.isAssistantForRun(runId: String): Boolean =
        this is AgentMessageUi &&
            (id == "assistant-$runId" || id.startsWith(assistantMessagePrefix(runId)))

    private fun assistantMessagePrefix(runId: String): String = "assistant-$runId-"

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    internal fun supplementMessageId(runId: String, index: Int): String =
        "user-$runId-supplement-$index"

    private fun interruptedNoticeId(runId: String): String = "interrupted-$runId"

}
