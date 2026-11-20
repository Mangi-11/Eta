package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.suqi8.coui.kmp.blur.MaterialBlurDefaults
import io.github.suqi8.coui.kmp.blur.MaterialBlurStyle
import io.github.suqi8.coui.kmp.blur.couiMaterialBlur
import io.github.suqi8.coui.kmp.layout.BottomSheetDefaults
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme

internal object CouiPanelDefaults {
    val contentPadding = 24.dp
}

private fun couiPrimaryDialogAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) = DialogButtonBarAction(text = text, enabled = enabled, recommended = true, onClick = onClick)

@Composable
private fun couiPanelBlur(style: (Boolean) -> MaterialBlurStyle): Modifier? {
    val backdrop = LocalCouiPageBackdrop.current ?: return null
    val dark = COUITheme.isDark
    return Modifier.couiMaterialBlur(backdrop = backdrop, style = style(dark))
}

@Composable
internal fun CouiBottomSheet(
    show: Boolean,
    title: String?,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val blur = couiPanelBlur(MaterialBlurDefaults::bottomSheet)
    val centered = LocalWindowInfo.current.containerDpSize.width >= BottomSheetDefaults.LargeScreenWidthThreshold
    OverlayBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        showDragHandle = true,
        backgroundModifier = blur ?: Modifier,
        backgroundColor = if (blur == null) BottomSheetDefaults.backgroundColor() else Color.Transparent,
        materialStroke = blur != null,
    ) {
        // COUI OverlayBottomSheet 仅在外层处理 imePadding()，不消费底部导航栏 Insets；
        // 贴底态统一补上 navigationBars 排除 IME 后的底边距，键盘弹起时不双倍留白。
        if (centered) {
            content()
        } else {
            Box(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.exclude(WindowInsets.ime).only(WindowInsetsSides.Bottom),
                ),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun CouiAlert(
    visible: Boolean,
    title: String?,
    message: String?,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    positive: DialogButtonBarAction?,
    negative: DialogButtonBarAction? = null,
    neutral: DialogButtonBarAction? = null,
    scrollableContent: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val blur = couiPanelBlur(MaterialBlurDefaults::dialog)
    OverlayDialog(
        show = visible,
        title = title,
        summary = message,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        backgroundModifier = blur ?: Modifier,
        backgroundColor = if (blur == null) DialogDefaults.backgroundColor() else Color.Transparent,
        materialStroke = blur != null,
        customContent = content?.let { body ->
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (scrollableContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = CouiPanelDefaults.contentPadding, vertical = 8.dp),
                    content = body,
                )
            }
        },
    ) {
        DialogButtonBar(negative = negative, positive = positive, neutral = neutral)
    }
}

@Composable
internal fun CouiConfirmDialog(
    visible: Boolean,
    title: String?,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    cancelText: String = stringResource(R.string.action_cancel),
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    destructive: Boolean = false,
    scrollableContent: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val confirm = couiPrimaryDialogAction(confirmText, onConfirm, confirmEnabled)
    CouiAlert(
        visible = visible,
        title = title,
        message = message,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        positive = if (destructive) null else confirm,
        negative = DialogButtonBarAction(cancelText, enabled = cancelEnabled, onClick = onDismissRequest),
        neutral = if (destructive) confirm.copy(recommended = false) else null,
        scrollableContent = scrollableContent,
        content = content,
    )
}

@Composable
internal fun CouiNoticeDialog(
    visible: Boolean,
    title: String?,
    message: String?,
    confirmText: String = stringResource(R.string.action_close),
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    scrollableContent: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    CouiAlert(
        visible = visible,
        title = title,
        message = message,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        positive = couiPrimaryDialogAction(confirmText, onDismissRequest),
        scrollableContent = scrollableContent,
        content = content,
    )
}

@Composable
internal fun CouiInputDialog(
    visible: Boolean,
    title: String?,
    message: String? = null,
    state: TextFieldState,
    inputTransformation: InputTransformation? = null,
    placeholder: String? = null,
    counter: String? = null,
    counterIsError: Boolean = false,
    confirmText: String,
    confirmEnabled: Boolean = true,
    cancelText: String = stringResource(R.string.action_cancel),
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    autoFocus: Boolean = true,
) {
    CouiAlert(
        visible = visible,
        title = title,
        message = message,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        positive = couiPrimaryDialogAction(confirmText, onConfirm, confirmEnabled),
        negative = DialogButtonBarAction(cancelText, onClick = onDismissRequest),
    ) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        CouiTextField(
            state = state,
            inputModifier = Modifier.focusRequester(focusRequester),
            placeholder = placeholder,
            inputTransformation = inputTransformation,
            rightLabel = counter,
            rightLabelColor = if (counterIsError) COUITheme.colorScheme.error else Color.Unspecified,
        )
        LaunchedEffect(visible, autoFocus) {
            if (visible && autoFocus && focusRequester.requestFocus()) keyboard?.show()
        }
    }
}
