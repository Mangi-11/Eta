package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsPreserveExistingAppearanceBehavior() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.monetEnabled)
        assertTrue(settings.progressiveBlurEnabled)
        assertTrue(settings.swipeDismissEnabled)
        assertTrue(settings.predictiveBackEnabled)
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearancePaletteStyle.TONAL_SPOT, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearancePaletteStyle.CMF, AppearancePaletteStyle.fromPersistedValue("cmf"))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
    }
}
