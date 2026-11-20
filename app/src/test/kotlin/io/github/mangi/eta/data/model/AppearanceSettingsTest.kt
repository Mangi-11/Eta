package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsPreserveExistingAppearanceBehaviorAndDefaultToMiuix() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceUiStyle.MIUIX, settings.uiStyle)
        assertEquals(AppearanceThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.monetEnabled)
        assertTrue(settings.blurEnabled)
        assertTrue(settings.progressiveBlurEnabled)
        assertTrue(settings.swipeDismissEnabled)
        assertTrue(settings.predictiveBackEnabled)
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceUiStyle.MIUIX, AppearanceUiStyle.fromPersistedValue(null))
        assertEquals(AppearanceUiStyle.MIUIX, AppearanceUiStyle.fromPersistedValue("unknown"))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("coui"))
        assertEquals(AppearanceUiStyle.MIUIX, AppearanceUiStyle.fromPersistedValue("miuix"))
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearancePaletteStyle.TONAL_SPOT, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearancePaletteStyle.CMF, AppearancePaletteStyle.fromPersistedValue("cmf"))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
        assertEquals(
            AppearanceTopBarBlurStyle.GAUSSIAN,
            AppearanceTopBarBlurStyle.fromPersistedValue("future_style"),
        )
    }
}
