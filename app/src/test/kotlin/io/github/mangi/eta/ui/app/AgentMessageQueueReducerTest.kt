package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageQueueReducerTest {

    @Test
    fun enqueueAppendsInOrderAndClearsCurrentDraft() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val initial = baseState(
            isStreaming = true,
            input = "草稿",
            images = listOf(image),
        )

        val firstState = AgentMessageQueueReducer.enqueue(
            state = initial,
            item = QueuedMessageUi(id = "q-1", content = "第一条", pendingImages = listOf(image)),
        )
        val secondState = AgentMessageQueueReducer.enqueue(
            state = firstState,
            item = QueuedMessageUi(id = "q-2", content = "第二条"),
        )

        assertEquals("", secondState.input)
        assertTrue(secondState.pendingImages.isEmpty())
        assertEquals(listOf("q-1", "q-2"), secondState.queuedMessages.map { it.id })
    }

    @Test
    fun immediateInterruptPreemptsQueueHeadAndDemotesPreviousInterrupt() {
        val state = baseState(isStreaming = true).let {
            val s1 = AgentMessageQueueReducer.enqueue(it, QueuedMessageUi(id = "q-1", content = "普通排队"))
            AgentMessageQueueReducer.enqueue(s1, QueuedMessageUi(id = "q-2", content = "第一次插话", immediateInterrupt = true))
        }
        assertEquals(listOf("q-2", "q-1"), state.queuedMessages.map { it.id })
        assertTrue(state.queuedMessages[0].immediateInterrupt)

        val next = AgentMessageQueueReducer.enqueue(
            state = state,
            item = QueuedMessageUi(id = "q-3", content = "第二次插话", immediateInterrupt = true),
        )
        assertEquals(listOf("q-3", "q-2", "q-1"), next.queuedMessages.map { it.id })
        assertTrue(next.queuedMessages[0].immediateInterrupt)
        assertFalse(next.queuedMessages[1].immediateInterrupt)
    }

    @Test
    fun promoteToInterruptMovesItemToFrontWhenStreamingAndPopsImmediatelyWhenIdle() {
        val queued = listOf(
            QueuedMessageUi(id = "q-1", content = "第一条"),
            QueuedMessageUi(id = "q-2", content = "第二条"),
        )
        val streamingState = baseState(isStreaming = true).copy(queuedMessages = queued)
        val (promotedStreaming, promotedItem) = AgentMessageQueueReducer.promoteToInterrupt(streamingState, "q-2")
        assertNotNull(promotedItem)
        assertEquals(listOf("q-2", "q-1"), promotedStreaming.queuedMessages.map { it.id })
        assertTrue(promotedStreaming.queuedMessages.first().immediateInterrupt)

        val idleState = baseState(isStreaming = false).copy(queuedMessages = queued)
        val (afterIdlePop, poppedItem) = AgentMessageQueueReducer.promoteToInterrupt(idleState, "q-2")
        assertNotNull(poppedItem)
        assertFalse(poppedItem!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterIdlePop.queuedMessages.map { it.id })
    }

    @Test
    fun recallToDraftMergesWithUnsentTextAndAttachmentsAndBumpsSyncVersion() {
        val existingImage = PendingImageUi(id = "img-1", uri = "u1", dataUrl = "d1", mimeType = "image/png")
        val queuedImage = PendingImageUi(id = "img-2", uri = "u2", dataUrl = "d2", mimeType = "image/png")
        val queuedFile = PendingFileReferenceUi(
            id = "file-1",
            reference = AgentFileReference(
                displayName = "doc.txt",
                absolutePath = "/sdcard/doc.txt",
                kind = AgentFileReferenceKind.File,
            ),
        )
        val state = baseState(
            isStreaming = true,
            input = "",
            images = listOf(existingImage),
        ).copy(
            queuedMessages = listOf(
                QueuedMessageUi(
                    id = "q-1",
                    content = "撤回的内容",
                    pendingImages = listOf(existingImage, queuedImage),
                    pendingFileReferences = listOf(queuedFile),
                ),
                QueuedMessageUi(id = "q-2", content = "保留的内容"),
            ),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(
            state = state,
            currentInputText = "正在敲的半句话",
            id = "q-1",
        )
        assertNotNull(recalled)
        assertEquals("正在敲的半句话\n撤回的内容", recalled!!.input)
        assertEquals(listOf("img-1", "img-2"), recalled.pendingImages.map { it.id })
        assertEquals(listOf("file-1"), recalled.pendingFileReferences.map { it.id })
        assertEquals(listOf("q-2"), recalled.queuedMessages.map { it.id })
        assertEquals(state.inputSyncVersion + 1, recalled.inputSyncVersion)
    }

    @Test
    fun pollNextToSendAfterRunRespectsUserStopExceptForImmediateInterrupt() {
        val normalQueue = listOf(
            QueuedMessageUi(id = "q-1", content = "第一条"),
            QueuedMessageUi(id = "q-2", content = "第二条"),
        )
        val state = baseState(isStreaming = false).copy(queuedMessages = normalQueue)

        // 用户主动点击停止按钮：队列保留，不自动发出
        val (stoppedState, stoppedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = true,
        )
        assertNull(stoppedNext)
        assertEquals(2, stoppedState.queuedMessages.size)

        // 自然完成：自动弹出队首第 1 条
        val (completedState, completedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = false,
        )
        assertEquals("q-1", completedNext?.id)
        assertEquals(listOf("q-2"), completedState.queuedMessages.map { it.id })

        // 插话触发的中止：即使运行因取消结束，队首插话项仍立即弹出发送
        val interruptState = state.copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-interrupt", content = "立即插话", immediateInterrupt = true),
                QueuedMessageUi(id = "q-1", content = "第一条"),
            ),
        )
        val (afterInterruptState, interruptNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = interruptState,
            stoppedByUser = true,
        )
        assertEquals("q-interrupt", interruptNext?.id)
        assertFalse(interruptNext!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterInterruptState.queuedMessages.map { it.id })
    }

    private fun baseState(
        isStreaming: Boolean,
        input: String = "",
        images: List<PendingImageUi> = emptyList(),
    ) = AgentChatUiState(
        messages = emptyList(),
        input = input,
        isStreaming = isStreaming,
        thinkingEnabled = true,
        pendingImages = images,
    )
}
