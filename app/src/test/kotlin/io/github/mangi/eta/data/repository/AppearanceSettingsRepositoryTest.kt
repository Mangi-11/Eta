package io.github.mangi.eta.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class AppearanceSettingsRepositoryTest {
    @Test
    fun appearanceRoundTripPreservesExistingSettingsFields() = runBlocking {
        val before = SettingsDataStore.settings()
        val providerId = "provider-${UUID.randomUUID()}"
        val modelId = "model-${UUID.randomUUID()}"
        val appearance = AppearanceSettings(
            uiStyle = AppearanceUiStyle.MIUIX,
            themeMode = AppearanceThemeMode.DARK,
            monetEnabled = true,
            accentColor = AppearanceAccentColor.TEAL,
        )

        try {
            SettingsDataStore.updateSettings {
                it.copy(
                    selectedProviderId = providerId,
                    selectedModelId = modelId,
                    memoryEnabled = false,
                )
            }
            AppearanceSettingsRepository.update(appearance)

            assertEquals(appearance, AppearanceSettingsRepository.settingsFlow().first())
            val after = SettingsDataStore.settings()
            assertEquals(providerId, after.selectedProviderId)
            assertEquals(modelId, after.selectedModelId)
            assertEquals(false, after.memoryEnabled)
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun upgradeResetsSavedCouiOnceAcrossRestarts() = runBlocking {
        val file = RuntimeEnvironment.getApplication().preferencesDataStoreFile("appearance-test-${UUID.randomUUID()}")
        val style = stringPreferencesKey("appearance_ui_style")
        val mode = stringPreferencesKey("appearance_theme_mode")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            PreferencesFileSerializer.writeTo(preferencesOf(style to "coui", mode to "dark"), it)
        }
        for (expected in listOf(AppearanceUiStyle.MIUIX, AppearanceUiStyle.COUI)) {
            val job = Job()
            try {
                val store = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job),
                    migrations = listOf(SettingsDataStore.DefaultUiStyleMigration),
                    produceFile = { file },
                )
                val settings = store.data.first()
                assertEquals(expected, AppearanceUiStyle.fromPersistedValue(settings[style]))
                assertEquals("dark", settings[mode])
                store.edit { it[style] = "coui" }
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun transformUpdatesDoNotOverwriteEarlierAppearanceChanges() = runBlocking {
        val before = AppearanceSettingsRepository.settings()
        try {
            AppearanceSettingsRepository.update { it.copy(monetEnabled = true) }
            AppearanceSettingsRepository.update { it.copy(blurEnabled = false) }
            AppearanceSettingsRepository.update { it.copy(progressiveBlurEnabled = false) }

            val actual = AppearanceSettingsRepository.settings()
            assertEquals(true, actual.monetEnabled)
            assertEquals(false, actual.blurEnabled)
            assertEquals(false, actual.progressiveBlurEnabled)
        } finally {
            AppearanceSettingsRepository.update(before)
        }
    }
}
