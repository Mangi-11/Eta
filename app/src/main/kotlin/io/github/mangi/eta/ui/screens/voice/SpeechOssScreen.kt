package io.github.mangi.eta.ui.screens.voice

import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.SpeechLifecycle

@Composable
internal fun SpeechOssScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings

    ScaffoldPage(
        title = stringResource(R.string.speech_oss_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@ScaffoldPage
        }
        item(key = "note") {
            SpeechNote(stringResource(R.string.speech_oss_note))
        }
        item(key = "storage") {
            SpeechGroupCard(title = stringResource(R.string.speech_group_storage)) {
                SpeechFieldColumn {
                    SettingsTextField(
                        label = stringResource(R.string.speech_oss_region),
                        value = settings.oss.region,
                        onChange = { store.edit(settings.copy(oss = settings.oss.copy(region = it.trim()))) },
                    )
                    SettingsTextField(
                        label = "Endpoint",
                        value = settings.oss.endpoint,
                        onChange = { store.edit(settings.copy(oss = settings.oss.copy(endpoint = it.trim()))) },
                    )
                    SettingsTextField(
                        label = "Bucket",
                        value = settings.oss.bucket,
                        onChange = { store.edit(settings.copy(oss = settings.oss.copy(bucket = it.trim()))) },
                    )
                    SettingsTextField(
                        label = stringResource(R.string.speech_oss_prefix),
                        value = settings.oss.prefix,
                        onChange = { store.edit(settings.copy(oss = settings.oss.copy(prefix = it.trim()))) },
                    )
                }
            }
        }
        item(key = "credentials") {
            SpeechGroupCard(title = stringResource(R.string.speech_group_credentials)) {
                SpeechFieldColumn {
                    SettingsTextField(
                        label = "AccessKey ID",
                        value = store.credentials.ossAccessKeyId,
                        secret = true,
                        onChange = { store.credential(SpeechCredentialField.OSS_KEY_ID, it.trim()) },
                    )
                    SettingsTextField(
                        label = "AccessKey Secret",
                        value = store.credentials.ossAccessKeySecret,
                        secret = true,
                        onChange = { store.credential(SpeechCredentialField.OSS_KEY_SECRET, it.trim()) },
                    )
                }
            }
        }
        item(key = "save") {
            SpeechSaveBlock(store) {}
        }
    }
}
