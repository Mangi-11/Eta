package io.github.mangi.eta.ui.screens.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.runtime.getValue

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SpeechSettingsRepository.settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    val current = settings

    ScaffoldPage(
        title = stringResource(R.string.speech_settings_title),
        onBack = onBack,
    ) {
        if (current == null) {
            item(key = "loading") { SpeechLoadingState(failure = null) }
            return@ScaffoldPage
        }
        item(key = "service") {
            SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                ArrowPreference(
                    title = stringResource(R.string.speech_recognition_title),
                    summary = if (current.recognitionEnabled) current.asr.label()
                    else stringResource(R.string.speech_recognition_disabled),
                    onClick = { onNavigate(AppRoute.SpeechRecognition) },
                )
                ArrowPreference(
                    title = stringResource(R.string.speech_synthesis_title),
                    summary = current.tts.label(),
                    onClick = { onNavigate(AppRoute.SpeechSynthesis) },
                )
            }
        }
        item(key = "assistant") {
            Column {
                SpeechGroupCard(title = stringResource(R.string.speech_group_assistant)) {
                    SwitchPreference(
                        title = stringResource(R.string.speech_auto_speak),
                        summary = stringResource(R.string.speech_auto_speak_summary),
                        checked = current.autoSpeak,
                        enabled = current.tts != TtsProvider.NONE,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                SpeechSettingsRepository.save(current.copy(autoSpeak = enabled))
                            }
                        },
                    )
                }
                SpeechNote(stringResource(R.string.speech_overview_note))
            }
        }
    }
}
