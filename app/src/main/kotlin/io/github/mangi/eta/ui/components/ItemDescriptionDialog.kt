package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ItemDescriptionDialog(
    show: Boolean,
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            var retainedTitle by remember { mutableStateOf(title) }
            var retainedDescription by remember { mutableStateOf(description) }
            if (show) {
                retainedTitle = title
                retainedDescription = description
            }
            CouiNoticeDialog(
                visible = show,
                title = retainedTitle,
                message = retainedDescription,
                confirmText = stringResource(R.string.action_close),
                onDismissRequest = onDismiss,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            var retainedTitle by remember { mutableStateOf(title) }
            var retainedDescription by remember { mutableStateOf(description) }
            if (show) {
                retainedTitle = title
                retainedDescription = description
            }
            GlassDialog(
                show = show,
                title = retainedTitle,
                backdrop = currentGlassOverlayBackdrop(),
                onDismissRequest = onDismiss,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = retainedDescription,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    )
                    GlassDialogButton(
                        onClick = onDismiss,
                        role = GlassDialogButtonRole.Normal,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            style = MiuixTheme.textStyles.button,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
