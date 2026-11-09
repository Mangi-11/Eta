package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.checkbox.Checkbox
import com.itos.coui.checkbox.CouiCheckboxDefaults
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.input.CouiHapticFeedback
import com.itos.coui.input.performCouiHapticFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Item
import com.itos.coui.menu.CouiMenuDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.CouiMenuStyle
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.radiobutton.RadioButton
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.slider.CouiSliderDefaults
import com.itos.coui.slider.Slider
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme

val CouiIconDividerIndent: Dp =
    CouiPageDefaults.rowPadding + CouiPageDefaults.iconSize + CouiPageDefaults.iconSpacing

@Composable
internal fun CouiIconDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier,
        startIndent = CouiIconDividerIndent,
        endIndent = CouiPageDefaults.rowPadding,
    )
}

@Composable
internal fun CouiIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = CouiTheme.colorScheme.onSurface,
) {
    CouiIcon(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
internal fun CouiIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = CouiTheme.colorScheme.onSurface,
) {
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.then(semantics),
        colorFilter = if (tint.isSpecified) ColorFilter.tint(tint) else null,
    )
}

@Composable
internal fun CouiSettingsIcon(
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
) {
    val colors = CouiPageDefaults.themeColors()
    val glyphSize = when (icon) {
        Icons.AutoMirrored.Rounded.MenuBook -> 22.dp
        else -> 24.dp
    }
    Box(
        modifier = Modifier.size(CouiPageDefaults.iconSize),
        contentAlignment = Alignment.Center,
    ) {
        CouiIcon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) tint else colors.disabled,
        )
    }
}

@Composable
internal fun CouiPreference(
    modifier: Modifier = Modifier,
    title: String,
    summary: String? = null,
    value: String? = null,
    titleColor: Color = Color.Unspecified,
    summaryColor: Color = Color.Unspecified,
    valueColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    CouiPreferenceRow(
        modifier = modifier,
        title = title,
        summary = summary,
        titleColor = titleColor,
        summaryColor = summaryColor,
        enabled = enabled,
        icon = icon,
        onClick = onClick,
    ) {
        trailing?.invoke()
        if (!value.isNullOrEmpty()) {
            val resolvedValueColor = if (valueColor.isSpecified) {
                valueColor
            } else if (enabled) {
                colors.summary
            } else {
                colors.disabled
            }
            Text(
                modifier = Modifier
                    .widthIn(max = 162.dp)
                    .padding(start = 16.dp),
                text = value,
                kind = CouiTextKind.PreferenceValue,
                color = resolvedValueColor,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) {
            CouiSettingsArrow(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(width = 12.dp, height = 24.dp),
                back = false,
                color = Color(if (dark) 0x4DFFFFFF else 0x42000000),
            )
        }
    }
}

@Composable
internal fun CouiSwitchPreference(
    modifier: Modifier = Modifier,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
    loadingDescription: String? = null,
    hapticFeedbackEnabled: Boolean = true,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    var optimisticChecked by remember(checked) { mutableStateOf(checked) }
    val commitChange: (Boolean) -> Unit = { target ->
        optimisticChecked = target
        onCheckedChange(target)
    }
    CouiPreferenceRow(
        modifier = modifier
            .couiButtonFeedback(dark, source, enabled && !loading, list = true)
            .toggleable(optimisticChecked, source, null, enabled && !loading, Role.Switch) { target ->
                view.performCouiHapticFeedback(CouiHapticFeedback.Switch, hapticFeedbackEnabled)
                commitChange(target)
            },
        title = title,
        summary = summary,
        enabled = enabled,
        icon = icon,
    ) {
        Switch(
            modifier = Modifier.padding(start = 16.dp),
            checked = optimisticChecked,
            onCheckedChange = null,
            dark = dark,
            colors = CouiSwitchDefaults.colors(colors.accent, dark),
            enabled = enabled,
            interactionSource = source,
            onDragCheckedChange = commitChange,
            loading = loading,
            loadingDescription = loadingDescription,
            hapticFeedbackEnabled = hapticFeedbackEnabled,
        )
    }
}

@Composable
internal fun CouiCheckboxPreference(
    modifier: Modifier = Modifier,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = CouiPageDefaults.rowPadding,
        vertical = CouiPageDefaults.contentPadding,
    ),
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val source = remember { MutableInteractionSource() }
    CouiPreferenceRow(
        modifier = modifier
            .couiButtonFeedback(dark, source, enabled, list = true)
            .toggleable(checked, source, null, enabled, Role.Checkbox, onCheckedChange),
        title = title,
        summary = summary,
        enabled = enabled,
        icon = icon,
        contentPadding = contentPadding,
    ) {
        Checkbox(
            modifier = Modifier
                .padding(start = 16.dp)
                .size(24.dp),
            checked = checked,
            enabled = enabled,
            colors = CouiCheckboxDefaults.colors(colors.accent, dark),
            shape = CouiCheckboxDefaults.shape,
            borderShape = CouiCheckboxDefaults.borderShape,
        )
    }
}

@Composable
internal fun CouiSelectPreference(
    modifier: Modifier = Modifier,
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val source = remember { MutableInteractionSource() }
    CouiPreferenceRow(
        modifier = modifier
            .couiButtonFeedback(dark, source, enabled, list = true)
            .selectable(selected, source, null, enabled, Role.RadioButton, onSelect),
        title = title,
        summary = summary,
        enabled = enabled,
        icon = icon,
    ) {
        RadioButton(
            modifier = Modifier
                .padding(start = 16.dp)
                .size(24.dp),
            selected = selected,
            enabled = enabled,
        )
    }
}

