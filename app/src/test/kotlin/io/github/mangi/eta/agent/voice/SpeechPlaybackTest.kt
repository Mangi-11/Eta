package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechPlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outputs = mutableListOf<FakeOutput>()
    private var canceled = 0
    private val playback = SpeechPlaybackController(context, scope,
        streamSynthesize = null,
        synthesize = { _, _, _, audio ->
            try { audio(byteArrayOf(1, 2)); awaitCancellation() }
            finally { canceled++ }
        },
        createOutput = { FakeOutput().also(outputs::add) },
    )
    @After fun close() { playback.stop(); scope.cancel() }

    private fun start(id: String) {
        playback.speak(id, "回答正文", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        val deadline = System.nanoTime() + 3_000_000_000
        while (playback.state.value.loading && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertNull(playback.state.value.error)
        assertFalse(playback.state.value.loading)
        assertEquals(id, playback.state.value.messageId)
    }

    @Test fun newPlaybackCancelsOldSynthesisAndClosesItsPlayer() {
        start("old")
        start("new")
        assertEquals(1, canceled)
        assertTrue(outputs.first().closed)
        assertFalse(outputs.last().closed)
        playback.stop()
        assertEquals(2, canceled)
        assertTrue(outputs.last().closed)
        assertNull(playback.state.value.messageId)
    }

    @Test fun microphoneLeaseInterruptsPlayback() {
        start("answer")
        val microphone = SpeechAudioLease(context) {}
        try {
            microphone.acquire(playback = false)
            assertTrue(outputs.single().closed)
            assertEquals(1, canceled)
            assertNull(playback.state.value.messageId)
        } finally { microphone.close() }
    }

    @Test fun microphoneWinsWhileOldPlaybackIsStillPreparing() {
        playback.speak("preparing", "尚未合成的回答", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        val microphone = SpeechAudioLease(context) {}
        try {
            microphone.acquire(playback = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(playback.state.value.messageId)
            assertTrue(outputs.all { it.closed })
        } finally { microphone.close() }
    }

    @Test fun ownerCancellationClosesAudioWithoutExplicitStop() {
        start("answer")
        scope.cancel()
        // 播放端在 IO 线程，取消后需等它退出再回到主线程收尾。
        val deadline = System.nanoTime() + 3_000_000_000
        while (!outputs.single().closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue(outputs.single().closed)
        assertNull(playback.state.value.messageId)
    }

    @Test fun unpluggingHeadphonesStopsSynthesisAndPlayback() {
        start("answer")
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(outputs.single().closed)
        assertNull(playback.state.value.messageId)
    }

    @Test fun successfulSynthesisFinishesInputBeforeWaitingForPlayback() {
        val output = FakeOutput()
        val controller = SpeechPlaybackController(context, scope,
            streamSynthesize = null,
            synthesize = { _, _, _, audio -> audio(byteArrayOf(1, 2)) },
            createOutput = { output },
        )
        try {
            controller.speak("short", "简短回答", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
            val deadline = System.nanoTime() + 3_000_000_000
            while (!output.closed && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(1)
            }
            assertTrue(output.finished)
            assertTrue(output.closed)
            assertNull(controller.state.value.error)
        } finally { controller.stop() }
    }

    @Test fun synthesisIsNotPacedByPlaybackAndPrefetchesBoundedChunks() {
        val writing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val synthesized = java.util.concurrent.atomic.AtomicInteger()
        val output = object : SpeechAudioOutput {
            @Volatile var finished = false
            @Volatile var closed = false
            override fun write(bytes: ByteArray) { writing.countDown(); release.await() }
            override fun finish() { finished = true }
            override fun drained() = true
            override fun close() { closed = true }
        }
        val controller = SpeechPlaybackController(context, scope,
            streamSynthesize = null,
            synthesize = { _, _, _, audio -> synthesized.incrementAndGet(); audio(byteArrayOf(1, 2)) },
            createOutput = { output },
        )
        try {
            val text = "很长的回答。".repeat(250)
            val chunkCount = SpeechText.chunks(SpeechText.readable(text)).size
            assertTrue(chunkCount > 2)
            controller.speak("long", text, SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
            fun idleUntil(condition: () -> Boolean) {
                val deadline = System.nanoTime() + 3_000_000_000
                while (!condition() && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
            }
            idleUntil { writing.count == 0L && synthesized.get() >= 2 }
            assertEquals(2, synthesized.get())
            release.countDown()
            idleUntil { output.closed }
            assertEquals(chunkCount, synthesized.get())
            assertTrue(output.finished)
            assertNull(controller.state.value.error)
        } finally { release.countDown(); controller.stop() }
    }

    private fun speakUntilClosed(
        controller: SpeechPlaybackController,
        output: FakeOutput,
        text: String,
        provider: TtsProvider = TtsProvider.QWEN,
    ) {
        controller.speak("stream", text, SpeechSettings(tts = provider),
            SpeechCredentials(qwenTts = "test-key", geminiTts = "test-key", minimaxTts = "test-key"))
        val deadline = System.nanoTime() + 3_000_000_000
        while (!output.closed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    @Test fun streamingSessionReceivesWholeAnswerOnce() {
        val output = FakeOutput()
        val sessions = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val segments = java.util.concurrent.atomic.AtomicInteger()
        val controller = SpeechPlaybackController(context, scope,
            streamSynthesize = { _, _, chunks, audio -> sessions += chunks; audio(byteArrayOf(1, 2)) },
            synthesize = { _, _, _, _ -> segments.incrementAndGet() },
            createOutput = { output },
        )
        try {
            val text = "很长的回答。".repeat(250)
            speakUntilClosed(controller, output, text)
            assertEquals(1, sessions.size)
            assertEquals(SpeechText.chunks(SpeechText.readable(text)), sessions.single())
            assertEquals(0, segments.get())
            assertTrue(output.finished)
            assertNull(controller.state.value.error)
        } finally { controller.stop() }
    }

    @Test fun streamingFailureBeforeAudioFallsBackToSegments() {
        val output = FakeOutput()
        val segments = java.util.concurrent.atomic.AtomicInteger()
        val controller = SpeechPlaybackController(context, scope,
            streamSynthesize = { _, _, _, _ -> throw SpeechFailure(SpeechErrorCode.NETWORK, "unavailable") },
            synthesize = { _, _, _, audio -> segments.incrementAndGet(); audio(byteArrayOf(1, 2)) },
            createOutput = { output },
        )
        try {
            speakUntilClosed(controller, output, "第一句。第二句。")
            assertEquals(1, segments.get())
            assertTrue(output.finished)
            assertNull(controller.state.value.error)
        } finally { controller.stop() }
    }

    @Test fun streamingFailureAfterAudioReportsErrorWithoutRestarting() {
        val output = FakeOutput()
        val segments = java.util.concurrent.atomic.AtomicInteger()
        val controller = SpeechPlaybackController(context, scope,
            streamSynthesize = { _, _, _, audio ->
                audio(byteArrayOf(1, 2))
                throw SpeechFailure(SpeechErrorCode.NETWORK, "dropped")
            },
            synthesize = { _, _, _, audio -> segments.incrementAndGet(); audio(byteArrayOf(1, 2)) },
            createOutput = { output },
        )
        try {
            speakUntilClosed(controller, output, "第一句。第二句。")
            assertEquals(0, segments.get())
            assertEquals("dropped", controller.state.value.error)
        } finally { controller.stop() }
    }

    @Test fun httpProvidersUseOrderedSegmentsWithoutAttemptingWebSocketSessions() {
        for (provider in listOf(TtsProvider.GEMINI, TtsProvider.MINIMAX)) {
            val output = FakeOutput()
            val segments = java.util.concurrent.CopyOnWriteArrayList<String>()
            val controller = SpeechPlaybackController(context, scope,
                streamSynthesize = { _, _, _, _ -> fail("HTTP providers must not start a WebSocket session") },
                synthesize = { settings, _, text, audio ->
                    assertEquals(provider, settings.tts)
                    segments += text
                    audio(byteArrayOf(1, 2))
                },
                createOutput = { output },
            )
            try {
                val text = "这是一段较长的回答。".repeat(100)
                speakUntilClosed(controller, output, text, provider)
                assertEquals(SpeechText.chunks(text), segments)
                assertTrue(output.finished)
                assertNull(controller.state.value.error)
            } finally { controller.stop() }
        }
    }

    @Test fun httpProviderFailureIsNotRetriedOrSentToAnotherProvider() {
        for (provider in listOf(TtsProvider.GEMINI, TtsProvider.MINIMAX)) {
            val output = FakeOutput()
            var attempts = 0
            val controller = SpeechPlaybackController(context, scope,
                synthesize = { settings, _, _, audio ->
                    assertEquals(provider, settings.tts)
                    attempts++
                    audio(byteArrayOf(1, 2))
                    throw SpeechFailure(SpeechErrorCode.NETWORK, "连接中断")
                },
                createOutput = { output },
            )
            try {
                speakUntilClosed(controller, output, "回答正文。".repeat(150), provider)
                assertEquals(1, attempts)
                assertEquals("连接中断", controller.state.value.error)
                assertFalse(output.finished)
            } finally { controller.stop() }
        }
    }

    private class FakeOutput : SpeechAudioOutput {
        var closed = false
        var finished = false
        override fun write(bytes: ByteArray) { check(!closed) }
        override fun finish() { check(!closed); finished = true }
        override fun drained(): Boolean { check(finished); return true }
        override fun close() { closed = true }
    }
}
