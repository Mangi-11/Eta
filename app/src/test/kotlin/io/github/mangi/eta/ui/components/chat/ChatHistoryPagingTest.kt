package io.github.mangi.eta.ui.components.chat

import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatHistoryPagingTest {
    @Test
    fun actualUpwardScrollLoadsOnePageAndPreservesTheVisibleMessageOffset() {
        val controller = Robolectric.buildActivity(HistoryActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            activity.render()
            assertEquals(0, activity.loadRequests)
            activity.scrollState.requestScrollToItem(2, 12)
            activity.render()
            assertEquals(0, activity.loadRequests)
            val before = activity.readingPosition()

            activity.paging.scrollConnection.onPreScroll(Offset(0f, 10f), NestedScrollSource.UserInput)
            activity.render()
            assertEquals(1, activity.loadRequests)
            activity.completePage()
            repeat(3) { activity.render() }

            assertEquals(before, activity.readingPosition())
            assertEquals(1, activity.loadRequests)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    class HistoryActivity : ComponentActivity() {
        lateinit var scrollState: LazyListState
        internal lateinit var paging: ChatHistoryPaging
        var loadRequests = 0
        private var startIndex by mutableStateOf(100)
        private var entries by mutableStateOf(entriesFrom(100))
        private var loadState by mutableStateOf(ConversationHistoryLoadState.Idle)
        private var keepBottomAnchored by mutableStateOf(true)
        private lateinit var view: ComposeView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            view = ComposeView(this)
            setContentView(view)
            view.setContent {
                scrollState = rememberLazyListState(initialFirstVisibleItemIndex = entries.size + 1)
                paging = rememberChatHistoryPaging(
                    scrollState, entries, startIndex, entries.size, true, loadState,
                    enabled = true, canReleasePages = true, keepBottomAnchored = keepBottomAnchored,
                    onBottomAnchorChanged = { keepBottomAnchored = it },
                    onLoadEarlier = {
                        loadRequests += 1
                        loadState = ConversationHistoryLoadState.Loading
                    },
                    onViewportChanged = {},
                )
                LazyColumn(state = scrollState, modifier = Modifier.nestedScroll(paging.scrollConnection)) {
                    item(key = ChatHistoryBoundaryKey) { Spacer(Modifier.height(48.dp)) }
                    items(entries, key = { it.key }) { Spacer(Modifier.fillMaxWidth().height(48.dp)) }
                    item(key = ChatBottomSentinelKey) { Spacer(Modifier.height(1.dp)) }
                }
            }
        }

        fun completePage() {
            startIndex -= 50
            entries = entriesFrom(startIndex)
            loadState = ConversationHistoryLoadState.Idle
        }

        fun readingPosition(): Pair<Any, Int> = scrollState.layoutInfo.visibleItemsInfo.first {
            it.key != ChatHistoryBoundaryKey && it.key != ChatBottomSentinelKey
        }.let { it.key to it.offset }

        fun render() {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 320, 640)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }

        private fun entriesFrom(start: Int): List<AgentTimelineEntry> =
            (start until 150).map { AgentTimelineEntry.Message(UserMessageUi("message-$it", "$it")) }
    }
}
