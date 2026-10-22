package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeechSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun oldSettingsPreserveSystemRecognitionAndSilentOutput() {
        val config = json.decodeFromString<SpeechSettings>("{}")
        assertEquals(AsrProvider.SYSTEM, config.asr)
        assertEquals(TtsProvider.NONE, config.tts)
        assertFalse(config.autoSpeak)
        assertTrue(config.recognitionEnabled)
        validateSpeechSettings(config, SpeechCredentials(), synthesis = false)
    }

    @Test fun switchingServicesPreservesIndependentConfigurations() {
        val config = SpeechSettings(asr = AsrProvider.QWEN_REALTIME, tts = TtsProvider.DOUBAO,
            qwenAsr = QwenSpeechConfig(SpeechRegion.SINGAPORE),
            doubaoTts = DoubaoSpeechConfig(legacyAuth = true, appId = "test-app"), qwenVoice = "custom-voice")
        val changed = config.copy(asr = AsrProvider.SYSTEM, tts = TtsProvider.NONE)
        assertEquals(changed, json.decodeFromString<SpeechSettings>(json.encodeToString(changed)))
        assertEquals(config.qwenAsr, changed.qwenAsr)
        assertEquals(config.doubaoTts, changed.doubaoTts)
        assertEquals(config.qwenVoice, changed.qwenVoice)
    }

    @Test fun selectedCloudProviderRequiresCredentialsWithoutFallback() {
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.QWEN_FLASH), SpeechCredentials(), false)
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.QWEN_FILE), SpeechCredentials(qwenAsr = "test"), false)
        }
    }

    @Test fun credentialsAreRedactedAndNeverPartOfSettings() {
        val secrets = SpeechCredentials(qwenAsr = "private-key")
        assertFalse(secrets.toString().contains("private-key"))
        val updated = secrets.withValue(SpeechCredentialField.DOUBAO_TTS, "other-key")
        assertEquals("private-key", updated.qwenAsr)
        assertEquals("other-key", updated.doubaoTts)
        assertFalse(json.encodeToString(SpeechSettings()).contains("private-key"))
    }

    @Test fun existingSpeechConfigurationAndCredentialsSurviveNewProviders() {
        val old = json.decodeFromString<SpeechSettings>(
            """{"tts":"DOUBAO","qwenVoice":"custom-qwen","doubaoVoice":"custom-doubao","chatAutoSpeak":true}""",
        )
        assertEquals(TtsProvider.DOUBAO, old.tts)
        assertTrue(old.chatAutoSpeak)
        assertEquals("custom-qwen", old.qwenVoice)
        assertEquals("custom-doubao", old.doubaoVoice)
        assertEquals(GeminiTtsConfig(), old.geminiTts)
        assertEquals(MiniMaxTtsConfig(), old.minimaxTts)
        val updated = old.copy(
            tts = TtsProvider.GEMINI,
            geminiTts = old.geminiTts.copy(model = GeminiTtsModel.FLASH_LITE, style = "自然"),
            minimaxTts = old.minimaxTts.copy(model = MiniMaxTtsModel.HD, speed = 1.25f),
        )
        assertEquals(updated, json.decodeFromString<SpeechSettings>(json.encodeToString(updated)))
        val credentials = json.decodeFromString<SpeechCredentials>(
            """{"qwenAsr":"old-asr","doubaoTts":"old-tts","ossAccessKeySecret":"old-oss"}""",
        ).withValue(SpeechCredentialField.GEMINI_TTS, "new-gemini")
            .withValue(SpeechCredentialField.MINIMAX_TTS, "new-minimax")
        val restored = json.decodeFromString<SpeechCredentials>(json.encodeToString(credentials))
        assertEquals("old-asr", restored.qwenAsr)
        assertEquals("old-tts", restored.doubaoTts)
        assertEquals("old-oss", restored.ossAccessKeySecret)
        assertEquals("new-gemini", restored.geminiTts)
        assertEquals("new-minimax", restored.minimaxTts)
        assertEquals("SpeechCredentials(redacted)", restored.toString())
    }

    @Test fun newProvidersValidateOnlyTheirOwnCredentialsAndOptions() {
        for (provider in listOf(TtsProvider.GEMINI, TtsProvider.MINIMAX)) {
            val settings = SpeechSettings(tts = provider)
            assertThrows(SpeechFailure::class.java) {
                validateSpeechSettings(settings, SpeechCredentials(qwenTts = "other-key"), true)
            }
            validateSpeechSettings(settings, SpeechCredentials(geminiTts = "test", minimaxTts = "test"), true)
        }
        val secrets = SpeechCredentials(geminiTts = "test", minimaxTts = "test")
        for (speed in listOf(0f, 2.1f, Float.NaN)) {
            assertThrows(SpeechFailure::class.java) {
                validateSpeechSettings(SpeechSettings(tts = TtsProvider.MINIMAX,
                    minimaxTts = MiniMaxTtsConfig(speed = speed)), secrets, true)
            }
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(tts = TtsProvider.GEMINI,
                geminiTts = GeminiTtsConfig(style = "字".repeat(501))), secrets, true)
        }
    }

    @Test fun disablingRecognitionPreservesProviderConfigurationAndPlayback() {
        val original = SpeechSettings(asr = AsrProvider.QWEN_FILE, tts = TtsProvider.GEMINI,
            language = "zh", refineTranscript = true, autoSpeak = true)
        val disabled = original.copy(recognitionEnabled = false)
        val restored = json.decodeFromString<SpeechSettings>(json.encodeToString(disabled))
        assertFalse(restored.recognitionEnabled)
        assertEquals(original, restored.copy(recognitionEnabled = true))
        validateSpeechSettings(restored, SpeechCredentials(), synthesis = false)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(restored, SpeechCredentials(), synthesis = true)
        }
    }
}
