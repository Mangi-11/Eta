package io.github.mangi.eta.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val coroutineScope = rememberCoroutineScope()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    val themeModes = AppearanceThemeMode.entries
    val themeModeLabels = listOf(
        stringResource(R.string.appearance_theme_system),
        stringResource(R.string.appearance_theme_light),
        stringResource(R.string.appearance_theme_dark),
    )
    val paletteStyles = AppearancePaletteStyle.entries
    val paletteLabels = listOf(
        stringResource(R.string.appearance_palette_tonal_spot),
        stringResource(R.string.appearance_palette_neutral),
        stringResource(R.string.appearance_palette_vibrant),
        stringResource(R.string.appearance_palette_expressive),
        stringResource(R.string.appearance_palette_rainbow),
        stringResource(R.string.appearance_palette_fruit_salad),
        stringResource(R.string.appearance_palette_monochrome),
        stringResource(R.string.appearance_palette_fidelity),
        stringResource(R.string.appearance_palette_content),
        stringResource(R.string.appearance_palette_cmf),
    )
    val accentColors = AppearanceAccentColor.entries
    val accentLabels = listOf(
        stringResource(R.string.appearance_accent_system),
        stringResource(R.string.appearance_accent_blue),
        stringResource(R.string.appearance_accent_purple),
        stringResource(R.string.appearance_accent_pink),
        stringResource(R.string.appearance_accent_red),
        stringResource(R.string.appearance_accent_orange),
        stringResource(R.string.appearance_accent_yellow),
        stringResource(R.string.appearance_accent_green),
        stringResource(R.string.appearance_accent_teal),
    )

    ScaffoldPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
    ) {
        item(key = "appearance_color") {
            val backdrop = currentGlassOverlayBackdrop()
            SmallTitle(text = stringResource(R.string.appearance_group_color))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                GlassDropdownPreference(
                    title = stringResource(R.string.appearance_theme_mode),
                    items = themeModeLabels,
                    selectedIndex = appearance.themeMode.ordinal,
                    backdrop = backdrop,
                    onSelectedIndexChange = { index ->
                        themeModes.getOrNull(index)?.let { mode ->
                            update { current -> current.copy(themeMode = mode) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_monet),
                    checked = appearance.monetEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(monetEnabled = enabled) }
                    },
                )
                AnimatedVisibility(
                    visible = appearance.monetEnabled,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        GlassDropdownPreference(
                            title = stringResource(R.string.appearance_palette_style),
                            items = paletteLabels,
                            selectedIndex = appearance.paletteStyle.ordinal,
                            backdrop = backdrop,
                            onSelectedIndexChange = { index ->
                                paletteStyles.getOrNull(index)?.let { style ->
                                    update { current -> current.copy(paletteStyle = style) }
                                }
                            },
                        )
                        GlassDropdownPreference(
                            title = stringResource(R.string.appearance_accent_color),
                            items = accentLabels,
                            selectedIndex = appearance.accentColor.ordinal,
                            backdrop = backdrop,
                            onSelectedIndexChange = { index ->
                                accentColors.getOrNull(index)?.let { accent ->
                                    update { current -> current.copy(accentColor = accent) }
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.appearance_pure_black),
                            checked = appearance.pureBlackEnabled,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(pureBlackEnabled = enabled) }
                            },
                        )
                    }
                }
            }
        }

        item(key = "appearance_interface") {
            SmallTitle(text = stringResource(R.string.appearance_group_interface))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.appearance_swipe_dismiss),
                    checked = appearance.swipeDismissEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(swipeDismissEnabled = enabled) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_predictive_back),
                    checked = appearance.predictiveBackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(predictiveBackEnabled = enabled) }
                    },
                )
                if (isRuntimeShaderSupported()) {
                    SwitchPreference(
                        title = stringResource(R.string.appearance_progressive_blur),
                        summary = stringResource(R.string.appearance_progressive_blur_summary),
                        checked = appearance.progressiveBlurEnabled,
                        onCheckedChange = { enabled ->
                            update { current -> current.copy(progressiveBlurEnabled = enabled) }
                        },
                    )
                }
            }
        }
    }
}
