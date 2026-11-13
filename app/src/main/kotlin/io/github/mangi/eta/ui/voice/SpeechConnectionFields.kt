package io.github.mangi.eta.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.DoubaoSpeechConfig
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechRegion
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    if (!synthesis) {
        when (store.settings.asr) {
            AsrProvider.MIMO -> MimoAsrConnectionSection(store)
            AsrProvider.DEEPSEEK -> DeepSeekAsrConnectionSection(store)
            AsrProvider.QWEN_REALTIME, AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE, AsrProvider.DOUBAO ->
                QwenDoubaoConnectionSection(store, synthesis = false)
        }
        return
    }
    when (store.settings.tts) {
        TtsProvider.NONE -> Unit
        TtsProvider.QWEN, TtsProvider.DOUBAO -> QwenDoubaoConnectionSection(store, synthesis = true)
        TtsProvider.GEMINI -> GeminiConnectionSection(store)
        TtsProvider.MINIMAX -> MiniMaxConnectionSection(store)
        TtsProvider.MIMO -> MimoTtsConnectionSection(store)
        TtsProvider.DEEPSEEK -> DeepSeekTtsConnectionSection(store)
    }
}

@Composable
internal fun SpeechApiKeyConnectionSection(
    baseUrl: String,
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    note: String,
    options: @Composable () -> Unit = {},
) {
    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_connection)) {
            options()
            SpeechFieldColumn {
                SpeechField("API Key", apiKey, secret = true, onChange = { onApiKey(it.trim()) })
                SpeechField(stringResource(R.string.speech_base_url), baseUrl, onChange = { onBaseUrl(it.trim()) })
            }
        }
        SpeechNote(note)
    }
}

@Composable
private fun QwenDoubaoConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val settings = store.settings
    val qwen = if (synthesis) settings.tts == TtsProvider.QWEN else settings.asr != AsrProvider.DOUBAO
    val keyField = when {
        qwen && synthesis -> SpeechCredentialField.QWEN_TTS
        qwen -> SpeechCredentialField.QWEN_ASR
        synthesis -> SpeechCredentialField.DOUBAO_TTS
        else -> SpeechCredentialField.DOUBAO_ASR
    }
    val secret = when (keyField) {
        SpeechCredentialField.QWEN_TTS -> store.credentials.qwenTts
        SpeechCredentialField.QWEN_ASR -> store.credentials.qwenAsr
        SpeechCredentialField.DOUBAO_TTS -> store.credentials.doubaoTts
        SpeechCredentialField.DOUBAO_ASR -> store.credentials.doubaoAsr
        else -> error("Not a provider credential")
    }
    var advanced by rememberSaveable(keyField) { mutableStateOf(false) }
    val uiStyle = LocalUiStyle.current

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_connection)) {
            if (qwen) {
                val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                val change: (QwenSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(qwenTts = it) else settings.copy(qwenAsr = it))
                }
                val regionItems = listOf(
                    stringResource(R.string.speech_region_beijing),
                    stringResource(R.string.speech_region_singapore),
                )
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_region),
                        items = regionItems,
                        selectedIndex = config.region.ordinal,
                        onSelectedIndexChange = { index ->
                            SpeechRegion.entries.getOrNull(index)?.let { change(config.copy(region = it)) }
                        },
                    )
                    Divider()
                } else {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.speech_region),
                        items = regionItems,
                        selectedIndex = config.region.ordinal,
                        onSelectedIndexChange = { index ->
                            SpeechRegion.entries.getOrNull(index)?.let { change(config.copy(region = it)) }
                        },
                    )
                }
                SpeechFieldColumn {
                    SpeechField(
                        label = "API Key",
                        value = secret,
                        secret = true,
                        onChange = { store.credential(keyField, it.trim()) },
                    )
                }
            } else {
                val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
                val change: (DoubaoSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(doubaoTts = it) else settings.copy(doubaoAsr = it))
                }
                SpeechFieldColumn {
                    SpeechField(
                        label = stringResource(
                            if (config.legacyAuth) R.string.speech_access_token else R.string.speech_api_key,
                        ),
                        value = secret,
                        secret = true,
                        onChange = { store.credential(keyField, it.trim()) },
                    )
                    if (config.legacyAuth) {
                        SpeechField(
                            label = stringResource(R.string.speech_app_id),
                            value = config.appId,
                            onChange = { change(config.copy(appId = it.trim())) },
                        )
                    }
                }
            }

            val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)
            if (uiStyle == AppearanceUiStyle.COUI) {
                val colors = CouiPageDefaults.themeColors()
                Divider()
                CouiPreference(
                    title = stringResource(R.string.speech_advanced),
                    showChevron = false,
                    trailing = {
                        CouiIcon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(
                                if (advanced) R.string.speech_collapse else R.string.speech_expand,
                            ),
                            tint = colors.summary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(chevronRotation),
                        )
                    },
                    onClick = { advanced = !advanced },
                )
            } else {
                BasicComponent(
                    title = stringResource(R.string.speech_advanced),
                    endActions = {
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(
                                if (advanced) R.string.speech_collapse else R.string.speech_expand,
                            ),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.rotate(chevronRotation),
                        )
                    },
                    onClick = { advanced = !advanced },
                )
            }
            AnimatedVisibility(visible = advanced) {
                Column {
                    if (qwen) {
                        val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_qwen_base_url_hint),
                                value = config.baseUrl,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(qwenTts = config.copy(baseUrl = value.trim()))
                                        else settings.copy(qwenAsr = config.copy(baseUrl = value.trim())),
                                    )
                                },
                            )
                        }
                    } else {
                        val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
                        val onLegacyChange: (Boolean) -> Unit = { enabled ->
                            store.edit(
                                if (synthesis) settings.copy(doubaoTts = config.copy(legacyAuth = enabled))
                                else settings.copy(doubaoAsr = config.copy(legacyAuth = enabled)),
                            )
                        }
                        if (uiStyle == AppearanceUiStyle.COUI) {
                            Divider()
                            CouiSwitchPreference(
                                title = stringResource(R.string.speech_legacy_auth),
                                checked = config.legacyAuth,
                                onCheckedChange = onLegacyChange,
                            )
                        } else {
                            SwitchPreference(
                                title = stringResource(R.string.speech_legacy_auth),
                                checked = config.legacyAuth,
                                onCheckedChange = onLegacyChange,
                            )
                        }
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_base_url),
                                value = config.baseUrl,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(doubaoTts = config.copy(baseUrl = value.trim()))
                                        else settings.copy(doubaoAsr = config.copy(baseUrl = value.trim())),
                                    )
                                },
                            )
                            SpeechField(
                                label = stringResource(R.string.speech_resource_id_hint),
                                value = config.resourceId,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(doubaoTts = config.copy(resourceId = value.trim()))
                                        else settings.copy(doubaoAsr = config.copy(resourceId = value.trim())),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (advanced) {
            SpeechNote(
                stringResource(
                    when {
                        qwen -> R.string.speech_qwen_base_url_note
                        synthesis -> R.string.speech_doubao_tts_note
                        else -> R.string.speech_doubao_asr_note
                    },
                ),
            )
        }
    }
}
