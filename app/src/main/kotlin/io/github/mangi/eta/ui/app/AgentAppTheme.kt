package io.github.mangi.eta.ui.app

import android.util.DisplayMetrics
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiPresets
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiDarkTheme
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.DEFAULT_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
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

    val initialDisplayDensity = remember(context) { context.resources.displayMetrics.density }
    val baseDensity = if (configuration.densityDpi > 0) {
        configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
    } else {
        initialDisplayDensity
    }
    val effectiveScale = if (applyInterfaceScale) {
        normalizeInterfaceScale(appearance.interfaceScale)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
    val targetDensity = baseDensity * effectiveScale
    val platformDensity = remember(context, baseDensity, configuration.fontScale) {
        val saved = context.resources.displayMetrics.density
        context.resources.displayMetrics.density = baseDensity
        Density(context).also { context.resources.displayMetrics.density = saved }
    }
    if (applyInterfaceScale) {
        // 独立窗口（Dialog / Popup）的新 AndroidComposeView 从宿主 Context.resources 读取密度；
        // 以 configuration.densityDpi / 160f 为 100% 尺寸基准同步写入，保证页面与独立窗口缩放一致。
        context.resources.displayMetrics.density = targetDensity
        @Suppress("DEPRECATION")
        context.resources.displayMetrics.scaledDensity = targetDensity * configuration.fontScale
    }
    DisposableEffect(context, applyInterfaceScale, baseDensity, configuration.fontScale) {
        onDispose {
            if (applyInterfaceScale) {
                context.resources.displayMetrics.density = baseDensity
                @Suppress("DEPRECATION")
                context.resources.displayMetrics.scaledDensity = baseDensity * configuration.fontScale
            }
        }
    }
    val appDensity = remember(context, targetDensity, configuration.fontScale, applyInterfaceScale) {
        if (applyInterfaceScale) {
            context.resources.displayMetrics.density = targetDensity
            Density(context)
        } else {
            platformDensity
        }
    }

    val currentContent by rememberUpdatedState(content)
    // 切换 UI 样式时移动同一内容树，保留导航栈、页面草稿与正在运行的任务状态。
    val scopedContent = remember { movableContentOf { currentContent() } }

    MiuixTheme(controller = controller) {
        val baseMiuixColors = MiuixTheme.colorScheme
        val pureBlack = appearance.monetEnabled && appearance.pureBlackEnabled && isDark
        val couiNative = if (isDark) CouiPresets.Dark else CouiPresets.Light
        val themedMiuixColors = remember(
            baseMiuixColors,
            appearance.uiStyle,
            pureBlack,
            isDark,
        ) {
            when {
                appearance.uiStyle == AppearanceUiStyle.COUI -> {
                    val accentContainer = couiNative.accent.copy(alpha = if (isDark) 0.22f else 0.12f)
                    baseMiuixColors.copy(
                        primary = couiNative.accent,
                        onPrimary = couiNative.onAccent,
                        primaryContainer = accentContainer,
                        onPrimaryContainer = couiNative.accent,
                        secondaryContainer = if (isDark) Color(0xFF282A2E) else Color(0xFFECEEF1),
                        onSecondaryContainer = couiNative.onSurface,
                        tertiaryContainer = accentContainer,
                        onTertiaryContainer = couiNative.accent,
                        background = couiNative.background,
                        surface = if (isDark) Color(0xFF121214) else Color.White,
                        surfaceContainer = if (isDark) Color(0xFF1E2024) else Color(0xFFF4F5F7),
                        surfaceContainerHigh = if (isDark) Color(0xFF282A2E) else Color(0xFFEBECEE),
                        surfaceContainerHighest = if (isDark) Color(0xFF32353A) else Color(0xFFE2E4E8),
                        onBackground = couiNative.onSurface,
                        onSurface = couiNative.onSurface,
                        onSurfaceContainer = couiNative.onSurface,
                        onSurfaceVariantSummary = couiNative.onSurfaceVariant,
                        onSurfaceVariantActions = couiNative.onSurfaceVariant,
                        outline = if (isDark) Color(0x26FFFFFF) else Color(0x16000000),
                        dividerLine = couiNative.outlineVariant,
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
        val effectiveBlur = if (appearance.uiStyle == AppearanceUiStyle.COUI) {
            blurSupported
        } else {
            appearance.blurEnabled && blurSupported
        }

        MiuixTheme(colors = themedMiuixColors) {
            CompositionLocalProvider(
                LocalContentColor provides themedMiuixColors.onBackground,
                LocalCouiDarkTheme provides isDark,
                LocalCouiContourGlowEnabled provides effectiveBlur,
                LocalAppearanceSettings provides appearance,
                LocalUiStyle provides appearance.uiStyle,
                LocalBlurEnabled provides effectiveBlur,
                LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
                LocalPlatformDensity provides platformDensity,
                LocalDensity provides appDensity,
            ) {
                if (appearance.uiStyle == AppearanceUiStyle.COUI) {
                    CouiTheme(darkTheme = isDark, colorScheme = couiNative) {
                        if (effectiveBlur) {
                            ProvideCouiMenuBackdrop(isolateContent = false) { windowSource ->
                                Box(Modifier.fillMaxSize().then(windowSource)) {
                                    scopedContent()
                                }
                            }
                        } else {
                            scopedContent()
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
