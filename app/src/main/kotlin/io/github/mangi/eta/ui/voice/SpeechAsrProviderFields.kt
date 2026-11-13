package io.github.mangi.eta.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.MimoAsrLanguage
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun MimoAsrConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.mimoAsr
    val languages = MimoAsrLanguage.entries
    val items = languages.map { language ->
        stringResource(
            when (language) {
                MimoAsrLanguage.AUTO -> R.string.speech_language_auto
                MimoAsrLanguage.CHINESE -> R.string.speech_language_chinese
                MimoAsrLanguage.ENGLISH -> R.string.speech_language_english
            },
        )
    }
    val onLanguageChange: (Int) -> Unit = { index ->
        languages.getOrNull(index)?.let {
            store.edit(store.settings.copy(mimoAsr = config.copy(language = it)))
        }
    }
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.mimoAsr,
        onBaseUrl = { store.edit(store.settings.copy(mimoAsr = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.MIMO_ASR, it) },
        note = stringResource(R.string.speech_mimo_asr_connection_note),
    ) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiDropdownPreference(
                    title = stringResource(R.string.speech_recognition_language),
                    items = items,
                    selectedIndex = config.language.ordinal,
                    onSelectedIndexChange = onLanguageChange,
                )
                Divider()
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_recognition_language),
                    items = items,
                    selectedIndex = config.language.ordinal,
                    onSelectedIndexChange = onLanguageChange,
                )
            }
        }
    }
}

@Composable
internal fun DeepSeekAsrConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.deepseekAsr
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.deepseekAsr,
        onBaseUrl = { store.edit(store.settings.copy(deepseekAsr = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.DEEPSEEK_ASR, it) },
        note = stringResource(
            if (store.hasProviderDeepSeekKey) {
                R.string.speech_deepseek_asr_connection_note_with_provider
            } else {
                R.string.speech_deepseek_asr_connection_note
            },
        ),
    )
}
