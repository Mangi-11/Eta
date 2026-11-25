package io.github.mangi.eta.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.components.DemandSceneBackdrop
import io.github.mangi.eta.ui.components.TopLayerHost
import io.github.mangi.eta.ui.components.TopLayerState
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    provideGlassHost: Boolean = false,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorSchemeMode = when {
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.Light
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.Dark
        !appearance.monetEnabled -> ColorSchemeMode.System
        appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val systemSeedColor = if (
        appearance.monetEnabled &&
        appearance.accentColor == AppearanceAccentColor.SYSTEM &&
        appearance.paletteStyle != AppearancePaletteStyle.TONAL_SPOT
    ) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !appearance.monetEnabled -> null
        appearance.accentColor == AppearanceAccentColor.SYSTEM -> systemSeedColor
        else -> appearance.accentColor.seedColor()
    }
    val controller = remember(appearance, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixPaletteStyle(),
            isDark = isDark,
        )
    }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(controller = controller) {
        val baseColors = MiuixTheme.colorScheme
        val pureBlack = appearance.monetEnabled && appearance.pureBlackEnabled && isDark
        val themedColors = remember(baseColors, pureBlack) {
            if (pureBlack) {
                baseColors.copy(
                    background = Color.Black,
                    surface = Color.Black,
                )
            } else {
                baseColors
            }
        }

        MiuixTheme(colors = themedColors) {
            CompositionLocalProvider(
                LocalContentColor provides themedColors.onBackground,
                LocalAppearanceSettings provides appearance,
            ) {
                if (provideGlassHost) {
                    val surfaceColor = themedColors.surface
                    val delegateBackdrop = rememberGlassBackdrop(surfaceColor)
                    val sceneBackdrop = remember(delegateBackdrop, surfaceColor) {
                        delegateBackdrop?.let { DemandSceneBackdrop(it, fallbackColor = surfaceColor) }
                    }
                    val topLayer = remember { TopLayerState() }
                    var hasActiveMiuixPopup by remember { mutableStateOf(false) }
                    var hasActiveTopLayerOverlay by remember { mutableStateOf(false) }
                    val hasActiveOverlay = hasActiveMiuixPopup || hasActiveTopLayerOverlay
                    val captureActive = hasActiveOverlay || sceneBackdrop?.isActive == true
                    if (sceneBackdrop != null) {
                        SideEffect {
                            sceneBackdrop.setDelegateAttached(captureActive)
                        }
                        LaunchedEffect(sceneBackdrop.isActive, hasActiveOverlay) {
                            if (!sceneBackdrop.isActive || hasActiveOverlay) return@LaunchedEffect
                            while (sceneBackdrop.isActive && !hasActiveOverlay) {
                                delay(250L)
                                sceneBackdrop.refreshIdleState()
                            }
                        }
                    }
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0),
                        popupHost = {
                            Box(
                                modifier = Modifier.onSizeChanged { size ->
                                    hasActiveMiuixPopup = size.width > 0 && size.height > 0
                                },
                            ) {
                                MiuixPopupHost()
                            }
                        },
                    ) {
                        CompositionLocalProvider(
                            LocalGlassSceneBackdrop provides sceneBackdrop,
                            LocalTopLayer provides topLayer,
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .captureGlassBackdrop(
                                            if (captureActive) delegateBackdrop else null,
                                        ),
                                ) {
                                    content()
                                }
                                TopLayerHost(
                                    state = topLayer,
                                    onActiveOverlayChanged = { hasActiveTopLayerOverlay = it },
                                )
                            }
                        }
                    }
                } else {
                    content()
                }
            }
        }
    }
}

private fun AppearancePaletteStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    AppearancePaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    AppearancePaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    AppearancePaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    AppearancePaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    AppearancePaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    AppearancePaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    AppearancePaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    AppearancePaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    AppearancePaletteStyle.CONTENT -> ThemePaletteStyle.Content
    AppearancePaletteStyle.CMF -> ThemePaletteStyle.Cmf
}

private fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF4285F4)
    AppearanceAccentColor.PURPLE -> Color(0xFF7C4DFF)
    AppearanceAccentColor.PINK -> Color(0xFFE91E63)
    AppearanceAccentColor.RED -> Color(0xFFEA4335)
    AppearanceAccentColor.ORANGE -> Color(0xFFFF5722)
    AppearanceAccentColor.YELLOW -> Color(0xFFFBBC04)
    AppearanceAccentColor.GREEN -> Color(0xFF34A853)
    AppearanceAccentColor.TEAL -> Color(0xFF00BCD4)
}
