package io.github.mangi.eta.ui.state

/** 打开外部入口归档会话的请求：会话 ID 由界面侧按来源与会话键推导。 */
data class ArchivedConversationRequest(
    val source: String,
    val conversationKey: String,
)
