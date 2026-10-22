package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechSettingsRepositoryTest {
    @Test fun speechSettingsRoundTripWithoutOverwritingModelOrAppearance() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(context)
        val before = SettingsDataStore.settings()
        val speech = SpeechSettings(asr = AsrProvider.QWEN_FLASH, tts = TtsProvider.DOUBAO,
            autoSpeak = true, recognitionEnabled = false)
        SpeechSettingsRepository.save(speech)
        assertEquals(speech, SpeechSettingsRepository.settings())
        SettingsDataStore.updateSettings { it.copy(memoryEnabled = !before.memoryEnabled) }
        assertEquals(speech, SpeechSettingsRepository.settings())
        assertEquals(before.appearance, SettingsDataStore.settings().appearance)
        assertEquals(before.selectedProviderId, SettingsDataStore.settings().selectedProviderId)
    }

    @Test fun unreadableCredentialsRequireReconfiguration() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("eta_speech_secrets", Context.MODE_PRIVATE)
        prefs.edit().putString("credentials", "invalid ciphertext").commit()
        try {
            SpeechSettingsRepository.credentials(context)
            fail("Invalid ciphertext must not become empty credentials")
        } catch (expected: SpeechCredentialsUnavailable) {
            assertFalse(expected.toString().contains("invalid ciphertext"))
        }
    }
}
