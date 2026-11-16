package io.github.mangi.eta.ui.voice

import android.Manifest
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaSpeechPhase
import io.github.mangi.eta.agent.voice.SpeechAudioCapture
import io.github.mangi.eta.agent.voice.SpeechInputController
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.ui.app.AgentAppTheme
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SpeechDictationGestureTest {
    @Test
    fun holdCancelZoneRequiresLeavingSafetyMargin() {
        assertFalse(isInHoldCancelZone(Offset(50f, 50f), width = 100f, height = 100f, cancelMarginPx = 90f))
        assertFalse(isInHoldCancelZone(Offset(50f, -80f), width = 100f, height = 100f, cancelMarginPx = 90f))
        assertTrue(isInHoldCancelZone(Offset(50f, -95f), width = 100f, height = 100f, cancelMarginPx = 90f))
        assertTrue(isInHoldCancelZone(Offset(-95f, 50f), width = 100f, height = 100f, cancelMarginPx = 90f))
        assertTrue(isInHoldCancelZone(Offset(195f, 50f), width = 100f, height = 100f, cancelMarginPx = 90f))
        assertTrue(isInHoldCancelZone(Offset(50f, 195f), width = 100f, height = 100f, cancelMarginPx = 90f))
    }

    @Test
    fun tapStartsTwoStepDictationAndRoutesToDraftCallback() = withHarness { harness ->
        val center = harness.micBounds().center
        val downTime = SystemClock.uptimeMillis()
        harness.dispatchTouch(MotionEvent.ACTION_DOWN, downTime, center.x, center.y)
        harness.advance(2) // ~32ms < 150ms
        harness.dispatchTouch(MotionEvent.ACTION_UP, downTime, center.x, center.y)
        harness.advance(6)

        val session = harness.sessions.last()
        assertTrue(harness.controller.state.value.active)
        assertFalse(harness.controller.state.value.holdToTalk)
        assertEquals(EtaSpeechPhase.LISTENING, harness.controller.state.value.phase)

        // 再轻点一次按钮完成两步听写
        val finishCenter = harness.micBounds(R.string.speech_dictation_finish).center
        val finishDownTime = SystemClock.uptimeMillis()
        harness.dispatchTouch(MotionEvent.ACTION_DOWN, finishDownTime, finishCenter.x, finishCenter.y)
        harness.advance(2)
        harness.dispatchTouch(MotionEvent.ACTION_UP, finishDownTime, finishCenter.x, finishCenter.y)
        harness.advance(4)

        assertTrue(session.finished)
        session.complete("草稿文字")
        harness.advance(6)

        assertEquals(listOf("草稿文字"), harness.tapResults)
        assertTrue(harness.holdResults.isEmpty())
    }

    @Test
    fun holdSpeakAndReleaseInsideSafetyZoneSendsAutomatically() = withHarness { harness ->
        val center = harness.micBounds().center
        val downTime = SystemClock.uptimeMillis()
        harness.dispatchTouch(MotionEvent.ACTION_DOWN, downTime, center.x, center.y)
        harness.advance(14) // ~224ms > 150ms

        val session = harness.sessions.last()
        assertTrue(harness.controller.state.value.active)
        assertTrue(harness.controller.state.value.holdToTalk)
        assertFalse(harness.holdCancelling)
        assertEquals(EtaSpeechPhase.LISTENING, harness.controller.state.value.phase)

        harness.dispatchTouch(MotionEvent.ACTION_UP, downTime, center.x, center.y)
        harness.advance(4)

        assertTrue(session.finished)
        assertEquals(EtaSpeechPhase.RECOGNIZING, harness.controller.state.value.phase)
        session.complete("按住直接发送")
        harness.advance(6)

        assertEquals(listOf("按住直接发送"), harness.holdResults)
        assertTrue(harness.tapResults.isEmpty())
        assertFalse(harness.controller.state.value.active)
    }

    @Test
    fun holdDragOutToCancelZoneAndReleaseAbortsWithoutSending() = withHarness { harness ->
        val center = harness.micBounds().center
        val downTime = SystemClock.uptimeMillis()
        harness.dispatchTouch(MotionEvent.ACTION_DOWN, downTime, center.x, center.y)
        harness.advance(14)
        val session = harness.sessions.last()
        assertTrue(harness.controller.state.value.holdToTalk)

        // 向上滑动 80dp（超过 36dp 取消阈值）进入取消区
        val cancelY = center.y - harness.dp(80f)
        harness.dispatchTouch(MotionEvent.ACTION_MOVE, downTime, center.x, cancelY)
        harness.advance(6)
        assertTrue(harness.holdCancelling)
        assertTrue(harness.hasText(harness.activity.getString(R.string.speech_hold_release_to_cancel)))

        harness.dispatchTouch(MotionEvent.ACTION_UP, downTime, center.x, cancelY)
        harness.advance(6)

        assertTrue(session.canceled)
        assertFalse(harness.holdCancelling)
        assertFalse(harness.controller.state.value.active)
        assertTrue(harness.holdResults.isEmpty())
        assertTrue(harness.tapResults.isEmpty())
    }

    @Test
    fun holdDragOutThenReturnToSafetyZoneStillSendsOnRelease() = withHarness { harness ->
        val center = harness.micBounds().center
        val downTime = SystemClock.uptimeMillis()
        harness.dispatchTouch(MotionEvent.ACTION_DOWN, downTime, center.x, center.y)
        harness.advance(14)
        val session = harness.sessions.last()

        val cancelY = center.y - harness.dp(80f)
        harness.dispatchTouch(MotionEvent.ACTION_MOVE, downTime, center.x, cancelY)
        harness.advance(4)
        assertTrue(harness.holdCancelling)

        harness.dispatchTouch(MotionEvent.ACTION_MOVE, downTime, center.x, center.y)
        harness.advance(4)
        assertFalse(harness.holdCancelling)

        harness.dispatchTouch(MotionEvent.ACTION_UP, downTime, center.x, center.y)
        harness.advance(4)

        assertTrue(session.finished)
        assertFalse(session.canceled)
        session.complete("滑回后再发送")
        harness.advance(6)

        assertEquals(listOf("滑回后再发送"), harness.holdResults)
    }

    private fun withHarness(block: (Harness) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(activity).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val harness = Harness(activity)
        try {
            harness.advance(20)
            block(harness)
        } finally {
            harness.close()
            controller.pause().stop().destroy()
        }
    }

    private class Harness(val activity: ComponentActivity) {
        val tapResults = mutableListOf<String>()
        val holdResults = mutableListOf<String>()
        val sessions = mutableListOf<FakeStreamSession>()
        var holdCancelling by mutableStateOf(false)
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)

        val controller = run {
            var pending: FakeStreamSession? = null
            SpeechInputController(
                context = activity,
                scope = scope,
                createRecorder = { FakeStreamSession().also { pending = it; sessions += it } },
                recognizeStream = { _, _, _, onReady, onPreview, onEndpoint, _ ->
                    val session = checkNotNull(pending)
                    pending = null
                    session.attach(onReady, onPreview, onEndpoint)
                    session.awaitTranscript()
                },
                loadSettings = { SpeechSettings(recognitionEnabled = true) },
                loadCredentials = { SpeechCredentials(qwenAsr = "test-key", doubaoAsr = "test-key") },
                onHoldResult = holdResults::add,
                onResult = tapResults::add,
            )
        }

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End,
                            ) {
                                SpeechInputFeedback(controller, holdCancelling = holdCancelling)
                                SpeechDictationButton(
                                    controller = controller,
                                    enabled = true,
                                    holdCancelling = holdCancelling,
                                    onHoldCancellingChange = { holdCancelling = it },
                                )
                            }
                        }
                    }
                }
            }
            activity.setContentView(view)
        }

        fun close() {
            controller.cancel()
            recomposer.cancel()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun advance(frames: Int) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                root().measureAndLayoutForTest()
            }
        }

        fun dp(value: Float): Float = value * root().density.density

        fun dispatchTouch(action: Int, downTime: Long, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                root().view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            advance(1)
        }

        fun micBounds(descRes: Int = R.string.speech_dictation) =
            requireNotNull(findByDescription(activity.getString(descRes))) {
                "未找到描述为 ${activity.getString(descRes)} 的麦克风节点"
            }.boundsInRoot

        fun hasText(text: String): Boolean {
            fun find(node: SemanticsNode): Boolean {
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) {
                    return true
                }
                return node.children.any(::find)
            }
            return find(root().semanticsOwner.unmergedRootSemanticsNode)
        }

        private fun findByDescription(desc: String): SemanticsNode? {
            fun find(node: SemanticsNode): SemanticsNode? {
                if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(desc) == true) {
                    return node
                }
                return node.children.firstNotNullOfOrNull(::find)
            }
            return find(root().semanticsOwner.unmergedRootSemanticsNode)
        }

        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("未找到 Compose Root")

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findRoot(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
    }

    private class FakeStreamSession : SpeechAudioCapture {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val stopSignal = CompletableDeferred<Unit>()
        private val transcript = CompletableDeferred<String>()
        var finished = false
            private set
        var canceled = false
            private set

        fun attach(
            onReady: () -> Unit,
            @Suppress("UNUSED_PARAMETER") onPreview: (String) -> Unit,
            @Suppress("UNUSED_PARAMETER") onEndpoint: () -> Unit,
        ) {
            onReady()
        }

        override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
            onReady()
            try {
                stopSignal.await()
            } finally {
                frames.close()
            }
        }

        fun complete(text: String) {
            if (!canceled) transcript.complete(text)
        }

        suspend fun awaitTranscript(): String = transcript.await()

        override fun finish() {
            finished = true
            stopSignal.complete(Unit)
        }

        override fun cancel() {
            canceled = true
            finish()
            frames.cancel()
        }
    }
}
