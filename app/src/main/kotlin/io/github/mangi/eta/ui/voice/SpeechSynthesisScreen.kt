package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechSynthesisScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings
    val ttsLabels = TtsProvider.entries.map { it.label() }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.speech_synthesis_title),
                onBack = onBack,
            ) {
                if (!store.loaded) {
                    SpeechLoadingState(store.message)
                    return@CouiScaffoldPage
                }
                SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_tts_service),
                        items = ttsLabels,
                        selectedIndex = settings.tts.ordinal,
                        onSelectedIndexChange = { index ->
                            TtsProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(tts = it)) }
                        },
                    )
                }
                if (settings.tts != TtsProvider.NONE) {
                    SpeechConnectionSection(store, synthesis = true)
                    SpeechVoiceSection(store)
                    SpeechPreviewSection(store)
                }
                SpeechSaveBlock(store) {
                    if (store.settings.tts != TtsProvider.NONE) {
                        validateSpeechSettings(store.settings, store.credentials, synthesis = true)
                    }
                }
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.speech_synthesis_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (!store.loaded) {
                    item(key = "loading") { SpeechLoadingState(store.message) }
                    return@MiuixScaffoldPage
                }
                item(key = "service") {
                    SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.speech_tts_service),
                            items = ttsLabels,
                            selectedIndex = settings.tts.ordinal,
                            onSelectedIndexChange = { index ->
                                TtsProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(tts = it)) }
                            },
                        )
                    }
                }
                if (settings.tts != TtsProvider.NONE) {
                    item(key = "connection") {
                        SpeechConnectionSection(store, synthesis = true)
                    }
                    item(key = "voice") {
                        SpeechVoiceSection(store)
                    }
                    item(key = "preview") {
                        SpeechPreviewSection(store)
                    }
                }
                item(key = "save") {
                    SpeechSaveBlock(store) {
                        if (store.settings.tts != TtsProvider.NONE) {
                            validateSpeechSettings(store.settings, store.credentials, synthesis = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechPreviewSection(store: SpeechSettingsStore) {
    val playback by store.playback.state.collectAsState()
    val playing = playback.messageId != null
    val defaultText = stringResource(R.string.speech_preview_default_text)
    var text by rememberSaveable { mutableStateOf(defaultText) }
    val title = stringResource(if (playing) R.string.speech_preview_stop else R.string.speech_preview_voice)
    val summary = playback.error ?: if (playback.loading) stringResource(R.string.speech_preview_loading) else null
    val onPreviewClick: () -> Unit = {
        if (playing) {
            store.playback.stop()
        } else {
            store.playback.speak(
                "preview",
                text,
                store.settings,
                store.credentials,
            )
        }
    }

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_preview_voice)) {
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_preview_text),
                    value = text,
                    singleLine = false,
                    enabled = !store.saving,
                    onChange = {
                        if (it.length <= 500) {
                            store.playback.stop()
                            text = it
                        }
                    },
                )
            }
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    val dialogColors = CouiDialogDefaults.themeColors()
                    Divider()
                    CouiPreference(
                        title = title,
                        summary = summary,
                        summaryColor = if (playback.error != null) dialogColors.destructive else Color.Unspecified,
                        enabled = !store.saving && text.isNotBlank(),
                        showChevron = false,
                        onClick = onPreviewClick,
                    )
                }
                AppearanceUiStyle.MIUIX -> {
                    BasicComponent(
                        title = title,
                        summary = summary,
                        summaryColor = if (playback.error != null) {
                            BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                        } else {
                            BasicComponentDefaults.summaryColor()
                        },
                        enabled = !store.saving && text.isNotBlank(),
                        onClick = onPreviewClick,
                    )
                }
            }
        }
        SpeechNote(stringResource(R.string.speech_preview_note))
    }
}
