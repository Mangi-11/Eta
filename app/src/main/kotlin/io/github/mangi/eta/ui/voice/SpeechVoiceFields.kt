package io.github.mangi.eta.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import top.yukonga.miuix.kmp.basic.DropdownItem

/** 播报音色：预设音色或自定义音色 ID。 */
@Composable
internal fun SpeechVoiceSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val voice = when (settings.tts) {
        TtsProvider.NONE -> return
        TtsProvider.QWEN -> settings.qwenVoice
        TtsProvider.DOUBAO -> settings.doubaoVoice
        TtsProvider.GEMINI -> settings.geminiTts.voice
        TtsProvider.MINIMAX -> settings.minimaxTts.voice
    }
    val presets = SpeechVoicePresets.forProvider(settings.tts)
    val presetIndex = presets.indexOfFirst { it.id == voice }
    var editingCustom by rememberSaveable(settings.tts) { mutableStateOf(false) }
    val custom = editingCustom || presetIndex < 0
    val change: (String) -> Unit = {
        store.edit(when (settings.tts) {
            TtsProvider.NONE -> settings
            TtsProvider.QWEN -> settings.copy(qwenVoice = it)
            TtsProvider.DOUBAO -> settings.copy(doubaoVoice = it)
            TtsProvider.GEMINI -> settings.copy(geminiTts = settings.geminiTts.copy(voice = it))
            TtsProvider.MINIMAX -> settings.copy(minimaxTts = settings.minimaxTts.copy(voice = it))
        })
    }

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.speech_group_voice))
        EtaPreferenceGroup {
            EtaDropdownPreference(
                title = stringResource(R.string.speech_voice),
                items = presets.map { preset ->
                    DropdownItem(text = stringResource(preset.name), summary = stringResource(preset.summary))
                } + DropdownItem(text = stringResource(R.string.speech_voice_custom)),
                selectedIndex = if (custom) presets.size else presetIndex,
                useWindow = false,
                onSelectedIndexChange = { index ->
                    val preset = presets.getOrNull(index)
                    editingCustom = preset == null
                    if (preset != null) change(preset.id) else if (!custom) change("")
                },
            )
            AnimatedVisibility(visible = custom) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
                    SpeechFieldColumn {
                        SpeechField(
                            label = stringResource(R.string.speech_voice_id),
                            value = voice,
                            onChange = { change(it.trim()) },
                        )
                    }
                }
            }
            SpeechDeliveryFields(store)
        }
        SpeechNote(
            stringResource(
                when (settings.tts) {
                    TtsProvider.QWEN -> R.string.speech_qwen_voice_note
                    TtsProvider.DOUBAO -> R.string.speech_doubao_voice_note
                    TtsProvider.GEMINI -> R.string.speech_gemini_voice_note
                    TtsProvider.MINIMAX -> R.string.speech_minimax_voice_note
                    TtsProvider.NONE -> error("No synthesis provider")
                },
            ),
        )
    }
}
