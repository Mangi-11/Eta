package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = mutableListOf<String>()
    private lateinit var controller: SpeechInputController

    @Before fun setup() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        controller = SpeechInputController(RuntimeEnvironment.getApplication(), scope, onResult = results::add)
    }
    @After fun close() { controller.cancel(); scope.cancel() }

    private fun start(): ShadowSpeechRecognizer {
        controller.start(SpeechSettings(), SpeechCredentials())
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).also {
            it.triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            shadowOf(Looper.getMainLooper()).idle()
            it.triggerOnReadyForSpeech(Bundle())
        }
    }
    private fun text(value: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value)) }

    @Test fun dictationWaitsForUserEvenWhenSystemReturnsEarly() {
        start().triggerOnResults(text("可编辑的草稿"))
        assertTrue(results.isEmpty())
        assertEquals("可编辑的草稿", controller.state.value.preview)
        controller.finish()
        controller.finish()
        assertEquals(listOf("可编辑的草稿"), results)
        assertFalse(controller.state.value.active)
    }

    @Test fun canceledSessionCannotSubmitOrReplaceNewPreview() {
        val old = start()
        controller.cancel()
        val current = start()
        old.triggerOnResults(text("旧结果"))
        current.triggerOnPartialResults(text("新草稿"))
        assertEquals("新草稿", controller.state.value.preview)
        controller.finish()
        current.triggerOnResults(text("新结果"))
        assertEquals(listOf("新结果"), results)
    }

    @Test fun closingOwnerReleasesRecognitionAndTimeouts() {
        val recognizer = start()
        controller.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        recognizer.triggerOnResults(text("迟到"))
        assertTrue(recognizer.isDestroyed)
        assertTrue(results.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test fun anotherAudioOwnerInterruptsRecording() {
        val recognizer = start()
        val lease = SpeechAudioLease(RuntimeEnvironment.getApplication()) {}
        try {
            lease.acquire(playback = false)
            assertTrue(recognizer.isDestroyed)
            assertFalse(controller.state.value.active)
        } finally { lease.close() }
    }

    @Test fun ownerCancellationReleasesSystemRecognizer() {
        val recognizer = start()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(recognizer.isDestroyed)
        assertFalse(controller.state.value.active)
    }

    @Test fun disabledRecognitionDoesNotAcquireAudioOrRequireCloudCredentials() {
        var interrupted = false
        val lease = SpeechAudioLease(RuntimeEnvironment.getApplication()) { interrupted = true }
        try {
            lease.acquire(playback = false)
            for (provider in AsrProvider.entries) {
                controller.start(SpeechSettings(asr = provider, recognitionEnabled = false))
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(controller.state.value.active)
                assertNull(controller.state.value.error)
                assertTrue(results.isEmpty())
                assertFalse(interrupted)
            }
        } finally { lease.close() }
    }

    @Test fun disablingAnActiveSessionDiscardsLateRecognitionResults() {
        val recognizer = start()
        controller.start(SpeechSettings(recognitionEnabled = false))
        shadowOf(Looper.getMainLooper()).idle()
        recognizer.triggerOnResults(text("迟到的旧识别"))
        assertTrue(recognizer.isDestroyed)
        assertFalse(controller.state.value.active)
        assertTrue(results.isEmpty())
    }
    private fun overlay(refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner()) =
        SpeechInputController(RuntimeEnvironment.getApplication(), scope, automaticEndpoint = true,
            refiner = refiner, onResult = results::add)

    private fun SpeechInputController.begin(settings: SpeechSettings, holdToTalk: Boolean = false): ShadowSpeechRecognizer {
        start(settings, SpeechCredentials(), holdToTalk = holdToTalk)
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).also {
            it.triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            shadowOf(Looper.getMainLooper()).idle()
            it.triggerOnReadyForSpeech(Bundle())
        }
    }

    @Test fun overlaySubmitsAfterPauseByDefault() {
        val overlay = overlay()
        try {
            overlay.begin(SpeechSettings()).triggerOnResults(text("打开设置"))
            assertEquals(listOf("打开设置"), results)
        } finally { overlay.cancel() }
    }

    @Test fun overlayWaitsWhenAutoSendIsOffOrHoldingToTalk() {
        listOf(
            SpeechSettings(autoSendSilenceMs = 0) to false,
            SpeechSettings() to true,
        ).forEach { (settings, hold) ->
            val overlay = overlay()
            try {
                overlay.begin(settings, holdToTalk = hold).triggerOnResults(text("还没说完"))
                assertTrue(results.isEmpty())
                overlay.finish()
                assertEquals(listOf("还没说完"), results)
            } finally { overlay.cancel(); results.clear() }
        }
    }

    @Test fun refinedTextReplacesTranscriptAndFailureKeepsOriginal() {
        fun refiner(reply: () -> String) = SpeechTranscriptRefiner(
            loadConfig = {
                io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
                    baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = "",
                )
            },
            providerFor = {
                object : io.github.mangi.eta.agent.model.AgentProviderClient {
                    override val id = "fixture"
                    override val capabilities = io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider.capabilities
                    override fun complete(
                        request: io.github.mangi.eta.agent.model.ProviderRequest,
                        runController: io.github.mangi.eta.agent.runtime.AgentRunController,
                        onEvent: (io.github.mangi.eta.agent.model.ProviderEvent) -> Unit,
                    ) = io.github.mangi.eta.agent.model.ProviderResponse(org.json.JSONObject()
                        .put("role", "assistant").put("content", reply()).put("finish_reason", "stop"))
                }
            },
        )
        listOf(
            refiner { "用 Python 写脚本" } to "用 Python 写脚本",
            refiner { throw java.io.IOException("offline") } to "用配森写脚本",
        ).forEach { (refiner, expected) ->
            val overlay = overlay(refiner)
            try {
                overlay.begin(SpeechSettings(refineTranscript = true)).triggerOnResults(text("用配森写脚本"))
                val deadline = System.nanoTime() + 3_000_000_000
                while (results.isEmpty() && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
                assertEquals(listOf(expected), results)
                assertFalse(overlay.state.value.active)
            } finally { overlay.cancel(); results.clear() }
        }
    }
}
