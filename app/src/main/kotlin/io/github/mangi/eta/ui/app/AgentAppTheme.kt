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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.components.DemandSceneBackdrop
import io.github.mangi.eta.ui.components.EtaTopLayerHost
import io.github.mangi.eta.ui.components.EtaTopLayerState
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import io.github.mangi.eta.ui.components.coui.CouiBackdropContent
import io.github.mangi.eta.ui.components.coui.rememberCouiPageBackdrop
import io.github.suqi8.coui.kmp.basic.Scaffold as CouiScaffold
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.squircle.LocalSquircleEnabled as LocalCouiSquircleEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.LocalContentColor as LocalCouiContentColor
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
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
    systemOverlay: Boolean = false,
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

    val currentContent by rememberUpdatedState(content)
    // 切换 UI 样式时移动同一内容树，保留导航栈、页面草稿与正在运行的任务状态。
    val scopedContent = remember { movableContentOf { currentContent() } }

    MiuixTheme(controller = controller) {
        val baseMiuixColors = MiuixTheme.colorScheme
        val pureBlack = appearance.monetEnabled && appearance.pureBlackEnabled && isDark
        val couiNative = remember(isDark) { if (isDark) darkColorScheme() else lightColorScheme() }
        val themedMiuixColors = remember(
            baseMiuixColors,
            appearance.uiStyle,
            pureBlack,
            isDark,
        ) {
            when {
                appearance.uiStyle == AppearanceUiStyle.COUI -> {
                    val accentContainer = couiNative.primary.copy(alpha = if (isDark) 0.22f else 0.12f)
                    baseMiuixColors.copy(
                        primary = couiNative.primary,
                        onPrimary = couiNative.labelOnColor,
                        primaryContainer = accentContainer,
                        onPrimaryContainer = couiNative.primary,
                        secondaryContainer = couiNative.container12,
                        onSecondaryContainer = couiNative.labelPrimary,
                        tertiaryContainer = accentContainer,
                        onTertiaryContainer = couiNative.primary,
                        background = couiNative.backgroundWithCard,
                        surface = couiNative.backgroundWithCard,
                        surfaceContainer = couiNative.cardBackground,
                        surfaceContainerHigh = couiNative.cardPressed,
                        surfaceContainerHighest = couiNative.containerSolid,
                        onBackground = couiNative.labelPrimary,
                        onSurface = couiNative.labelPrimary,
                        onSurfaceContainer = couiNative.labelPrimary,
                        onSurfaceVariantSummary = couiNative.labelSecondary,
                        onSurfaceVariantActions = couiNative.labelSecondary,
                        outline = couiNative.divider,
                        dividerLine = couiNative.divider,
                        error = couiNative.error,
                        onError = couiNative.onError,
                    )
                }
                pureBlack -> baseMiuixColors.copy(
                    background = Color.Black,
                    surface = Color.Black,
                )
                else -> baseMiuixColors
            }
        }

        val blurSupported = isRuntimeShaderSupported()
        val couiMaterialSupported = blurSupported && !systemOverlay && LocalView.current.isHardwareAccelerated

        MiuixTheme(colors = themedMiuixColors) {
            CompositionLocalProvider(
                LocalContentColor provides themedMiuixColors.onBackground,
                LocalAppearanceSettings provides appearance,
                LocalUiStyle provides appearance.uiStyle,
            ) {
                if (appearance.uiStyle == AppearanceUiStyle.COUI) {
                    // 使用 colors 重载避免拉入 COUI ThemeController 对 material-color-utilities 5.0
                    // 旧包的静态引用（Miuix 0.9.5-beta02 已升级至 6.0.0）；显式补齐 COUI LocalContentColor，
                    // 避免深色模式下 COUI 组件回退到默认黑色内容色。
                    COUITheme(colors = couiNative) {
                        CompositionLocalProvider(
                            LocalCouiContentColor provides couiNative.labelPrimary,
                            LocalCOUIMaterialEffectEnabled provides
                                (couiMaterialSupported && appearance.blurEnabled),
                            LocalCouiSquircleEnabled provides !systemOverlay,
                        ) {
                            if (systemOverlay) {
                                scopedContent()
                            } else {
                                CouiScaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    containerColor = Color.Transparent,
                                    contentWindowInsets = WindowInsets(0),
                                ) {
                                    if (provideGlassHost) {
                                        // 主窗口根级记录页面背景，使 AgentAppRoot 层弹出的弹窗同样拥有 COUI 17 材质模糊；
                                        // 各二级页自身的 CouiBackdropContent 会覆盖为当前页局部背景。
                                        val rootBackdrop = rememberCouiPageBackdrop(couiNative.backgroundWithCard)
                                        CouiBackdropContent(rootBackdrop) { scopedContent() }
                                    } else {
                                        scopedContent()
                                    }
                                }
                            }
                        }
                    }
                } else if (provideGlassHost) {
                    val surfaceColor = themedMiuixColors.surface
                    val delegateBackdrop = rememberGlassBackdrop(surfaceColor)
                    val sceneBackdrop = remember(delegateBackdrop, surfaceColor) {
                        delegateBackdrop?.let { DemandSceneBackdrop(it, fallbackColor = surfaceColor) }
                    }
                    val topLayer = remember { EtaTopLayerState() }
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
                            LocalEtaTopLayer provides topLayer,
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .captureGlassBackdrop(
                                            if (captureActive) delegateBackdrop else null,
                                        ),
                                ) {
                                    scopedContent()
                                }
                                EtaTopLayerHost(
                                    state = topLayer,
                                    onActiveOverlayChanged = { hasActiveTopLayerOverlay = it },
                                )
                            }
                        }
                    }
                } else {
                    scopedContent()
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
