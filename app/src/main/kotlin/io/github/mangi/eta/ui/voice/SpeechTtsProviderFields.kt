package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.GeminiTtsModel
import io.github.mangi.eta.data.model.MiniMaxTtsModel
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.TtsProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun GeminiConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.geminiTts
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.geminiTts,
        onBaseUrl = { store.edit(store.settings.copy(geminiTts = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.GEMINI_TTS, it) },
        note = stringResource(R.string.speech_gemini_connection_note),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.speech_tts_model),
            items = listOf(
                stringResource(R.string.speech_gemini_flash),
                stringResource(R.string.speech_gemini_flash_lite),
            ),
            selectedIndex = config.model.ordinal,
            onSelectedIndexChange = { index ->
                GeminiTtsModel.entries.getOrNull(index)?.let {
                    store.edit(store.settings.copy(geminiTts = config.copy(model = it)))
                }
            },
        )
    }
}

@Composable
internal fun MiniMaxConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.minimaxTts
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.minimaxTts,
        onBaseUrl = { store.edit(store.settings.copy(minimaxTts = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.MINIMAX_TTS, it) },
        note = stringResource(R.string.speech_minimax_connection_note),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.speech_tts_model),
            items = listOf(
                stringResource(R.string.speech_minimax_turbo),
                stringResource(R.string.speech_minimax_hd),
            ),
            selectedIndex = config.model.ordinal,
            onSelectedIndexChange = { index ->
                MiniMaxTtsModel.entries.getOrNull(index)?.let {
                    store.edit(store.settings.copy(minimaxTts = config.copy(model = it)))
                }
            },
        )
    }
}

@Composable
private fun SpeechApiKeyConnectionSection(
    baseUrl: String,
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    note: String,
    model: @Composable () -> Unit,
) {
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            model()
            SpeechFieldColumn {
                SpeechField("API Key", apiKey, secret = true, onChange = { onApiKey(it.trim()) })
                SpeechField(stringResource(R.string.speech_base_url), baseUrl, onChange = { onBaseUrl(it.trim()) })
            }
        }
        SpeechNote(note)
    }
}

/** 只呈现该服务实际支持的发声参数。 */
@Composable
internal fun SpeechDeliveryFields(store: SpeechSettingsStore) {
    when (store.settings.tts) {
        TtsProvider.GEMINI -> {
            val config = store.settings.geminiTts
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_gemini_style),
                    value = config.style,
                    singleLine = false,
                    onChange = { store.edit(store.settings.copy(geminiTts = config.copy(style = it))) },
                )
            }
        }
        TtsProvider.MINIMAX -> {
            val config = store.settings.minimaxTts
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                .plus(config.speed).distinct().sorted()
            OverlayDropdownPreference(
                title = stringResource(R.string.speech_speed),
                items = speeds.map { "$it×" },
                selectedIndex = speeds.indexOf(config.speed),
                onSelectedIndexChange = { index ->
                    speeds.getOrNull(index)?.let {
                        store.edit(store.settings.copy(minimaxTts = config.copy(speed = it)))
                    }
                },
            )
        }
        TtsProvider.NONE, TtsProvider.QWEN, TtsProvider.DOUBAO -> Unit
    }
}
