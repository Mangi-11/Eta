package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SpeechSettingsRepository.settingsFlow().collectAsState(initial = null)
    val current = settings

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.speech_settings_title),
                onBack = onBack,
            ) {
                if (current == null) {
                    SpeechLoadingState(failure = null)
                    return@CouiScaffoldPage
                }
                SpeechGroupCard(title = stringResource(R.string.speech_group_service)) {
                    CouiPreference(
                        title = stringResource(R.string.speech_recognition_title),
                        summary = if (current.recognitionEnabled) current.asr.label()
                        else stringResource(R.string.speech_recognition_disabled),
                        onClick = { onNavigate(AppRoute.SpeechRecognition) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.speech_synthesis_title),
                        summary = current.tts.label(),
                        onClick = { onNavigate(AppRoute.SpeechSynthesis) },
                    )
                }
                SpeechGroupCard(title = stringResource(R.string.speech_group_assistant)) {
                    CouiSwitchPreference(
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
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.speech_settings_title),
                onBack = onBack,
            ) {
                if (current == null) {
                    item(key = "loading") { SpeechLoadingState(failure = null) }
                    return@MiuixScaffoldPage
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
    }
}
