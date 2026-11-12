package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.MimoAsrConfig
import io.github.mangi.eta.data.model.MimoAsrLanguage
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FileSpeechLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captures = mutableListOf<FakeCapture>()
    private val requests = mutableListOf<FileRequest>()
    private val results = mutableListOf<String>()
    private val settings = SpeechSettings(
        asr = AsrProvider.MIMO,
        qwenAsr = QwenSpeechConfig(baseUrl = "invalid-unused-provider"),
        mimoAsr = MimoAsrConfig(language = MimoAsrLanguage.CHINESE),
    )
    private val credentials = SpeechCredentials(mimoAsr = "fixture-mimo-key")
    private lateinit var controller: SpeechInputController

    @Before fun setup() {
        controller = SpeechInputController(
            context = RuntimeEnvironment.getApplication(),
            scope = scope,
            automaticEndpoint = true,
            createRecorder = { FakeCapture().also(captures::add) },
            recognizeStream = { _, _, _, _, _, _, _ -> error("File recognition must not open a stream") },
            recognizeFile = { _, config, secrets, wav, progress ->
                val request = FileRequest(config, secrets, wav, progress)
                requests += request
                try {
                    request.result.await()
                } catch (error: CancellationException) {
                    request.canceled = true
                    throw error
                }
            },
            onResult = results::add,
        )
    }

    @After fun close() {
        controller.cancel()
        scope.cancel()
        idle()
    }

    @Test fun mimoWaitsForFinishThenTranscribesRecordedWavWithItsOwnConfiguration() {
        start()
        assertEquals(EtaSpeechPhase.LISTENING, controller.state.value.phase)
        assertTrue(requests.isEmpty())
        assertTrue(results.isEmpty())

        controller.finish()
        controller.finish()
        idle()

        val request = requests.single()
        assertSame(settings, request.settings)
        assertSame(credentials, request.credentials)
        assertEquals(48, request.wav.size)
        assertEquals("RIFF", request.wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), request.wav.copyOfRange(44, 48))
        assertTrue(captures.single().finished)
        assertEquals(EtaSpeechPhase.RECOGNIZING, controller.state.value.phase)
        assertTrue(results.isEmpty())

        request.result.complete("打开设置")
        idle()
        assertEquals(listOf("打开设置"), results)
        assertFalse(controller.state.value.active)
        assertTrue(captures.single().canceled)
    }

    @Test fun cancelingRecordingDoesNotSubmitAudio() {
        start()
        controller.cancel()
        idle()
        assertTrue(captures.single().canceled)
        assertTrue(requests.isEmpty())
        assertTrue(results.isEmpty())
        assertFalse(controller.state.value.active)
    }

    @Test fun blankTranscriptReportsNoSpeechWithoutDeliveringAResult() {
        start()
        controller.finish()
        idle()
        requests.single().result.complete(" \n\t")
        idle()
        assertTrue(results.isEmpty())
        assertFalse(controller.state.value.active)
        assertEquals("没有识别到语音，请重试", controller.state.value.error)
        assertTrue(captures.single().canceled)
    }

    @Test fun canceledTranscriptionCannotChangeTheNextSessionOrDeliverAnOldResult() {
        start()
        controller.finish()
        idle()
        val old = requests.single()
        controller.cancel()
        idle()
        assertTrue(old.canceled)

        start()
        old.progress("旧会话进度")
        old.result.complete("旧会话结果")
        idle()
        assertEquals(EtaSpeechPhase.LISTENING, controller.state.value.phase)
        assertNotEquals("旧会话进度", controller.state.value.progress)
        assertTrue(results.isEmpty())

        controller.finish()
        idle()
        val current = requests.last()
        assertEquals(2, requests.size)
        current.progress("新会话进度")
        idle()
        assertEquals("新会话进度", controller.state.value.progress)
        current.result.complete("新会话结果")
        idle()
        assertEquals(listOf("新会话结果"), results)
        assertFalse(controller.state.value.active)
    }

    private fun start() {
        controller.start(settings, credentials)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class FileRequest(
        val settings: SpeechSettings,
        val credentials: SpeechCredentials,
        val wav: ByteArray,
        val progress: (String) -> Unit,
    ) {
        val result = CompletableDeferred<String>()
        var canceled = false
    }

    private class FakeCapture : SpeechAudioCapture {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val stopped = CompletableDeferred<Unit>()
        var finished = false
        var canceled = false

        override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
            try {
                onReady()
                frames.send(byteArrayOf(1, 2, 3, 4))
                stopped.await()
            } finally {
                frames.close()
            }
        }

        override fun finish() {
            finished = true
            stopped.complete(Unit)
        }

        override fun cancel() {
            canceled = true
            finish()
            frames.cancel()
        }
    }
}
