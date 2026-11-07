package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSliderPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val coroutineScope = rememberCoroutineScope()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    val blurSupported = isRuntimeShaderSupported()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun selectUiStyle(uiStyle: AppearanceUiStyle) {
        coroutineScope.launch {
            AppearanceSettingsRepository.selectUiStyle(uiStyle)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    val uiStyles = AppearanceUiStyle.entries
    val uiStyleLabels = listOf(
        stringResource(R.string.appearance_ui_style_coui),
        stringResource(R.string.appearance_ui_style_miuix),
    )
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
    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )
    val parsedScale = scaleInput.toIntOrNull()

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.appearance_title),
                onBack = onBack,
            ) {
                Group(title = stringResource(R.string.appearance_group_color)) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.appearance_ui_style),
                        items = uiStyleLabels,
                        selectedIndex = appearance.uiStyle.ordinal,
                        onSelectedIndexChange = { index ->
                            uiStyles.getOrNull(index)?.let(::selectUiStyle)
                        },
                    )
                    Divider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.appearance_theme_mode),
                        items = themeModeLabels,
                        selectedIndex = appearance.themeMode.ordinal,
                        onSelectedIndexChange = { index ->
                            themeModes.getOrNull(index)?.let { mode ->
                                update { current -> current.copy(themeMode = mode) }
                            }
                        },
                    )
                }

                Group(title = stringResource(R.string.appearance_group_interface)) {
                    CouiSwitchPreference(
                        title = stringResource(R.string.appearance_swipe_dismiss),
                        summary = stringResource(R.string.appearance_swipe_dismiss_summary),
                        checked = appearance.swipeDismissEnabled,
                        onCheckedChange = { enabled ->
                            update { current -> current.copy(swipeDismissEnabled = enabled) }
                        },
                    )
                    Divider()
                    CouiSwitchPreference(
                        title = stringResource(R.string.appearance_predictive_back),
                        summary = stringResource(R.string.appearance_predictive_back_summary),
                        checked = appearance.predictiveBackEnabled,
                        onCheckedChange = { enabled ->
                            update { current -> current.copy(predictiveBackEnabled = enabled) }
                        },
                    )
                    Divider()
                    CouiSliderPreference(
                        title = stringResource(R.string.appearance_interface_scale),
                        summary = stringResource(R.string.appearance_interface_scale_summary),
                        value = scaleDraft.coerceIn(
                            MIN_INTERFACE_SCALE * 100f,
                            MAX_INTERFACE_SCALE * 100f,
                        ),
                        onValueChange = { scaleDraft = it },
                        valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                        steps = 5,
                        showTickMarks = true,
                        onValueChangeFinished = { commitScale(scaleDraft) },
                        valueText = "${scaleDraft.roundToInt()}%",
                        onClick = {
                            scaleInput = scaleDraft.roundToInt().toString()
                            showScaleDialog = true
                        },
                    )
                }

                CouiPageBottomSpacer()
            }

            CouiInputDialog(
                visible = showScaleDialog,
                title = stringResource(R.string.appearance_interface_scale_dialog_title),
                message = stringResource(R.string.appearance_interface_scale_dialog_summary),
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                placeholder = stringResource(R.string.appearance_interface_scale_input_label),
                confirmText = stringResource(R.string.action_confirm),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
                onDismissRequest = { showScaleDialog = false },
            )
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.appearance_title),
                onBack = onBack,
            ) {
                item(key = "appearance_color") {
                    SmallTitle(text = stringResource(R.string.appearance_group_color))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.appearance_ui_style),
                            items = uiStyleLabels,
                            selectedIndex = appearance.uiStyle.ordinal,
                            onSelectedIndexChange = { index ->
                                uiStyles.getOrNull(index)?.let(::selectUiStyle)
                            },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.appearance_theme_mode),
                            items = themeModeLabels,
                            selectedIndex = appearance.themeMode.ordinal,
                            onSelectedIndexChange = { index ->
                                themeModes.getOrNull(index)?.let { mode ->
                                    update { current -> current.copy(themeMode = mode) }
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.appearance_monet),
                            summary = stringResource(R.string.appearance_monet_summary),
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
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.appearance_palette_style),
                                    items = paletteLabels,
                                    selectedIndex = appearance.paletteStyle.ordinal,
                                    onSelectedIndexChange = { index ->
                                        paletteStyles.getOrNull(index)?.let { style ->
                                            update { current -> current.copy(paletteStyle = style) }
                                        }
                                    },
                                )
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.appearance_accent_color),
                                    items = accentLabels,
                                    selectedIndex = appearance.accentColor.ordinal,
                                    onSelectedIndexChange = { index ->
                                        accentColors.getOrNull(index)?.let { accent ->
                                            update { current -> current.copy(accentColor = accent) }
                                        }
                                    },
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.appearance_pure_black),
                                    summary = stringResource(R.string.appearance_pure_black_summary),
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
                            title = stringResource(R.string.appearance_blur),
                            summary = stringResource(R.string.appearance_blur_summary),
                            checked = appearance.blurEnabled && blurSupported,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(blurEnabled = enabled) }
                            },
                            enabled = blurSupported,
                        )
                        AnimatedVisibility(
                            visible = appearance.blurEnabled && blurSupported,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.appearance_blur_style),
                                summary = blurStyleSummaries[appearance.topBarBlurStyle.ordinal],
                                items = blurStyleLabels,
                                selectedIndex = appearance.topBarBlurStyle.ordinal,
                                onSelectedIndexChange = { index ->
                                    blurStyles.getOrNull(index)?.let { style ->
                                        update { current -> current.copy(topBarBlurStyle = style) }
                                    }
                                },
                            )
                        }
                        SwitchPreference(
                            title = stringResource(R.string.appearance_swipe_dismiss),
                            summary = stringResource(R.string.appearance_swipe_dismiss_summary),
                            checked = appearance.swipeDismissEnabled,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(swipeDismissEnabled = enabled) }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.appearance_predictive_back),
                            summary = stringResource(R.string.appearance_predictive_back_summary),
                            checked = appearance.predictiveBackEnabled,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(predictiveBackEnabled = enabled) }
                            },
                        )
                        SliderPreference(
                            title = stringResource(R.string.appearance_interface_scale),
                            summary = stringResource(R.string.appearance_interface_scale_summary),
                            value = scaleDraft.coerceIn(
                                MIN_INTERFACE_SCALE * 100f,
                                MAX_INTERFACE_SCALE * 100f,
                            ),
                            onValueChange = { scaleDraft = it },
                            valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                            onValueChangeFinished = { commitScale(scaleDraft) },
                            valueText = "${scaleDraft.roundToInt()}%",
                            showKeyPoints = true,
                            keyPoints = listOf(80f, 90f, 100f, 110f),
                            magnetThreshold = 0.01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            onClick = {
                                scaleInput = scaleDraft.roundToInt().toString()
                                showScaleDialog = true
                            },
                            holdDownState = showScaleDialog,
                        )
                    }
                }
            }

            WindowDialog(
                show = showScaleDialog,
                title = stringResource(R.string.appearance_interface_scale_dialog_title),
                summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
                onDismissRequest = { showScaleDialog = false },
            ) {
                Column {
                    TextField(
                        value = scaleInput,
                        onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                        label = stringResource(R.string.appearance_interface_scale_input_label),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                    )
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_confirm),
                        confirmEnabled = parsedScale != null && parsedScale in 80..110,
                        onCancel = { showScaleDialog = false },
                        onConfirm = {
                            parsedScale?.let { commitScale(it.toFloat()) }
                            showScaleDialog = false
                        },
                    )
                }
            }
        }
    }
}
