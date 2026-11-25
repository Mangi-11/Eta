package io.github.mangi.eta.ui.components.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal const val ChatHistoryBoundaryKey = "agent-chat-earlier-messages"
internal const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"

/** 每次真实上翻最多消费一次预取机会；数据插入和重新测量不产生新的阅读请求。 */
internal class HistoryScrollDemand {
    private var pending by mutableStateOf(false)

    fun onScroll(verticalDistance: Float) {
        if (verticalDistance != 0f) pending = verticalDistance > 0f
    }

    fun canLoad(
        measured: Boolean,
        firstVisibleItemIndex: Int,
        hasEarlier: Boolean,
        enabled: Boolean,
        loadState: ConversationHistoryLoadState,
    ): Boolean = pending && measured && firstVisibleItemIndex <= 3 && hasEarlier &&
        enabled && loadState == ConversationHistoryLoadState.Idle

    fun consume() {
        pending = false
    }
}

internal data class ChatHistoryPaging(
    val scrollConnection: NestedScrollConnection,
    val retry: () -> Unit,
)

private data class HistoryPagingConfiguration(
    val entries: List<AgentTimelineEntry>,
    val messageStartIndex: Int,
    val messageCount: Int,
    val hasEarlier: Boolean,
    val loadState: ConversationHistoryLoadState,
    val enabled: Boolean,
    val canReleasePages: Boolean,
    val keepBottomAnchored: Boolean,
)

private data class HistoryReadingAnchor(val key: String, val offset: Int, val startIndex: Int)
private class HistoryReadingPosition {
    var anchor: HistoryReadingAnchor? = null
}

@Composable
internal fun rememberChatHistoryPaging(
    scrollState: LazyListState,
    entries: List<AgentTimelineEntry>,
    messageStartIndex: Int,
    messageCount: Int,
    hasEarlier: Boolean,
    loadState: ConversationHistoryLoadState,
    enabled: Boolean,
    canReleasePages: Boolean,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onLoadEarlier: () -> Unit,
    onViewportChanged: (Boolean) -> Unit,
): ChatHistoryPaging {
    val configuration by rememberUpdatedState(HistoryPagingConfiguration(
        entries, messageStartIndex, messageCount, hasEarlier, loadState,
        enabled, canReleasePages, keepBottomAnchored,
    ))
    val loadEarlier by rememberUpdatedState(onLoadEarlier)
    val changeBottomAnchor by rememberUpdatedState(onBottomAnchorChanged)
    val viewportChanged by rememberUpdatedState(onViewportChanged)
    val demand = remember { HistoryScrollDemand() }
    val readingPosition = remember { HistoryReadingPosition() }

    fun captureAnchor(): HistoryReadingAnchor? =
        scrollState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != ChatHistoryBoundaryKey && it.key != ChatBottomSentinelKey
        }?.let { HistoryReadingAnchor(it.key as String, it.offset, configuration.messageStartIndex) }

    val requestEarlier = {
        val current = configuration
        if (current.hasEarlier && current.enabled && current.loadState != ConversationHistoryLoadState.Loading) {
            demand.consume()
            readingPosition.anchor = captureAnchor()
            viewportChanged(false)
            changeBottomAnchor(false)
            loadEarlier()
        }
    }
    val request by rememberUpdatedState(requestEarlier)
    val connection = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    demand.onScroll(available.y)
                    if (available.y > 0f) viewportChanged(false)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 惯性滚动可继续预取；requestScrollToItem 的布局恢复不经过此路径。
                demand.onScroll(consumed.y)
                if (readingPosition.anchor != null) readingPosition.anchor = captureAnchor()
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(scrollState) {
        snapshotFlow {
            val current = configuration
            val layout = scrollState.layoutInfo
            demand.canLoad(
                measured = layout.totalItemsCount > 0 && layout.viewportEndOffset > layout.viewportStartOffset,
                firstVisibleItemIndex = scrollState.firstVisibleItemIndex,
                hasEarlier = current.hasEarlier,
                enabled = current.enabled,
                loadState = current.loadState,
            )
        }.distinctUntilChanged().collect { ready -> if (ready) request() }
    }

    LaunchedEffect(messageStartIndex) {
        val anchor = readingPosition.anchor
        if (anchor != null && messageStartIndex < anchor.startIndex) {
            readingPosition.anchor = null
            // 手指或惯性仍在滚动时由 LazyColumn 的稳定 key 维持位置，不取消正在进行的滚动。
            if (!scrollState.isScrollInProgress && !configuration.keepBottomAnchored) {
                val index = entries.indexOfFirst { it.key == anchor.key }
                if (index >= 0) scrollState.requestScrollToItem(index + if (hasEarlier) 1 else 0, -anchor.offset)
            }
        }
    }

    LaunchedEffect(scrollState) {
        snapshotFlow {
            val current = configuration
            val layout = scrollState.layoutInfo
            val atLatest = current.enabled && current.canReleasePages && current.keepBottomAnchored &&
                layout.totalItemsCount > 0 && layout.viewportEndOffset > layout.viewportStartOffset &&
                !scrollState.canScrollForward && !scrollState.isScrollInProgress
            atLatest to current.messageCount
        }.distinctUntilChanged().collect { (atLatest, _) -> viewportChanged(atLatest) }
    }

    return ChatHistoryPaging(connection, requestEarlier)
}
