package io.github.mangi.eta.ui.components.coui

import android.graphics.Paint
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.chip.ChipTabRow
import com.itos.coui.chip.CouiChipTabItem
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionColors
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogMaterial
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.InputDialog
import com.itos.coui.effect.CouiContour
import com.itos.coui.effect.CouiContourStyle
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.couiTouchMotion
import com.itos.coui.motion.rememberCouiPressMotion
import com.itos.coui.motion.rememberCouiTouchMotion
import com.itos.coui.search.SearchBar
import com.itos.coui.search.rememberCouiSearchBarState
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.LocalBlurEnabled

enum class CouiButtonRole {
    Primary,
    Secondary,
    Destructive,
    DestructiveFilled,
}

/**
 * COUI 页面胶囊按钮，使用 COUI 公开的 CouiCapsuleShape、TouchMotion、PressMotion、Spotlight、
 * CouiContour(Button) 与 couiButtonFeedback 组合。
 */
@Composable
internal fun CouiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: CouiButtonRole = CouiButtonRole.Secondary,
    minHeight: Dp = CouiDialogDefaults.buttonHeight,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorSchemeFor(dark)
    val dialogColors = CouiDialogDefaults.themeColors(dark)
    val isPrimaryLike = role == CouiButtonRole.Primary || role == CouiButtonRole.DestructiveFilled
    val fillColor = when (role) {
        CouiButtonRole.Primary -> if (enabled) scheme.accent else scheme.accent.copy(alpha = 0.3f)
        CouiButtonRole.DestructiveFilled -> if (enabled) dialogColors.destructive else dialogColors.destructive.copy(alpha = 0.3f)
        CouiButtonRole.Secondary, CouiButtonRole.Destructive -> if (enabled) {
            if (dark) Color(0xFF353535) else Color(0xFFEBEBEB)
        } else {
            if (dark) Color(0x1AFFFFFF) else Color(0x14000000)
        }
    }
    val textColor = when (role) {
        CouiButtonRole.Primary, CouiButtonRole.DestructiveFilled ->
            if (enabled) Color.White else Color(0x4DFFFFFF)
        CouiButtonRole.Destructive ->
            if (enabled) dialogColors.destructive else dialogColors.disabledAction
        CouiButtonRole.Secondary ->
            if (enabled) scheme.onSurface else dialogColors.disabledAction
    }

    val source = remember { MutableInteractionSource() }
    val motion = rememberCouiTouchMotion(enabled)
    val press = rememberCouiPressMotion()
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = enabled,
        dark = dark,
        preset = if (isPrimaryLike) SpotlightPreset.OpaqueMedium1 else SpotlightPreset.OpaqueMedium2,
        cancelOnConsumedMove = true,
    )
    val glow = LocalCouiContourGlowEnabled.current
    val contour = remember(glow) {
        if (glow && Build.VERSION.SDK_INT >= 33) CouiContour(style = CouiContourStyle.Button) else null
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .graphicsLayer {
                scaleX = motion.scaleX.value + press.scale.value - 1f
                scaleY = motion.scaleY.value + press.scale.value - 1f
                translationX = motion.translationX.value
                translationY = motion.translationY.value
            }
            .couiTouchMotion(motion, enabled)
            .couiPressMotion(press, enabled, cancelOnConsumedMove = true)
            .then(lightInput)
            .clip(CouiCapsuleShape)
            .drawWithContent {
                drawContent()
                drawLight?.invoke(this)
            }
            .drawWithContent {
                drawRect(fillColor)
                if (Build.VERSION.SDK_INT >= 33 && contour != null) {
                    val alpha = if (isPrimaryLike) {
                        if (dark) { if (enabled) 0.25f else 0.05f } else 0.3f
                    } else if (dark) {
                        if (enabled) 0.1f else 0.03f
                    } else {
                        if (enabled) 0.8f else 0.24f
                    }
                    val inner = if (isPrimaryLike) {
                        if (enabled) 0.1f else if (dark) 0.06f else 0.04f
                    } else if (dark) {
                        0.01f
                    } else {
                        0.05f
                    }
                    paint.shader = contour.shader(
                        size = size,
                        density = density,
                        radiusPx = size.minDimension / 2f,
                        cornerWeight = if (size.width == size.height) 2f else 1f,
                        surfaceColor = Color.Transparent,
                        innerGlowAlpha = inner,
                        lineAlpha = alpha,
                        effectAlpha = 1f,
                        lineWidthPx = (if (isPrimaryLike) 2.5f else 3f) * density,
                    )
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
                }
                drawContent()
            }
            .couiButtonFeedback(dark, source, enabled)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            kind = CouiTextKind.ItemTitle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val FieldFocusEasing = CubicBezierEasing(0f, 0f, 0.1f, 1f)

/**
 * COUI 输入框：上方可选标签行（支持右侧辅助文案），主体采用平滑圆角浅底容器与聚焦描边，
 * 在卡片与弹窗内均保持干净的留白与呼吸感，不产生贯穿整宽的突兀底线。
 */
@Composable
internal fun CouiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    rightLabel: String? = null,
    rightLabelColor: Color = Color.Unspecified,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    showClearButton: Boolean = singleLine,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    var focused by remember { mutableStateOf(false) }
    val focusAlpha = remember { Animatable(0f) }
    val hintColor = if (dark) Color(0x59FFFFFF) else Color(0x4D000000)
    val containerColor = if (dark) Color(0x1AFFFFFF) else Color(0x0D000000)
    val shape = CouiShape(14.dp, 2f)
    val textStyle = rememberCouiTextStyle(CouiTextKind.Search).copy(
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
    val effectivePlaceholder = placeholder ?: label
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
                    Text(
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
                    Text(
                        text = rightLabel,
                        kind = CouiTextKind.GroupFooter,
                        color = if (rightLabelColor != Color.Unspecified) rightLabelColor else colors.summary,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(containerColor, shape)
                .then(
                    if (focusAlpha.value > 0.01f && enabled) {
                        Modifier.border(
                            width = 1.dp,
                            color = colors.accent.copy(alpha = 0.7f * focusAlpha.value),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = if (singleLine) 11.dp else 12.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = (22 * minLines.coerceAtLeast(1)).dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && !effectivePlaceholder.isNullOrEmpty()) {
                    Text(
                        text = effectivePlaceholder,
                        kind = CouiTextKind.Search,
                        color = hintColor,
                        textAlign = TextAlign.Start,
                        maxLines = if (singleLine) 1 else maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (showClearButton && focused && enabled && value.isNotEmpty() && trailingIcon == null) {
                val clearLabel = stringResource(R.string.page_clear_84fcd7)
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    CouiClearGlyph(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = clearLabel }
                            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                                onValueChange("")
                            },
                        color = colors.summary,
                    )
                }
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

/**
 * 页面内常驻 COUI SearchBar 封装，自动挂载不自采样的局部背景源并处理展开态返回键。
 */
@Composable
internal fun CouiSearchBarBox(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = CouiPageDefaults.themeColors().background,
    onSearch: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val searchState = rememberCouiSearchBarState(expanded)
    val keyboard = LocalSoftwareKeyboardController.current
    val backdrop = rememberCouiBackdrop()
    val dark = CouiTheme.isDark
    BackHandler(enabled = expanded) {
        expanded = false
        onQueryChange("")
        keyboard?.hide()
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .couiBackdrop(backdrop)
                .background(backgroundColor),
        )
        SearchBar(
            state = searchState,
            query = query,
            onQueryChange = onQueryChange,
            onExpandedChange = { next ->
                expanded = next
                if (!next) onQueryChange("")
            },
            placeholder = placeholder,
            cancelText = stringResource(R.string.action_cancel),
            clearDescription = stringResource(R.string.page_clear_84fcd7),
            dark = dark,
            backdrop = backdrop,
            enabled = enabled,
            onSearch = {
                keyboard?.hide()
                onSearch()
            },
        )
    }
}

/**
 * COUI ChipTabRow 封装：API 34+ 且渲染器可用时使用 COUI 原生 ChipTabRow，否则使用 COUI 胶囊标签回退。
 */
@Composable
internal fun <T : Any> CouiChipTabs(
    items: List<Pair<T, String>>,
    selectedKey: T,
    onSelectedKeyChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = CouiPageDefaults.themeColors().background,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val renderers = remember(items.map { it.first }) {
        if (Build.VERSION.SDK_INT >= 34 && items.isNotEmpty()) {
            runCatching { items.map { CouiFloatingRenderer.standalone() } }.getOrNull()
        } else {
            null
        }
    }
    if (Build.VERSION.SDK_INT >= 34 && renderers != null) {
        val backdrop = rememberCouiBackdrop()
        val chipItems = remember(items) {
            items.map { (key, label) -> CouiChipTabItem(key = key, label = label) }
        }
        Box(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Box(
                Modifier
                    .matchParentSize()
                    .couiBackdrop(backdrop)
                    .background(backgroundColor),
            )
            ChipTabRow(
                modifier = Modifier.fillMaxWidth(),
                items = chipItems,
                selectedKey = selectedKey,
                onSelectedKeyChange = { key ->
                    @Suppress("UNCHECKED_CAST")
                    onSelectedKeyChange(key as T)
                },
                backdrop = backdrop,
                dark = dark,
                enabled = enabled,
                renderers = renderers,
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { (key, label) ->
                val selected = key == selectedKey
                val source = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(CouiCapsuleShape)
                        .background(
                            if (selected) colors.accent else colors.card,
                            CouiCapsuleShape,
                        )
                        .couiButtonFeedback(dark, source, enabled)
                        .selectable(
                            selected = selected,
                            interactionSource = source,
                            indication = null,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelectedKeyChange(key) },
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        kind = CouiTextKind.ItemValue,
                        color = if (selected) Color.White else colors.title,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberCouiDialogMaterial(dark: Boolean = CouiTheme.isDark): CouiDialogMaterial? {
    val backdrop = rememberCouiWindowBackdrop()
    return if (CouiDialogDefaults.isMaterialSupported() && backdrop != null) {
        CouiDialogDefaults.material(dark)
    } else {
        null
    }
}

@Composable
internal fun couiPrimaryDialogAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    dark: Boolean = CouiTheme.isDark,
): CouiDialogAction {
    val accent = if (destructive) {
        CouiDialogDefaults.themeColors(dark).destructive
    } else {
        CouiTheme.colorSchemeFor(dark).accent
    }
    return CouiDialogAction(
        text = text,
        onClick = onClick,
        enabled = enabled,
        style = CouiDialogActionStyle.Primary,
        colors = CouiDialogDefaults.primaryActionColors(accent),
    )
}

@Composable
internal fun CouiConfirmDialog(
    visible: Boolean,
    title: String?,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    cancelText: String = stringResource(R.string.action_cancel),
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    destructive: Boolean = false,
    placement: CouiDialogPlacement = CouiDialogPlacement.Bottom,
    neutralAction: CouiDialogAction? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val backdrop = rememberCouiWindowBackdrop()
    val material = rememberCouiDialogMaterial(dark)
    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        dark = dark,
        title = title,
        message = message,
        confirmAction = couiPrimaryDialogAction(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            destructive = destructive,
            dark = dark,
        ),
        dismissAction = CouiDialogAction(
            text = cancelText,
            onClick = onDismissRequest,
            enabled = cancelEnabled,
        ),
        neutralAction = neutralAction,
        placement = placement,
        material = material,
        backdrop = backdrop,
    ) {
        if (content != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiDialogDefaults.contentPadding)
                    .padding(top = 4.dp, bottom = 12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun CouiNoticeDialog(
    visible: Boolean,
    title: String?,
    message: String?,
    confirmText: String = stringResource(R.string.action_close),
    onDismissRequest: () -> Unit,
    placement: CouiDialogPlacement = CouiDialogPlacement.Bottom,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val backdrop = rememberCouiWindowBackdrop()
    val material = rememberCouiDialogMaterial(dark)
    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        dark = dark,
        title = title,
        message = message,
        confirmAction = couiPrimaryDialogAction(
            text = confirmText,
            onClick = onDismissRequest,
            dark = dark,
        ),
        placement = placement,
        material = material,
        backdrop = backdrop,
    ) {
        if (content != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiDialogDefaults.contentPadding)
                    .padding(top = 4.dp, bottom = 12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun CouiInputDialog(
    visible: Boolean,
    title: String?,
    message: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    counter: String? = null,
    counterIsError: Boolean = false,
    confirmText: String,
    confirmEnabled: Boolean = true,
    cancelText: String = stringResource(R.string.action_cancel),
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    autoFocus: Boolean = true,
) {
    val dark = CouiTheme.isDark
    val backdrop = rememberCouiWindowBackdrop()
    val material = rememberCouiDialogMaterial(dark)
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    if (fieldValue.text != value) {
        fieldValue = fieldValue.copy(
            text = value,
            selection = TextRange(value.length.coerceAtLeast(0)),
        )
    }
    InputDialog(
        visible = visible,
        title = title,
        message = message,
        value = fieldValue,
        onValueChange = { next ->
            fieldValue = next
            if (next.text != value) {
                onValueChange(next.text)
            }
        },
        placeholder = placeholder,
        counter = counter,
        counterIsError = counterIsError,
        clearContentDescription = stringResource(R.string.page_clear_84fcd7),
        confirmAction = couiPrimaryDialogAction(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            dark = dark,
        ),
        dismissAction = CouiDialogAction(
            text = cancelText,
            onClick = onDismissRequest,
        ),
        onDismissRequest = onDismissRequest,
        dark = dark,
        material = material,
        backdrop = backdrop,
        autoFocus = autoFocus,
    )
}

