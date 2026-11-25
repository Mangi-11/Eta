package io.github.mangi.eta.ui.components.pane

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPaneStateTest {
    @Test
    fun interruptedOpeningResynchronizesClosedAndCanOpenAgain() = verifyInterruptedTransition(
        initial = ConversationPaneAnchor.Closed,
        target = ConversationPaneAnchor.Open,
    )

    @Test
    fun interruptedClosingResynchronizesOpenAndCanCloseAgain() = verifyInterruptedTransition(
        initial = ConversationPaneAnchor.Open,
        target = ConversationPaneAnchor.Closed,
    )

    private fun verifyInterruptedTransition(initial: ConversationPaneAnchor, target: ConversationPaneAnchor) = runBlocking {
        withTimeout(5_000) {
            val anchors = DraggableAnchors {
                ConversationPaneAnchor.Closed at 0f
                ConversationPaneAnchor.Open at 320f
            }
            val state = AnchoredDraggableState(initialValue = initial, anchors = anchors)
            val dragging = mutableStateOf(false)
            val observed = mutableListOf<ConversationPaneAnchor>()
            var visible = initial == ConversationPaneAnchor.Open
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                conversationPaneSettledValues(state) { dragging.value }.collect {
                    observed += it
                    visible = it == ConversationPaneAnchor.Open
                }
            }
            val clock = TestFrameClock()
            visible = target == ConversationPaneAnchor.Open
            val animation = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                state.animateTo(target, tween(1000, easing = LinearEasing))
            }
            try {
                clock.send(0L)
                yield()
                clock.send(100_000_000L)
                yield()
                flushSnapshots()
                assertTrue(state.offset > 0f && state.offset < 320f)

                dragging.value = true
                flushSnapshots()
                state.anchoredDrag(dragPriority = MutatePriority.UserInput) {
                    dragTo(anchors.positionOf(initial))
                }
                animation.join()
                flushSnapshots()
                assertTrue(animation.isCancelled)
                assertEquals(initial, state.settledValue)
                assertEquals(listOf(initial), observed)
                assertEquals(target == ConversationPaneAnchor.Open, visible)

                dragging.value = false
                flushSnapshots()
                assertEquals(listOf(initial, initial), observed)
                assertEquals(initial == ConversationPaneAnchor.Open, visible)

                visible = target == ConversationPaneAnchor.Open
                state.snapTo(target)
                flushSnapshots()
                assertEquals(listOf(initial, initial, target), observed)
                assertEquals(target == ConversationPaneAnchor.Open, visible)
            } finally {
                animation.cancelAndJoin()
                observer.cancelAndJoin()
            }
        }
    }

    private suspend fun flushSnapshots() {
        Snapshot.sendApplyNotifications()
        yield()
    }

    private class TestFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)
        fun send(time: Long) { check(frames.trySend(time).isSuccess) }
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }
}
