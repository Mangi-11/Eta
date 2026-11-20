package io.github.mangi.eta.ui.components.coui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.CancelButton
import io.github.suqi8.coui.kmp.basic.ChipTabRow
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.SearchBar
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme

enum class CouiButtonRole { Primary, Secondary, Destructive, DestructiveFilled }

@Composable
internal fun CouiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: CouiButtonRole = CouiButtonRole.Secondary,
    minHeight: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    val scheme = COUITheme.colorScheme
    val colors = when (role) {
        CouiButtonRole.Primary -> ButtonDefaults.textButtonColorsPrimary()
        CouiButtonRole.Secondary -> ButtonDefaults.textButtonColors()
        CouiButtonRole.Destructive -> ButtonDefaults.textButtonColors(textColor = scheme.error)
        CouiButtonRole.DestructiveFilled -> ButtonDefaults.textButtonColorsPrimary(
            color = scheme.error,
            disabledColor = scheme.errorDisabled,
            textColor = scheme.onError,
        )
    }
    TextButton(
        text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        minHeight = minHeight, insideMargin = contentPadding, colors = colors,
    )
}

private val FieldFocusEasing = CubicBezierEasing(0f, 0f, 0.1f, 1f)

/**
 * 保留调用端的编辑状态，密文通过安全输入框切换显隐。
 */
@Composable
internal fun CouiTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    label: String? = null,
    rightLabel: String? = null,
    rightLabelColor: Color = Color.Unspecified,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    secretVisible: Boolean? = null,
    inputTransformation: InputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    showClearButton: Boolean = singleLine,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = CouiPageDefaults.themeColors()
    var focused by remember { mutableStateOf(false) }
    val focusAlpha = remember { Animatable(0f) }
    val scheme = COUITheme.colorScheme
    val hintColor = scheme.hintNeutral
    val rectShape = RoundedCornerShape(TextFieldDefaults.CornerRadius)
    val rectMargin = TextFieldDefaults.insideMargin(TextFieldMode.Rectangle)
    val rectMinHeight = TextFieldDefaults.minHeight(TextFieldMode.Rectangle)
    val textStyle = TextFieldDefaults.textStyle(TextFieldMode.Rectangle).copy(
        color = if (enabled) colors.title else colors.disabled,
        textAlign = TextAlign.Start,
    )
    LaunchedEffect(focused) {
        if (focused) {
            focusAlpha.animateTo(1f, tween(200, easing = FieldFocusEasing))
        } else {
            focusAlpha.animateTo(0f, tween(150))
        }
    }
    val effectivePlaceholder = placeholder.orEmpty()
    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrEmpty() || !rightLabel.isNullOrEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!label.isNullOrEmpty()) {
                    CouiText(
                        text = label,
                        kind = CouiTextKind.GroupFooter,
                        color = if (focused && enabled) colors.accent else colors.summary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (!rightLabel.isNullOrEmpty()) {
                    CouiText(
                        text = rightLabel,
                        kind = CouiTextKind.GroupFooter,
                        color = if (rightLabelColor != Color.Unspecified) rightLabelColor else colors.summary,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        if (secretVisible == null) {
            TextField(
                state = state,
                modifier = inputModifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                backgroundMode = TextFieldMode.Rectangle,
                label = effectivePlaceholder,
                enabled = enabled,
                showClearButton = showClearButton,
                inputTransformation = inputTransformation,
                keyboardOptions = keyboardOptions,
                onKeyboardAction = onKeyboardAction,
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
                trailingIcon = trailingIcon,
            )
            return@Column
        }
        // 密文输入框沿用 BasicSecureTextField 保留密码无障碍语义与禁复制保护，
        // 同时外框对齐 COUI TextFieldMode.Rectangle，避免同表单中普通与密钥输入框割裂。
        val borderColor = if (!enabled) {
            scheme.disabledTextFieldBorder
        } else {
            lerp(colors.divider, colors.accent, focusAlpha.value.coerceIn(0f, 1f))
        }
        val borderWidth = if (enabled && focusAlpha.value > 0.01f) 1.dp else 0.5.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = rectMinHeight)
                .clip(rectShape)
                .border(width = borderWidth, color = borderColor, shape = rectShape)
                .padding(horizontal = rectMargin.width, vertical = rectMargin.height),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = (22 * minLines.coerceAtLeast(1)).dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (state.text.isEmpty() && effectivePlaceholder.isNotEmpty()) {
                    CouiText(
                        text = effectivePlaceholder,
                        kind = CouiTextKind.Search,
                        color = hintColor,
                        textAlign = TextAlign.Start,
                        maxLines = if (singleLine) 1 else maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicSecureTextField(
                    state = state,
                    modifier = inputModifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    enabled = enabled,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.accent),
                    inputTransformation = inputTransformation,
                    keyboardOptions = keyboardOptions,
                    onKeyboardAction = onKeyboardAction,
                    textObfuscationMode = if (secretVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                )
            }
            if (showClearButton && focused && enabled && state.text.isNotEmpty()) {
                CouiFieldClear(state, colors.summary)
            }
            trailingIcon?.invoke()
        }
    }
}

@Composable
private fun CouiClearGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val unit = size.width / 22f
        drawCircle(color, 9f * unit)
        drawLine(
            Color.Transparent,
            Offset(8.03f, 8.03f) * unit,
            Offset(13.97f, 13.97f) * unit,
            1.6f * unit,
            StrokeCap.Round,
            blendMode = BlendMode.Clear,
        )
        drawLine(
            Color.Transparent,
            Offset(13.97f, 8.03f) * unit,
            Offset(8.03f, 13.97f) * unit,
            1.6f * unit,
            StrokeCap.Round,
            blendMode = BlendMode.Clear,
        )
    }
}

