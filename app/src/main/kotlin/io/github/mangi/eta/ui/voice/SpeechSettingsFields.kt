package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiPreferenceFooter
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AsrProvider.label(): String = stringResource(
    when (this) {
        AsrProvider.QWEN_REALTIME -> R.string.speech_asr_qwen_realtime
        AsrProvider.QWEN_FLASH -> R.string.speech_asr_qwen_flash
        AsrProvider.QWEN_FILE -> R.string.speech_asr_qwen_file
        AsrProvider.DOUBAO -> R.string.speech_asr_doubao
        AsrProvider.MIMO -> R.string.speech_asr_mimo
        AsrProvider.DEEPSEEK -> R.string.speech_asr_deepseek
    },
)

@Composable
internal fun TtsProvider.label(): String = stringResource(
    when (this) {
        TtsProvider.NONE -> R.string.speech_tts_none
        TtsProvider.QWEN -> R.string.speech_tts_qwen
        TtsProvider.DOUBAO -> R.string.speech_tts_doubao
        TtsProvider.GEMINI -> R.string.speech_tts_gemini
        TtsProvider.MINIMAX -> R.string.speech_tts_minimax
        TtsProvider.MIMO -> R.string.speech_tts_mimo
        TtsProvider.DEEPSEEK -> R.string.speech_tts_deepseek
    },
)

@Composable
internal fun SpeechGroupCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreferenceGroup(
                title = title,
            ) {
                item { androidx.compose.foundation.layout.Column(content = content) }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            if (title != null) {
                SmallTitle(title)
            }
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SpeechNote(text: String, modifier: Modifier = Modifier) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreferenceFooter(text = text, modifier = modifier)
        }
        AppearanceUiStyle.MIUIX -> {
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
internal fun SpeechLoadingState(failure: String?) {
    if (failure != null) {
        ListEmptyState(title = failure, icon = Icons.Rounded.ErrorOutline)
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            InfiniteProgressIndicator(size = 24.dp)
        }
    }
}

@Composable
internal fun SpeechFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun SpeechSaveBlock(store: SpeechSettingsStore, validate: () -> Unit) {
    val uiStyle = LocalUiStyle.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (uiStyle == AppearanceUiStyle.COUI) CouiPageDefaults.horizontalMargin else 12.dp)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text = stringResource(if (store.saving) R.string.speech_saving else R.string.speech_save)
        if (uiStyle == AppearanceUiStyle.COUI) {
            CouiButton(
                text = text,
                enabled = store.loaded && !store.saving,
                role = CouiButtonRole.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = { store.save(validate) },
            )
        } else {
            TextButton(
                text = text,
                enabled = store.loaded && !store.saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { store.save(validate) },
            )
        }
        store.message?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = if (store.messageIsError) StatusError else StatusSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
