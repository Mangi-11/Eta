package io.github.mangi.eta.agent.voice

import android.app.Application
import android.graphics.Insets
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.AgentMessageUi
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtaVoicePanelTouchTest {
    @Test
    fun handleCanDragDownAfterEmptyEntryReceivesReply() = withPanel { panel ->
        panel.showReply()
        val before = panel.handleBounds()
        panel.dragBy(200f)
        val after = panel.handleBounds()
        assertTrue(
            "收到回复后把手应能向下拖动：before=$before after=$after",
            after.top > before.top + panel.dp(30f),
        )
        assertEquals(0, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun handleCanHandoffAfterReplyCompletesWithoutChangingBaseHeight() = withPanel { panel ->
        panel.showReply(processing = true)
        panel.state.value = panel.state.value.copy(phase = EtaVoicePhase.READY)
        panel.advance()
        panel.dragBy(-80f)
        assertEquals(1, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun cancelledHandleDragDoesNotHandoff() = withPanel { panel ->
        panel.showReply()
        panel.dragBy(-80f, cancel = true)
        assertEquals(0, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun disabledRecognitionWaitsForBothWakeAndContentRevealBeforeKeyboard() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advance(20)
            assertEquals(0, panel.entrances)
            assertEquals(0, panel.keyboardShows)
            assertFalse(panel.hasTextInput())
            assertFalse(panel.hasMicrophone())
            panel.advanceUntil { panel.entrances == 1 }
            panel.advance(1)
            assertEquals(1, panel.entrances)
            assertTrue(panel.hasTextInput())
            assertEquals("输入栏尚在显现时不能交接焦点，实际显现耗时=${panel.revealDurationMs}", 0, panel.contentReveals)
            assertEquals(0, panel.keyboardShows)
            panel.advanceUntil { panel.contentReveals == 1 }
            assertTrue("显现应形成可感知的独立阶段", panel.revealDurationMs >= 300)
            panel.advance(8)
            assertEquals(1, panel.contentReveals)
            assertTrue("入场结束后输入框应取得焦点", panel.textInputFocused())
            assertTrue("输入框取得焦点后应请求键盘", panel.keyboardShows > 0)
            assertFalse(panel.hasMicrophone())
            assertEquals(0, panel.microphoneStarts)
            panel.recognitionEnabled.value = true
            panel.advance()
            assertTrue(panel.hasMicrophone())
            assertEquals(1, panel.entrances)
            assertEquals(1, panel.contentReveals)
        }

    @Test
    fun revealedInputStillWaitsForWindowFocus() = withPanel(recognitionEnabled = false) { panel ->
        assertEquals(1, panel.contentReveals)
        assertEquals(0, panel.keyboardShows)
        panel.gainWindowFocus()
        panel.advance(8)
        assertTrue(panel.textInputFocused())
        assertTrue(panel.keyboardShows > 0)
    }

    @Test
    fun dismissingDuringTextRevealDoesNotRequestKeyboardLater() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advanceUntil { panel.entrances == 1 }
            panel.advance(1)
            assertEquals(1, panel.entrances)
            assertEquals(0, panel.contentReveals)
            panel.close()
            panel.advance()
            assertEquals(0, panel.contentReveals)
            assertEquals(0, panel.keyboardShows)
        }

    @Test
    fun manualInputFocusDuringRevealCancelsAutomaticKeyboardRequest() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advanceUntil { panel.entrances == 1 }
            panel.advance(1)
            panel.focusTextInput()
            panel.advance(3)
            assertTrue(panel.textInputFocused())
            val shows = panel.keyboardShows
            panel.advance()
            assertEquals(1, panel.contentReveals)
            assertEquals(shows, panel.keyboardShows)
        }

    @Test
    fun textInputTracksImeInsetsWithoutApplyingAnotherAnimation() =
        withPanel(recognitionEnabled = false) { panel ->
            panel.setImeBottom(0)
            panel.advance(2)
            val baseline = panel.textInputBottom()
            for (imeBottom in listOf(120, 240, 360, 240, 0)) {
                panel.setImeBottom(imeBottom)
                panel.advance(2)
                assertEquals("输入栏应与当前键盘边界保持同步", baseline - imeBottom, panel.textInputBottom(), 1f)
            }
        }

    @Test
    fun dismissingDuringEntranceDoesNotRequestKeyboardLater() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.advance(20)
            panel.close()
            panel.advance()
            assertEquals(0, panel.entrances)
            assertEquals(0, panel.keyboardShows)
        }

    private fun withPanel(
        recognitionEnabled: Boolean = true,
        initialFrames: Int = 180,
        block: (Panel) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val panel = Panel(controller.get(), recognitionEnabled) { controller.windowFocusChanged(true) }
        try {
            panel.advance(initialFrames)
            block(panel)
        } finally {
            panel.close()
            controller.pause().stop().destroy()
        }
    }

    private class Panel(
        val activity: ComponentActivity,
        recognitionEnabled: Boolean,
        private val onGainWindowFocus: () -> Unit,
    ) {
        val state = mutableStateOf(EtaVoiceUiState())
        val recognitionEnabled = mutableStateOf(recognitionEnabled)
        private val entranceComplete = mutableStateOf(false)
        private val inputFocusRequestKey = mutableStateOf(-1)
        var entrances = 0
        var contentReveals = 0
        private var wakeFinishedAt = 0L
        var revealDurationMs = 0L
        private var inputActivated = false
        var keyboardShows = 0
        var microphoneStarts = 0
        private val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShows++ }
            override fun hide() = Unit
        }
        var opened = 0
        var closed = 0
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalSoftwareKeyboardController provides keyboard,
                    ) {
                        EtaVoicePanel(
                            speechLevel = { 0f },
                            state = state.value,
                            input = "",
                            speech = EtaSpeechState(),
                            recognitionEnabled = this.recognitionEnabled.value,
                            entranceComplete = entranceComplete.value,
                            onEntranceFinished = {
                                entrances++
                                wakeFinishedAt = SystemClock.uptimeMillis()
                                entranceComplete.value = true
                            },
                            onContentReady = {
                                contentReveals++
                                revealDurationMs = SystemClock.uptimeMillis() - wakeFinishedAt
                                if (!this.recognitionEnabled.value && !inputActivated) inputFocusRequestKey.value++
                            },
                            onInputFocus = { inputActivated = true },
                            onMicrophone = { microphoneStarts++ },
                            onCancelSpeech = {},
                            onFinishSpeech = {},
                            onDownloadModel = {},
                            onOpenSpeechSettings = {},
                            onKeyboard = {},
                            inputFocusRequestKey = inputFocusRequestKey.value,
                            canOpenConversation = state.value.phase == EtaVoicePhase.READY &&
                                state.value.messages.isNotEmpty(),
                            handoffRunning = false,
                            exitRequested = false,
                            onInputChange = {},
                            onSuggestionClick = {},
                            onSubmit = {},
                            onStop = {},
                            onClose = { closed++ },
                            onOpenConversation = { opened++ },
                        )
                    }
                }
            }
            activity.setContentView(view)
        }

        fun close() {
            recomposer.cancel()
            scope.cancel()
        }

        fun showReply(processing: Boolean = false) {
            state.value = EtaVoiceUiState(
                messages = listOf(
                    AgentMessageUi(
                        id = "reply",
                        content = (1..70).joinToString("\n") { "Eta 测试回复 $it" },
                        renderMarkdown = false,
                    ),
                ),
                phase = if (processing) EtaVoicePhase.PROCESSING else EtaVoicePhase.READY,
            )
            advance()
        }

        fun advance(frames: Int = 180) {
            // 手动驱动重组帧和布局，使无真实显示器的触摸测试不依赖后台线程时序。
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                root().measureAndLayoutForTest()
            }
        }

        fun advanceUntil(condition: () -> Boolean) {
            repeat(180) {
                if (condition()) return
                advance(1)
            }
            assertTrue("动画应在有界帧数内完成", condition())
        }

        fun dp(value: Float): Float = value * root().density.density

        fun gainWindowFocus() = onGainWindowFocus()

        fun hasTextInput(): Boolean = findTextInput(root().semanticsOwner.unmergedRootSemanticsNode) != null

        fun textInputFocused(): Boolean = findTextInput(root().semanticsOwner.unmergedRootSemanticsNode)
            ?.config?.getOrNull(SemanticsProperties.Focused) == true

        fun focusTextInput() {
            val node = requireNotNull(findTextInput(root().semanticsOwner.unmergedRootSemanticsNode))
            assertTrue(node.config[SemanticsActions.RequestFocus].action?.invoke() == true)
        }

        fun textInputBottom(): Float = requireNotNull(findTextInput(root().semanticsOwner.unmergedRootSemanticsNode))
            .boundsInRoot.bottom

        fun setImeBottom(bottom: Int) {
            root().view.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsets.Type.ime(), bottom > 0)
                .build())
        }

        fun hasMicrophone(): Boolean {
            val description = activity.getString(R.string.voice_tap_to_speak)
            fun find(node: SemanticsNode): Boolean =
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true ||
                    node.children.any(::find)
            return find(root().semanticsOwner.unmergedRootSemanticsNode)
        }

        private fun findTextInput(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.EditableText) != null) return node
            return node.children.firstNotNullOfOrNull(::findTextInput)
        }

        fun handleBounds(): Rect = findHandle(root().semanticsOwner.unmergedRootSemanticsNode)?.boundsInRoot
            ?: error("未找到顶部把手")

        fun dragBy(distanceDp: Float, cancel: Boolean = false) {
            val view = root().view
            val bounds = handleBounds()
            val node = findHandle(root().semanticsOwner.unmergedRootSemanticsNode)!!
            assertTrue(
                "把手应已完成布局：bounds=$bounds size=${node.size} " +
                    "position=${node.positionInRoot} view=${view.width}x${view.height}",
                bounds.width > 0 && bounds.height > 0,
            )
            val start = bounds.center
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, start.x, y, 0)
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            send(MotionEvent.ACTION_DOWN, start.y)
            repeat(10) { index ->
                send(MotionEvent.ACTION_MOVE, start.y + dp(distanceDp) * (index + 1) / 10f)
            }
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, start.y + dp(distanceDp))
            advance()
        }

        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("未找到 Compose Root")

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        private fun findHandle(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.TestTag) == "eta_assistant_drag_handle") return node
            for (child in node.children) findHandle(child)?.let { return it }
            return null
        }
    }
}
