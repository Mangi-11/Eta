package io.github.mangi.eta.ui.components.chat

/**
 * 为聊天舞台生成独立的 Compose 状态边界。
 *
 * 会话切换时必须重建 LazyListState、流式 Markdown 状态和底部跟随任务，
 * 否则旧会话的列表位置可能被新会话复用。
 */
internal fun chatConversationCompositionKey(conversationId: String?): String =
    conversationId?.let { "conversation:$it" } ?: "conversation:draft"
