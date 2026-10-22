package io.github.mangi.eta.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
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
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.DoubaoSpeechConfig
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechRegion
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    if (!synthesis) {
        QwenDoubaoConnectionSection(store, synthesis = false)
        return
    }
    when (store.settings.tts) {
        TtsProvider.NONE -> Unit
        TtsProvider.QWEN, TtsProvider.DOUBAO -> QwenDoubaoConnectionSection(store, synthesis = true)
        TtsProvider.GEMINI -> GeminiConnectionSection(store)
        TtsProvider.MINIMAX -> MiniMaxConnectionSection(store)
    }
}

/** 识别与播报共用的服务商连接配置：地域或旧版鉴权、凭据与高级地址设置。 */
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

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.speech_group_connection))
        EtaPreferenceGroup {
            if (qwen) {
                val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                val change: (QwenSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(qwenTts = it) else settings.copy(qwenAsr = it))
                }
                EtaOverlayDropdownPreference(
                    title = stringResource(R.string.speech_region),
                    items = listOf(
                        stringResource(R.string.speech_region_beijing),
                        stringResource(R.string.speech_region_singapore),
                    ),
                    selectedIndex = config.region.ordinal,
                    onSelectedIndexChange = { index ->
                        SpeechRegion.entries.getOrNull(index)?.let { change(config.copy(region = it)) }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
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

            EtaPreferenceDivider(hasLeading = false)
            val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)
            EtaPreference(
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
            AnimatedVisibility(visible = advanced) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
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
                        EtaSwitchPreference(
                            title = stringResource(R.string.speech_legacy_auth),
                            checked = config.legacyAuth,
                            onCheckedChange = { enabled ->
                                store.edit(
                                    if (synthesis) settings.copy(doubaoTts = config.copy(legacyAuth = enabled))
                                    else settings.copy(doubaoAsr = config.copy(legacyAuth = enabled)),
                                )
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
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
