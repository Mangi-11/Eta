package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi

/**
 * 会话内排队发送（Queue）与插话发送（Interrupt）的纯状态转换器。
 *
 * - 排队发送追加到队尾，等待当前回合自然结束后按 FIFO 顺序自动发出。
 * - 插话发送（[QueuedMessageUi.immediateInterrupt]）置入队首并请求中止当前运行，
 *   待当前运行收尾落盘后立即优先发出，避免取消与新运行之间出现历史写入竞态。
 * - 用户主动点击停止按钮时保留队列不自动发出，防止想停下时后续排队指令继续跑起来。
 */
internal object AgentMessageQueueReducer {

    fun enqueue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val nextQueue = if (item.immediateInterrupt) {
            listOf(item) + state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        } else {
            state.queuedMessages + item
        }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = nextQueue,
        )
    }

    /**
     * 将队列中的指定消息提升为立即插话。
     *
     * - 若当前仍在流式运行（非压缩）：将其移至队首并标记 [QueuedMessageUi.immediateInterrupt]，等待当前运行取消后自动发出。
     * - 若当前已空闲：直接从队列弹出并返回，由调用方立即发起新运行。
     */
    fun promoteToInterrupt(
        state: AgentChatUiState,
        id: String,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state to null
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return if (state.isStreaming && !state.isCompacting) {
            val promoted = target.copy(immediateInterrupt = true)
            state.copy(queuedMessages = listOf(promoted) + rest) to promoted
        } else {
            state.copy(queuedMessages = rest) to target.copy(immediateInterrupt = false)
        }
    }

    /**
     * 将排队中的消息撤回输入框继续编辑；若输入框已有未发送草稿则保留并合并，不覆盖用户正在输入的内容。
     */
    fun recallToDraft(
        state: AgentChatUiState,
        currentInputText: String,
        id: String,
    ): AgentChatUiState? {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return null
        val rest = state.queuedMessages.filterNot { it.id == id }
        val mergedInput = when {
            currentInputText.isBlank() -> target.content
            target.content.isBlank() -> currentInputText
            else -> currentInputText.trimEnd() + "\n" + target.content
        }
        val mergedImages = (state.pendingImages + target.pendingImages).distinctBy { it.id }
        val mergedFiles = (state.pendingFileReferences + target.pendingFileReferences).distinctBy { it.id }
        return state.copy(
            input = mergedInput,
            pendingImages = mergedImages,
            pendingFileReferences = mergedFiles,
            queuedMessages = rest,
            inputSyncVersion = state.inputSyncVersion + 1,
        )
    }

    fun remove(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.filterNot { it.id == id },
        )
    }

    fun clearInterruptFlags(state: AgentChatUiState): AgentChatUiState {
        if (state.queuedMessages.none { it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { it.copy(immediateInterrupt = false) },
        )
    }

    /**
     * 当前运行收尾后决定是否自动弹出下一条排队消息：
     * - 队首为 [QueuedMessageUi.immediateInterrupt] 时，无论本次是否因取消结束，都立即弹出执行。
     * - 用户主动点击停止按钮（[stoppedByUser]）时，保留剩余队列，不自动发出。
     * - 其余情况（正常结束）弹出队首第一条消息自动发出。
     */
    fun pollNextToSendAfterRun(
        state: AgentChatUiState,
        stoppedByUser: Boolean,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val first = state.queuedMessages.firstOrNull() ?: return state to null
        if (!first.immediateInterrupt && stoppedByUser) {
            return clearInterruptFlags(state) to null
        }
        val remaining = state.queuedMessages.drop(1)
        return state.copy(queuedMessages = remaining) to first.copy(immediateInterrupt = false)
    }
}
