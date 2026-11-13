package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechRecognitionScreen(onBack: () -> Unit, onOpenOss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings
    val asrLabels = AsrProvider.entries.map { it.label() }
    val asrNoteRes = when (settings.asr) {
        AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO -> R.string.speech_asr_note_streaming
        AsrProvider.QWEN_FLASH -> R.string.speech_asr_note_flash
        AsrProvider.QWEN_FILE -> R.string.speech_asr_note_file
        AsrProvider.MIMO -> R.string.speech_asr_note_mimo
        AsrProvider.DEEPSEEK -> R.string.speech_asr_note_deepseek
    }
    val showQwenLanguage = when (settings.asr) {
        AsrProvider.QWEN_REALTIME, AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE -> true
        AsrProvider.DOUBAO, AsrProvider.MIMO, AsrProvider.DEEPSEEK -> false
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.speech_recognition_title),
                onBack = onBack,
            ) {
                if (!store.loaded) {
                    SpeechLoadingState(store.message)
                    return@CouiScaffoldPage
                }
                SpeechGroupCard {
                    CouiSwitchPreference(
                        title = stringResource(R.string.speech_recognition_enabled),
                        checked = settings.recognitionEnabled,
                        onCheckedChange = { store.edit(settings.copy(recognitionEnabled = it)) },
                    )
                }
                if (settings.recognitionEnabled) {
                    SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                        CouiDropdownPreference(
                            title = stringResource(R.string.speech_service_and_model),
                            items = asrLabels,
                            selectedIndex = settings.asr.ordinal,
                            onSelectedIndexChange = { index ->
                                AsrProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(asr = it)) }
                            },
                        )
                    }
                    SpeechNote(stringResource(asrNoteRes))
                    SpeechConnectionSection(store, synthesis = false)
                    if (showQwenLanguage) {
                        SpeechGroupCard {
                            SpeechFieldColumn {
                                SpeechField(
                                    label = stringResource(R.string.speech_language_hint),
                                    value = settings.language,
                                    onChange = { store.edit(settings.copy(language = it.trim())) },
                                )
                            }
                        }
                    }
                    SpeechResultSection(store)
                    if (settings.asr == AsrProvider.QWEN_FILE) {
                        SpeechGroupCard {
                            CouiPreference(
                                title = stringResource(R.string.speech_oss_title),
                                summary = settings.oss.bucket.ifBlank { stringResource(R.string.speech_oss_configure) },
                                onClick = onOpenOss,
                            )
                        }
                    }
                    SpeechRecognitionTestSection(store)
                } else {
                    SpeechNote(stringResource(R.string.speech_recognition_disabled_note))
                }
                SpeechSaveBlock(store) {
                    validateSpeechSettings(
                        store.settings,
                        store.credentials,
                        synthesis = false,
                        hasProviderDeepSeekKey = store.hasProviderDeepSeekKey,
                    )
                }
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.speech_recognition_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (!store.loaded) {
                    item(key = "loading") { SpeechLoadingState(store.message) }
                    return@MiuixScaffoldPage
                }
                item(key = "recognition_enabled") {
                    SpeechGroupCard {
                        SwitchPreference(
                            title = stringResource(R.string.speech_recognition_enabled),
                            checked = settings.recognitionEnabled,
                            onCheckedChange = { store.edit(settings.copy(recognitionEnabled = it)) },
                        )
                    }
                }
                if (settings.recognitionEnabled) {
                    item(key = "service") {
                        Column {
                            SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.speech_service_and_model),
                                    items = asrLabels,
                                    selectedIndex = settings.asr.ordinal,
                                    onSelectedIndexChange = { index ->
                                        AsrProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(asr = it)) }
                                    },
                                )
                            }
                            SpeechNote(stringResource(asrNoteRes))
                        }
                    }
                    item(key = "connection") {
                        SpeechConnectionSection(store, synthesis = false)
                    }
                    if (showQwenLanguage) {
                        item(key = "language") {
                            SpeechGroupCard {
                                SpeechFieldColumn {
                                    SpeechField(
                                        label = stringResource(R.string.speech_language_hint),
                                        value = settings.language,
                                        onChange = { store.edit(settings.copy(language = it.trim())) },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "result") {
                        SpeechResultSection(store)
                    }
                    if (settings.asr == AsrProvider.QWEN_FILE) {
                        item(key = "oss") {
                            SpeechGroupCard {
                                ArrowPreference(
                                    title = stringResource(R.string.speech_oss_title),
                                    summary = settings.oss.bucket.ifBlank { stringResource(R.string.speech_oss_configure) },
                                    onClick = onOpenOss,
                                )
                            }
                        }
                    }
                    item(key = "test") {
                        SpeechRecognitionTestSection(store)
                    }
                } else {
                    item(key = "recognition_disabled_note") {
                        SpeechNote(stringResource(R.string.speech_recognition_disabled_note))
                    }
                }
                item(key = "save") {
                    SpeechSaveBlock(store) {
                        validateSpeechSettings(store.settings, store.credentials, synthesis = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechResultSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val streaming = settings.asr == AsrProvider.QWEN_REALTIME || settings.asr == AsrProvider.DOUBAO
    val options = SpeechSettings.AUTO_SEND_SILENCE_OPTIONS
    val optionLabels = options.map { millis ->
        if (millis == 0) {
            stringResource(R.string.speech_auto_send_off)
        } else {
            stringResource(R.string.speech_auto_send_seconds, (millis / 1_000f).toString().removeSuffix(".0"))
        }
    }
    val selectedOptionIndex = options.indexOf(settings.autoSendSilenceMs)
        .takeIf { it >= 0 }
        ?: options.indexOf(SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS)
    val uiStyle = LocalUiStyle.current

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_result)) {
            if (streaming) {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_auto_send_title),
                        items = optionLabels,
                        selectedIndex = selectedOptionIndex,
                        onSelectedIndexChange = { index ->
                            options.getOrNull(index)?.let { store.edit(settings.copy(autoSendSilenceMs = it)) }
                        },
                    )
                    Divider()
                } else {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.speech_auto_send_title),
                        items = optionLabels,
                        selectedIndex = selectedOptionIndex,
                        onSelectedIndexChange = { index ->
                            options.getOrNull(index)?.let { store.edit(settings.copy(autoSendSilenceMs = it)) }
                        },
                    )
                }
            }
            if (uiStyle == AppearanceUiStyle.COUI) {
                CouiSwitchPreference(
                    title = stringResource(R.string.speech_refine_title),
                    summary = stringResource(R.string.speech_refine_summary),
                    checked = settings.refineTranscript,
                    onCheckedChange = { store.edit(settings.copy(refineTranscript = it)) },
                )
            } else {
                SwitchPreference(
                    title = stringResource(R.string.speech_refine_title),
                    summary = stringResource(R.string.speech_refine_summary),
                    checked = settings.refineTranscript,
                    onCheckedChange = { store.edit(settings.copy(refineTranscript = it)) },
                )
            }
        }
        if (streaming) SpeechNote(stringResource(R.string.speech_auto_send_note))
    }
}

