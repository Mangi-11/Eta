package io.github.mangi.eta.data.model

/** 分支边界只读取消息结构，避免为定位旧轮次加载全部正文和图片。 */
internal data class ConversationBranchMessage(
    val id: String,
    val sortIndex: Int,
    val type: String,
    val hasContent: Boolean,
    val noticeCode: String = "",
)