@Composable
internal fun <T> CouiPopupPreference(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<CouiMenuItem<T>>,
    onItemClick: (CouiMenuItem<T>) -> Unit,
    menuStyle: CouiMenuStyle = CouiMenuDefaults.style(),
    summary: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val source = remember { MutableInteractionSource() }
    val anchor = rememberCouiMenuAnchor()
    Box(modifier) {
        CouiPreferenceRow(
            modifier = Modifier
                .couiMenuAnchor(anchor)
                .couiButtonFeedback(dark, source, enabled, list = true, hoverLocked = expanded)
                .clickable(source, null, enabled, role = Role.Button) {
                    anchor.open { onExpandedChange(true) }
                },
            title = title,
            summary = summary,
            enabled = enabled,
            icon = icon,
        ) {
            BasicText(
                text = value,
                modifier = Modifier
                    .widthIn(max = 162.dp)
                    .padding(start = 16.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = rememberCouiTextStyle(CouiTextKind.PreferenceValue).copy(
                    color = if (enabled) colors.summary else colors.disabled,
                    textAlign = TextAlign.End,
                ),
            )
            CouiIcon(
                imageVector = CouiIcons.Popup,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(14.dp, 24.dp),
                tint = Color(if (dark) 0x4DFFFFFF else 0x42000000),
            )
        }
        Menu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            items = items,
            onItemClick = { item ->
                onItemClick(item)
                onExpandedChange(false)
            },
            style = menuStyle,
            anchor = anchor,
        )
    }
}

@Composable
internal fun CouiDropdownPreference(
    modifier: Modifier = Modifier,
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val currentLabel = items.getOrNull(safeIndex).orEmpty()
    val menuItems = remember(items, safeIndex) {
        items.mapIndexed { index, label ->
            CouiMenuItem(
                value = index,
                text = label,
                selected = index == safeIndex,
            )
        }
    }
    CouiPopupPreference(
        modifier = modifier,
        title = title,
        value = currentLabel,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        items = menuItems,
        onItemClick = { onSelectedIndexChange(it.value) },
        summary = summary,
        enabled = enabled,
        icon = icon,
    )
}

@Composable
internal fun CouiSliderPreference(
    modifier: Modifier = Modifier,
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueText: String? = null,
    summary: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    showTickMarks: Boolean = steps > 0,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onValueChangeFinished: () -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    Column(modifier.fillMaxWidth()) {
        CouiPreference(
            title = title,
            summary = summary,
            value = valueText,
            enabled = enabled,
            onClick = onClick,
            showChevron = false,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Slider(
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = title },
                value = value,
                onValueChange = onValueChange,
                dark = dark,
                enabled = enabled,
                colors = CouiSliderDefaults.colors(colors.accent, dark, enabled),
                shape = CouiCapsuleShape,
                valueRange = valueRange,
                steps = steps,
                showTickMarks = showTickMarks,
                onValueChangeFinished = onValueChangeFinished,
            )
            trailing?.invoke()
        }
    }
}

@Composable
internal fun CouiPreferenceRow(
    modifier: Modifier,
    title: String,
    summary: String?,
    enabled: Boolean,
    icon: (@Composable () -> Unit)?,
    titleColor: Color = Color.Unspecified,
    summaryColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = CouiPageDefaults.rowPadding,
        vertical = CouiPageDefaults.contentPadding,
    ),
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val resolvedTitleColor = if (titleColor.isSpecified) {
        if (enabled) titleColor else colors.disabled
    } else if (enabled) {
        colors.title
    } else {
        colors.disabled
    }
    val resolvedSummaryColor = if (summaryColor.isSpecified) {
        if (enabled) summaryColor else colors.disabled
    } else if (enabled) {
        colors.summary
    } else {
        colors.disabled
    }
    Item(
        modifier = modifier,
        icon = icon,
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        title = {
            Text(
                text = title,
                kind = CouiTextKind.PreferenceTitle,
                color = resolvedTitleColor,
                textAlign = TextAlign.Start,
            )
        },
        summary = summary?.takeIf { it.isNotEmpty() }?.let { text ->
            @Composable {
                Text(
                    text = text,
                    kind = CouiTextKind.PreferenceSummary,
                    color = resolvedSummaryColor,
                    textAlign = TextAlign.Start,
                )
            }
        },
        trailing = trailing,
    )
}

@Composable
internal fun CouiSettingsArrow(
    modifier: Modifier,
    back: Boolean,
    color: Color,
) {
    CouiIcon(
        imageVector = if (back) CouiIcons.Back else CouiIcons.Next,
        contentDescription = null,
        modifier = modifier,
        tint = color,
    )
}

/**
 * 对齐 COUI Group 标题行与留白规范的独立分组标题（用于 Lazy 列表或带尾部动作的分组标题）。
 */
@Composable
internal fun CouiSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = CouiPageDefaults.themeColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = CouiPageDefaults.categoryTopSpacing)
            .padding(horizontal = 32.dp, vertical = 8.dp)
            .heightIn(min = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            kind = CouiTextKind.GroupTitle,
            color = colors.summary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            textAlign = TextAlign.Start,
        )
        trailing()
    }
}

/**
 * 长列表保持逐项 Lazy 组合，首尾应用 COUI Group 的 24.dp 平滑圆角，中间项自动插入组内 Divider。
 */
@Composable
internal fun CouiGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    hasLeadingIcon: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val shape = when {
        isFirst && isLast -> CouiPageDefaults.shape
        isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        isLast -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CouiPageDefaults.horizontalMargin)
            .padding(bottom = if (isLast) CouiPageDefaults.groupSpacing else 0.dp)
            .clip(shape)
            .background(colors.card),
    ) {
        content()
        if (!isLast) {
            if (hasLeadingIcon) {
                CouiIconDivider()
            } else {
                Divider()
            }
        }
    }
}