@Composable
private fun SpeechRecognitionTestSection(store: SpeechSettingsStore) {
    val state by store.recognition.state.collectAsState()
    val request = rememberSpeechPermission { store.recognition.start(store.settings, store.credentials) }
    val summary = state.error
        ?: state.preview.take(200).ifBlank { state.progress }.ifBlank { null }
    val title = stringResource(if (state.active) R.string.speech_test_finish else R.string.speech_test_recognition)

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_test_recognition)) {
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    CouiPreference(
                        title = title,
                        summary = summary,
                        summaryColor = if (state.error != null) StatusError else Color.Unspecified,
                        enabled = !store.saving,
                        showChevron = false,
                        trailing = if (state.active) {
                            {
                                CouiButton(
                                    text = stringResource(R.string.action_cancel),
                                    onClick = { store.recognition.cancel() },
                                    minHeight = 32.dp,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = { if (state.active) store.recognition.finish() else request() },
                    )
                }
                AppearanceUiStyle.MIUIX -> {
                    BasicComponent(
                        title = title,
                        summary = summary,
                        summaryColor = BasicComponentDefaults.summaryColor(
                            color = if (state.error != null) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        ),
                        enabled = !store.saving,
                        onClick = { if (state.active) store.recognition.finish() else request() },
                        endActions = if (state.active) {
                            {
                                TextButton(
                                    text = stringResource(R.string.action_cancel),
                                    onClick = { store.recognition.cancel() },
                                    minWidth = 0.dp,
                                    minHeight = 32.dp,
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        } else null,
                    )
                }
            }
        }
        SpeechNote(stringResource(R.string.speech_test_note))
    }
}
