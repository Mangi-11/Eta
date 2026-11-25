package io.github.mangi.eta.ui.screens.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.validateSpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.components.SpeechLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun SpeechSynthesisScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings
    val ttsLabels = TtsProvider.entries.map { it.label() }

    ScaffoldPage(
        title = stringResource(R.string.speech_synthesis_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@ScaffoldPage
        }
        item(key = "service") {
            SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                GlassDropdownPreference(
                    title = stringResource(R.string.speech_tts_service),
                    items = ttsLabels,
                    selectedIndex = settings.tts.ordinal,
                    backdrop = currentGlassOverlayBackdrop(),
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
                    validateSpeechSettings(
                        store.settings,
                        store.credentials,
                        synthesis = true,
                        hasProviderDeepSeekKey = store.hasProviderDeepSeekKey,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechPreviewSection(store: SpeechSettingsStore) {
    val playback by store.playback.state.collectAsStateWithLifecycle()
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
                SettingsTextField(
                    label = stringResource(R.string.speech_preview_text),
                    value = text,
                    singleLine = false,
                    enabled = !store.saving,
                    inputTransformation = InputTransformation.maxLengthReject(500),
                    onChange = {
                        store.playback.stop()
                        text = it
                    },
                )
            }
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
        SpeechNote(stringResource(R.string.speech_preview_note))
    }
}
