package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.Serializable

/** 正文修订独立于原始执行历史；关联使用运行产生的消息标识，不依赖正文相等。 */
@Serializable
internal data class RoleplayMessageState(
    val links: Map<String, RoleplayMessageLink> = emptyMap(),
    val revisions: Map<String, RoleplayReplyRevision> = emptyMap(),
    val pendingRewrites: Map<String, String> = emptyMap(),
)

@Serializable
internal data class RoleplayMessageLink(val transcriptMessageId: String, val blockOrder: Int = 0)

@Serializable
internal data class RoleplayReplyRevision(
    val original: String,
    val candidates: List<String>,
    val selected: Int,
) {
    val content: String get() = candidates.getOrElse(selected) { original }
}
