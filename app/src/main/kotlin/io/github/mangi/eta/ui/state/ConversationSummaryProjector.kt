package io.github.mangi.eta.ui.state

import android.content.Context
import android.text.format.DateFormat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.contentMatches

/**
 * 将内存中的会话集合投影为侧栏摘要列表，按置顶与更新时间排序，并按（查询词, 状态引用）缓存正文检索结果。
 */
internal class ConversationSummaryProjector(
    private val appContext: Context,
    private val noticeText: (SystemNoticeCode) -> String,
    private val guiStatus: (String) -> GuiControlStatus?,
) {
    // 内容匹配按（查询词, 会话状态引用）缓存：刷新摘要时未变化的会话不重复全文扫描。
    private val contentMatchCache = mutableMapOf<String, ContentMatchCacheEntry>()

    fun clearCache() {
        contentMatchCache.clear()
    }

    fun evict(conversationId: String) {
        contentMatchCache.remove(conversationId)
    }

    fun project(
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
        pinnedIds: Set<String>,
        searchQuery: String,
        persistedSearchMatches: Set<String>,
    ): List<ConversationSummaryUi> {
        val summaries = conversationsById.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { (id, _) ->
                    id in pinnedIds
                }.thenByDescending { (id, _) ->
                    updatedAt[id] ?: 0L
                },
            )
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = titles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> noticeText(lastMessage.code)
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        appContext.getString(R.string.time_now)
                    } else {
                        updatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = updatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    isPinned = id in pinnedIds,
                    characterName = state.roleplay?.characterName ?: state.conversationCharacterName,
                    characterAvatarPath = state.roleplay?.avatarPath ?: state.conversationCharacterAvatarPath,
                    isActiveRun = state.isStreaming,
                    runStatus = when (guiStatus(id)) {
                        GuiControlStatus.Waiting -> appContext.getString(R.string.run_waiting_for_gui)
                        GuiControlStatus.UserPaused -> appContext.getString(R.string.run_gui_user_paused)
                        else -> null
                    },
                )
            }
        val query = searchQuery.trim()
        if (query.isBlank()) return summaries
        contentMatchCache.keys.retainAll(conversationsById.keys)
        return summaries.filter { summary ->
            summary.title.contains(query, ignoreCase = true) ||
                summary.characterName?.contains(query, ignoreCase = true) == true ||
                summary.preview.contains(query, ignoreCase = true) ||
                summary.id in persistedSearchMatches ||
                conversationContentMatches(conversationsById, summary.id, query)
        }
    }

    private fun conversationContentMatches(
        conversationsById: Map<String, AgentChatHomeUiState>,
        conversationId: String,
        query: String,
    ): Boolean {
        val state = conversationsById[conversationId] ?: return false
        val cached = contentMatchCache[conversationId]
        if (cached != null && cached.query == query && cached.state === state) {
            return cached.matches
        }
        val matches = state.contentMatches(query, noticeText)
        contentMatchCache[conversationId] = ContentMatchCacheEntry(query, state, matches)
        return matches
    }

    private data class ContentMatchCacheEntry(
        val query: String,
        val state: AgentChatHomeUiState,
        val matches: Boolean,
    )

    private companion object {
        const val MAX_PREVIEW_CHARS = 48
    }
}
