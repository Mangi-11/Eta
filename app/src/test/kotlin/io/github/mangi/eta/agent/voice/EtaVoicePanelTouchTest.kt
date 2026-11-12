package io.github.mangi.eta.agent.voice

import android.app.Application
import android.graphics.Insets
import android.graphics.Bitmap
import android.graphics.Canvas
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
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
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
    fun streamingReplyGrowsMonotonicallyWithoutPostCompletionBounceOrDrop() = withPanel { panel ->
        for (lineCount in listOf(4, 10, 18)) {
            panel.state.value = EtaVoiceUiState(
                messages = listOf(
                    AgentMessageUi(
                        id = "reply",
                        content = (1..lineCount).joinToString("\n") { "Eta 流式回复第 $it 行" },
                        renderMarkdown = false,
                        isStreaming = true,
                    ),
                ),
                phase = EtaVoicePhase.PROCESSING,
            )
            panel.advance(12)
        }
        panel.advance(60)
        val topBeforeComplete = panel.handleBounds().top
        panel.state.value = EtaVoiceUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "reply",
                    content = (1..18).joinToString("\n") { "Eta 流式回复第 $it 行" },
                    renderMarkdown = false,
                    isStreaming = false,
                ),
            ),
            phase = EtaVoicePhase.READY,
        )
        var minTopAfterComplete = topBeforeComplete
        var maxTopAfterComplete = topBeforeComplete
        repeat(90) {
            panel.advance(1)
            val top = panel.handleBounds().top
            minTopAfterComplete = minOf(minTopAfterComplete, top)
            maxTopAfterComplete = maxOf(maxTopAfterComplete, top)
        }
        assertTrue(
            "流式结束后不应再大幅向上弹起或向下回落：before=$topBeforeComplete min=$minTopAfterComplete max=$maxTopAfterComplete",
            maxTopAfterComplete <= topBeforeComplete + 1f,
        )
    }

    @Test
    fun disabledRecognitionWaitsForBothWakeAndContentRevealBeforeKeyboard() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advance(10)
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
            assertTrue("显现应形成可感知的独立阶段", panel.revealDurationMs >= 200)
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
    fun replyBackgroundCoversNavigationAreaWhileInputAvoidsIt() {
        for (style in AppearanceUiStyle.entries) {
            for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                withPanel(appearance = AppearanceSettings(uiStyle = style, themeMode = theme)) { panel ->
                    panel.showReply()
                    for (navigationBottom in listOf(48, 144)) {
                        panel.setBottomInsets(navigationBottom = navigationBottom)
                        panel.advance(3)
                        panel.assertNavigationBackgroundCovered(navigationBottom)
                    }
                }
            }
        }
    }

    @Test
    fun keyboardAndNavigationInsetsAreNotAddedTogether() = withPanel { panel ->
        panel.showReply()
        panel.setBottomInsets(navigationBottom = 48)
        panel.advance(3)
        val baseline = panel.textInputBottom()
        for (imeBottom in listOf(120, 240, 360, 240, 0)) {
            panel.setBottomInsets(navigationBottom = 48, imeBottom = imeBottom)
            panel.advance(3)
            assertEquals(baseline - (maxOf(48, imeBottom) - 48), panel.textInputBottom(), 1f)
        }
    }

    @Test
    fun inputAvoidsSideNavigationAndDisplayCutouts() = withPanel { panel ->
        panel.showReply()
        panel.setBottomInsets(navigationBottom = 0, navigationRight = 96, cutoutLeft = 72)
        panel.advance(3)
        panel.assertHorizontalInsetsRespected(left = 72, right = 96)
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

    @Test
    fun voiceCapsuleSwitchesSmoothlyBetweenListeningAndKeyboard() = withPanel { panel ->
        panel.speech.value = EtaSpeechState(phase = EtaSpeechPhase.LISTENING)
        panel.advance(30)
        assertFalse(panel.hasTextInput())
        assertTrue(panel.hasActionDescription(R.string.voice_use_keyboard))
        assertTrue(panel.hasActionDescription(R.string.voice_finish_speech))
        panel.assertSuggestionCapsulesTopUnclipped()
        panel.clickActionDescription(R.string.voice_use_keyboard)
        assertEquals(1, panel.keyboardSwitches)
        panel.speech.value = EtaSpeechState()
        panel.advance(30)
        assertTrue(panel.hasTextInput())
        assertTrue(panel.hasMicrophone())
        panel.assertSuggestionCapsulesTopUnclipped()
    }

    @Test
    fun sheetTopEdgeCoversLeftAndRightHorizontalSpanWithoutDiagonalClip() = withPanel { panel ->
        panel.showReply()
        panel.assertSheetTopSpanUnclipped()
    }

    private fun withPanel(
        recognitionEnabled: Boolean = true,
        initialFrames: Int = 180,
        appearance: AppearanceSettings = AppearanceSettings(),
        block: (Panel) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val panel = Panel(controller.get(), recognitionEnabled, appearance) { controller.windowFocusChanged(true) }
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
        appearance: AppearanceSettings,
        private val onGainWindowFocus: () -> Unit,
    ) {
        val state = mutableStateOf(EtaVoiceUiState())
        val speech = mutableStateOf(EtaSpeechState())
        val recognitionEnabled = mutableStateOf(recognitionEnabled)
        private val entranceComplete = mutableStateOf(false)
        private val inputFocusRequestKey = mutableStateOf(-1)
        var entrances = 0
        var contentReveals = 0
        private var wakeFinishedAt = 0L
        var revealDurationMs = 0L
        private var inputActivated = false
        var keyboardShows = 0
        var keyboardSwitches = 0
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
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalSoftwareKeyboardController provides keyboard,
                    ) {
                        EtaVoicePanel(
                            speechLevel = { 0f },
                            state = state.value,
                            input = "",
                            speech = speech.value,
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
                            onOpenSpeechSettings = {},
                            onKeyboard = { keyboardSwitches++ },
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
            shadowOf(android.os.Looper.getMainLooper()).idle()
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

        fun setBottomInsets(navigationBottom: Int, imeBottom: Int = 0, navigationRight: Int = 0, cutoutLeft: Int = 0) {
            root().view.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, navigationRight, navigationBottom))
                .setVisible(WindowInsets.Type.navigationBars(), true)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(cutoutLeft, 0, 0, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .setVisible(WindowInsets.Type.ime(), imeBottom > 0)
                .build())
        }

        fun assertHorizontalInsetsRespected(left: Int, right: Int) {
            val bounds = requireNotNull(findTextInput(root().semanticsOwner.unmergedRootSemanticsNode)).boundsInRoot
            assertTrue("输入框必须避开左侧挖孔", bounds.left >= left)
            assertTrue("输入框必须避开侧边导航栏", bounds.right <= root().view.width - right)
        }

        fun assertNavigationBackgroundCovered(navigationBottom: Int) {
            val view = root().view
            assertTrue("输入框必须避开导航区域", textInputBottom() < view.height - navigationBottom)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val inset = dp(4f).toInt()
                val contentColor = bitmap.getPixel(view.width / 2, view.height - navigationBottom - inset)
                val navigationColor = bitmap.getPixel(view.width / 2, view.height - inset)
                assertEquals("回复面板底色必须实际绘制为不透明", 255, contentColor ushr 24)
                assertEquals("浮窗底色应连续覆盖导航区域，不能露出下层窗口", contentColor, navigationColor)
            } finally {
                bitmap.recycle()
            }
        }

        fun assertSuggestionCapsulesTopUnclipped() {
            val view = root().view
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                for (label in listOf("总结屏幕内容", "解释当前页面", "提取关键信息")) {
                    val node = requireNotNull(findByText(label)) { "未找到建议胶囊：$label" }
                    val bounds = node.boundsInRoot
                    val sampleX = bounds.center.x.toInt()
                    val topPixel = bitmap.getPixel(sampleX, (bounds.top + dp(3f)).toInt())
                    val bottomPixel = bitmap.getPixel(sampleX, (bounds.bottom - dp(3f)).toInt())
                    assertTrue(
                        "建议胶囊顶部不应被斜向裁切：$label topAlpha=${topPixel ushr 24}",
                        (topPixel ushr 24) > 235,
                    )
                    assertTrue(
                        "建议胶囊上下边缘亮度应连续一致：$label top=${Integer.toHexString(topPixel)} bottom=${Integer.toHexString(bottomPixel)}",
                        kotlin.math.abs(((topPixel shr 16) and 0xFF) - ((bottomPixel shr 16) and 0xFF)) <= 8,
                    )
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun assertSheetTopSpanUnclipped() {
            val view = root().view
            val handle = handleBounds()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val sampleY = (handle.top + dp(6f)).toInt()
                val leftPixel = bitmap.getPixel(dp(44f).toInt(), sampleY)
                val rightPixel = bitmap.getPixel((view.width - dp(44f)).toInt(), sampleY)
                assertEquals("回复面板左上圆角内侧应完全覆盖底色", 255, leftPixel ushr 24)
                assertEquals("回复面板右上圆角内侧应完全覆盖底色", 255, rightPixel ushr 24)
            } finally {
                bitmap.recycle()
            }
        }

        private fun findByText(text: String): SemanticsNode? {
            fun find(node: SemanticsNode): SemanticsNode? {
                val texts = node.config.getOrNull(SemanticsProperties.Text)
                if (texts?.any { it.text == text } == true &&
                    node.config.getOrNull(SemanticsProperties.Role) != null
                ) {
                    return node
                }
                return node.children.firstNotNullOfOrNull(::find)
            }
            return find(root().semanticsOwner.unmergedRootSemanticsNode)
                ?: run {
                    fun findTextParent(node: SemanticsNode): SemanticsNode? {
                        if (node.children.any { child ->
                                child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                            }
                        ) {
                            return node
                        }
                        return node.children.firstNotNullOfOrNull(::findTextParent)
                    }
                    findTextParent(root().semanticsOwner.unmergedRootSemanticsNode)
                }
        }

        fun hasMicrophone(): Boolean = hasActionDescription(R.string.voice_tap_to_speak)

        fun hasActionDescription(resId: Int): Boolean = findByDescription(resId) != null

        fun clickActionDescription(resId: Int) {
            val node = requireNotNull(findByDescription(resId))
            assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
        }

        private fun findByDescription(resId: Int): SemanticsNode? {
            val description = activity.getString(resId)
            fun find(node: SemanticsNode): SemanticsNode? {
                if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) {
                    return node
                }
                return node.children.firstNotNullOfOrNull(::find)
            }
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
