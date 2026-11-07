package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsPreserveExistingAppearanceBehaviorAndDefaultToCoui() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceUiStyle.COUI, settings.uiStyle)
        assertEquals(AppearanceThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.monetEnabled)
        assertTrue(settings.blurEnabled)
        assertTrue(settings.swipeDismissEnabled)
        assertTrue(settings.predictiveBackEnabled)
        assertEquals(DEFAULT_INTERFACE_SCALE, settings.interfaceScale)
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue(null))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("unknown"))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("coui"))
        assertEquals(AppearanceUiStyle.MIUIX, AppearanceUiStyle.fromPersistedValue("miuix"))
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearancePaletteStyle.TONAL_SPOT, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
        assertEquals(
            AppearanceTopBarBlurStyle.GAUSSIAN,
            AppearanceTopBarBlurStyle.fromPersistedValue("future_style"),
        )
    }

    @Test
    fun interfaceScaleIsFiniteAndClamped() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.2f))
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(2f))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.POSITIVE_INFINITY))
        assertEquals(0.95f, normalizeInterfaceScale(0.95f))
    }
}
