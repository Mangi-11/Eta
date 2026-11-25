package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹窗底部 OS4 操作按钮行：取消在左、确认在右，平分整行。
 *
 * @param confirmText 确认按钮文案。
 * @param onCancel 取消回调。
 * @param onConfirm 确认回调。
 * @param modifier 根修饰符。
 * @param cancelText 取消按钮文案。
 * @param cancelEnabled 取消按钮是否可用。
 * @param confirmEnabled 确认按钮是否可用。
 * @param destructive 确认是否为破坏性操作（使用 error 配色）。
 */
@Composable
fun DialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassDialogButton(
            onClick = onCancel,
            enabled = cancelEnabled,
            role = GlassDialogButtonRole.Normal,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = cancelText ?: stringResource(R.string.action_cancel),
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GlassDialogButton(
            onClick = onConfirm,
            enabled = confirmEnabled,
            role = if (destructive) GlassDialogButtonRole.Destructive else GlassDialogButtonRole.Primary,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = confirmText,
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
