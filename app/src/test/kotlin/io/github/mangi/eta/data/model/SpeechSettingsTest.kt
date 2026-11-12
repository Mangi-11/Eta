package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeechSettingsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test fun legacySystemRecognitionMigratesToDefaultOnlineProviderAndPreservesOtherSettings() {
        val config = json.decodeFromString<SpeechSettings>("{}")
        assertEquals(AsrProvider.QWEN_REALTIME, config.asr)
        assertEquals(TtsProvider.NONE, config.tts)
        assertFalse(config.autoSpeak)
        assertTrue(config.recognitionEnabled)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(config, SpeechCredentials(), synthesis = false)
        }
        val legacy = json.decodeFromString<SpeechSettings>(
            """{"asr":"SYSTEM","tts":"DOUBAO","autoSpeak":true,"refineTranscript":true}""",
        )
        assertEquals(AsrProvider.QWEN_REALTIME, legacy.asr)
        assertEquals(TtsProvider.DOUBAO, legacy.tts)
        assertTrue(legacy.autoSpeak)
        assertTrue(legacy.refineTranscript)
    }

    @Test fun switchingServicesPreservesIndependentConfigurations() {
        val config = SpeechSettings(asr = AsrProvider.QWEN_REALTIME, tts = TtsProvider.DOUBAO,
            qwenAsr = QwenSpeechConfig(SpeechRegion.SINGAPORE),
            doubaoTts = DoubaoSpeechConfig(legacyAuth = true, appId = "test-app"), qwenVoice = "custom-voice")
        val changed = config.copy(asr = AsrProvider.DOUBAO, tts = TtsProvider.NONE)
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

    @Test fun mimoConfigurationsDefaultForOldDataAndRoundTripIndependently() {
        val old = json.decodeFromString<SpeechSettings>(
            """{"asr":"QWEN_FLASH","language":"ja","tts":"MINIMAX","minimaxTts":{"speed":1.25},"autoSpeak":true}""",
        )
        assertEquals(MimoAsrConfig(), old.mimoAsr)
        assertEquals(MimoTtsConfig(), old.mimoTts)
        val mimo = old.copy(
            asr = AsrProvider.MIMO,
            tts = TtsProvider.MIMO,
            mimoAsr = MimoAsrConfig(
                baseUrl = "https://recognition.example.test/v1",
                language = MimoAsrLanguage.CHINESE,
            ),
            mimoTts = MimoTtsConfig(
                baseUrl = "https://synthesis.example.test/v1",
                voice = "Mia",
                style = "自然、温和",
            ),
        )
        assertEquals(mimo, json.decodeFromString<SpeechSettings>(json.encodeToString(mimo)))
    }

    @Test fun mimoCredentialUpdatesPreserveEachOtherAndExistingProviders() {
        val oldCredentials = json.decodeFromString<SpeechCredentials>(
            """{"qwenAsr":"qwen-key","geminiTts":"gemini-key","ossAccessKeySecret":"oss-key"}""",
        )
        assertEquals("", oldCredentials.mimoAsr)
        assertEquals("", oldCredentials.mimoTts)
        val updated = oldCredentials.withValue(SpeechCredentialField.MIMO_ASR, "asr-key")
            .withValue(SpeechCredentialField.MIMO_TTS, "tts-key")
            .withValue(SpeechCredentialField.DOUBAO_ASR, "doubao-key")
        val restoredCredentials = json.decodeFromString<SpeechCredentials>(json.encodeToString(updated))
        assertEquals("asr-key", restoredCredentials.mimoAsr)
        assertEquals("tts-key", restoredCredentials.mimoTts)
        assertEquals("doubao-key", restoredCredentials.doubaoAsr)
        assertEquals("qwen-key", restoredCredentials.qwenAsr)
        assertEquals("gemini-key", restoredCredentials.geminiTts)
        assertEquals("oss-key", restoredCredentials.ossAccessKeySecret)
        assertEquals(
            "tts-key",
            restoredCredentials.withValue(SpeechCredentialField.MIMO_ASR, "changed-asr-key").mimoTts,
        )
    }

    @Test fun mimoSynthesisRequiresPlaybackCredentialsAndBoundedStyle() {
        val settings = SpeechSettings(tts = TtsProvider.MIMO)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(settings, SpeechCredentials(mimoAsr = "recognition-only"), true)
        }
        val credentials = SpeechCredentials(mimoTts = "playback-key")
        validateSpeechSettings(settings, credentials, true)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(settings.copy(mimoTts = settings.mimoTts.copy(style = "字".repeat(501))),
                credentials, true)
        }
    }
}