@Composable
private fun CouiFieldClear(state: TextFieldState, color: Color) {
    val clearLabel = stringResource(R.string.page_clear_84fcd7)
    Box(
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = clearLabel }
            .clickable(interactionSource = null, indication = null, role = Role.Button) { state.clearText() },
        contentAlignment = Alignment.Center,
    ) {
        CouiClearGlyph(
            modifier = Modifier.size(18.dp),
            color = color,
        )
    }
}

@Composable
internal fun CouiSearchBarBox(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSearch: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val setExpanded: (Boolean) -> Unit = {
        expanded = it
        if (!it) {
            onQueryChange("")
            keyboard?.hide()
        }
    }
    SearchBar(
        modifier = modifier.fillMaxWidth(),
        insideMargin = DpSize(16.dp, 6.dp),
        expanded = expanded,
        onExpandedChange = setExpanded,
        inputField = {
            InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {
                    keyboard?.hide()
                    onSearch()
                },
                expanded = expanded,
                onExpandedChange = setExpanded,
                label = placeholder,
                enabled = enabled,
            )
        },
        outsideEndAction = {
            CancelButton(
                onClick = { setExpanded(false) },
                text = stringResource(R.string.action_cancel),
            )
        },
        content = {},
    )
}

@Composable
internal fun <T : Any> CouiChipTabs(
    items: List<Pair<T, String>>,
    selectedKey: T,
    onSelectedKeyChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (items.isEmpty()) return
    ChipTabRow(
        tabs = items.map { it.second },
        selectedTabIndex = items.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0),
        onTabSelected = { items.getOrNull(it)?.first?.let(onSelectedKeyChange) },
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
internal fun <T : Any> CouiSegmentedTabs(
    items: List<Pair<T, String>>,
    selectedKey: T,
    onSelectedKeyChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    if (items.isEmpty()) return
    SegmentButton(
        items = items.map { it.second },
        selectedIndex = items.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0),
        onSelectedIndexChange = { items.getOrNull(it)?.first?.let(onSelectedKeyChange) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        height = if (small) 32.dp else io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults.Height,
    )
}
