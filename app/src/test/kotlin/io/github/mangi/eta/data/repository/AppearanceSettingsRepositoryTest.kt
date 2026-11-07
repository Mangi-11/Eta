package io.github.mangi.eta.data.repository

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
            interfaceScale = 0.9f,
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
    fun upgradedUserWithoutSavedUiStyleDefaultsToCouiAndExplicitChoiceIsPreserved() = runBlocking {
        val before = SettingsDataStore.settings()
        try {
            // 模拟升级用户：尚无 appearance_ui_style 持久化键时，默认使用 COUI
            assertEquals(AppearanceUiStyle.COUI, AppearanceSettingsRepository.settings().uiStyle)

            // 用户明确选择 Miuix 后，即使后续修改其他外观项，仍保留 Miuix 选择
            AppearanceSettingsRepository.selectUiStyle(AppearanceUiStyle.MIUIX)
            assertEquals(AppearanceUiStyle.MIUIX, AppearanceSettingsRepository.settings().uiStyle)

            AppearanceSettingsRepository.update { it.copy(themeMode = AppearanceThemeMode.DARK) }
            val afterOtherChange = AppearanceSettingsRepository.settings()
            assertEquals(AppearanceUiStyle.MIUIX, afterOtherChange.uiStyle)
            assertEquals(AppearanceThemeMode.DARK, afterOtherChange.themeMode)

            // 用户再次明确选择 COUI，同样持久化保留
            AppearanceSettingsRepository.selectUiStyle(AppearanceUiStyle.COUI)
            assertEquals(AppearanceUiStyle.COUI, AppearanceSettingsRepository.settings().uiStyle)
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun transformUpdatesDoNotOverwriteEarlierAppearanceChanges() = runBlocking {
        val before = AppearanceSettingsRepository.settings()
        try {
            AppearanceSettingsRepository.update { it.copy(monetEnabled = true) }
            AppearanceSettingsRepository.update { it.copy(blurEnabled = false) }

            val actual = AppearanceSettingsRepository.settings()
            assertEquals(true, actual.monetEnabled)
            assertEquals(false, actual.blurEnabled)
        } finally {
            AppearanceSettingsRepository.update(before)
        }
    }
}
