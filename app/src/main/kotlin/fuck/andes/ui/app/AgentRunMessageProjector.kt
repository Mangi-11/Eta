package fuck.andes.ui.app

import android.os.SystemClock
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi

internal class AgentRunMessageProjector(
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val thinkingStartedAt = mutableMapOf<String, Long>()

    fun appendReasoningDelta(
        runId: String,
        round: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val thinkingId = thinkingMessageId(runId, round)
        val elapsedSeconds = elapsedSeconds(thinkingId)
        var updated = false
        val next = messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next.insertBeforeAssistant(
            runId = runId,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = delta,
                isStreaming = true,
                elapsedSeconds = elapsedSeconds,
                collapsed = false,
            )
        )
    }

    fun ensureCompletedThinking(
        runId: String,
        round: Int,
        content: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round)
        if (messages.any { it is ThinkingMessageUi && it.id == thinkingId }) {
            return finalizeThinkingRound(runId, round, messages)
        }

        return messages.insertBeforeAssistant(
            runId = runId,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds(thinkingId),
                collapsed = true,
            )
        )
    }

    fun finalizeThinking(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ThinkingMessageUi && message.id.startsWith("$runId-thinking-")) {
                message.finished()
            } else {
                message
            }
        }

    fun finalizeThinkingRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round)
        return messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                message.finished()
            } else {
                message
            }
        }
    }

    fun startTool(
        runId: String,
        event: AgentEvent.ToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.insertBeforeAssistantOnce(
            runId = runId,
            message = ToolActivityMessageUi(
                id = toolActivityMessageId(runId, event.toolCallId),
                toolName = event.name,
                status = ToolActivityStatusUi.Running,
                argumentsSummary = event.argsPreview,
            )
        )

    fun finishTool(
        runId: String,
        event: AgentEvent.ToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.toolCallId)
        val status = if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
            ToolActivityStatusUi.Failed
        } else {
            ToolActivityStatusUi.Success
        }
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(
                    status = status,
                    resultSummary = event.resultSummary,
                    imageCount = event.imageCount,
                )
            } else {
                message
            }
        }
    }

    fun failRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Failed,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun clearRun(runId: String) {
        thinkingStartedAt.keys.removeAll { it.startsWith("$runId-thinking-") }
    }

    private fun ThinkingMessageUi.finished(): ThinkingMessageUi =
        copy(
            isStreaming = false,
            elapsedSeconds = thinkingStartedAt[id]?.let { startedAt ->
                ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
            } ?: elapsedSeconds,
            collapsed = true,
        )

    private fun elapsedSeconds(thinkingId: String): Int {
        val startedAt = thinkingStartedAt.getOrPut(thinkingId, nowElapsedRealtime)
        return ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
    }

    private fun List<AgentChatMessageUi>.insertBeforeAssistantOnce(
        runId: String,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        if (any { it.id == message.id }) return this
        return insertBeforeAssistant(runId, message)
    }

    private fun List<AgentChatMessageUi>.insertBeforeAssistant(
        runId: String,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        val assistantIndex = indexOfFirst { it is AgentMessageUi && it.id == "assistant-$runId" }
        return if (assistantIndex >= 0) {
            toMutableList().apply { add(assistantIndex, message) }
        } else {
            this + message
        }
    }

    private fun thinkingMessageId(runId: String, round: Int): String =
        "$runId-thinking-$round"

    private fun toolActivityMessageId(runId: String, toolCallId: String): String =
        "$runId-tool-${toolCallId.ifBlank { "unknown" }}"
}

private const val MAX_TOOL_RESULT_PREVIEW_CHARS = 48
