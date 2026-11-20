package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.LocalCardListPosition
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.CheckboxPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.preference.SwitchLoadingPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.squircle.squircleSurface
import io.github.suqi8.coui.kmp.theme.COUITheme

val CouiIconDividerIndent: Dp =
    CouiPageDefaults.rowPadding + CouiPageDefaults.iconSize + CouiPageDefaults.iconSpacing

@Composable
internal fun CouiCardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(horizontal = CouiPageDefaults.rowPadding))
}

@Composable
internal fun CouiIconDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(start = CouiIconDividerIndent, end = CouiPageDefaults.rowPadding))
}

@Composable
internal fun CouiIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = COUITheme.colorScheme.labelPrimary,
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
    tint: Color = COUITheme.colorScheme.labelPrimary,
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
    Box(
        modifier = Modifier.size(CouiPageDefaults.iconSize),
        contentAlignment = Alignment.Center,
    ) {
        CouiIcon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
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
    val titleColors = if (titleColor.isSpecified) BasicComponentDefaults.titleColor(color = titleColor)
        else BasicComponentDefaults.titleColor()
    val summaryColors = if (summaryColor.isSpecified) BasicComponentDefaults.summaryColor(color = summaryColor)
        else BasicComponentDefaults.summaryColor()
    val actions: @Composable RowScope.() -> Unit = {
        trailing?.invoke()
        if (!value.isNullOrEmpty()) {
            val colors = COUITheme.colorScheme
            CouiText(
                text = value,
                modifier = Modifier.widthIn(max = 162.dp),
                kind = CouiTextKind.ItemValue,
                color = when {
                    !enabled -> colors.disabledNeutral
                    valueColor.isSpecified -> valueColor
                    else -> colors.labelTertiary
                },
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showChevron) {
        ArrowPreference(
            title = title, modifier = modifier, titleColor = titleColors, summary = summary,
            summaryColor = summaryColors, startAction = icon, endActions = actions, onClick = onClick, enabled = enabled,
        )
    } else {
        BasicComponent(
            title = title, modifier = modifier, titleColor = titleColors, summary = summary,
            summaryColor = summaryColors, startAction = icon, endActions = actions, onClick = onClick, enabled = enabled,
        )
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
    // 直接透传业务态 checked，避免异步校验拒绝切换时乐观本地态卡在错误位置。
    when {
        loading -> SwitchLoadingPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            summary = summary,
            modifier = if (loadingDescription == null) modifier else modifier.semantics {
                stateDescription = loadingDescription
            },
            startAction = icon,
            enabled = enabled,
            isLoading = true,
        )
        hapticFeedbackEnabled -> SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            modifier = modifier,
            summary = summary,
            startAction = icon,
            enabled = enabled,
        )
        else -> BasicComponent(
            title = title,
            modifier = modifier.semantics { toggleableState = ToggleableState(checked) },
            summary = summary,
            startAction = icon,
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) },
            endActions = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
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
    contentPadding: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
    CheckboxPreference(
        title = title, checked = checked, onCheckedChange = onCheckedChange, modifier = modifier,
        summary = summary, enabled = enabled, startAction = icon, insideMargin = contentPadding,
    )
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
    summary: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val anchor = rememberCouiMenuAnchor()
    val scheme = COUITheme.colorScheme
    Box(modifier) {
        ArrowPreference(
            title = title,
            modifier = Modifier.couiMenuAnchor(anchor),
            summary = summary,
            startAction = icon,
            endActions = {
                CouiText(
                    text = value,
                    modifier = Modifier.widthIn(max = 162.dp),
                    kind = CouiTextKind.ItemValue,
                    color = if (enabled) scheme.labelTertiary else scheme.disabledNeutral,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            holdDownState = expanded,
            enabled = enabled,
            onClick = { anchor.open { onExpandedChange(true) } },
        )
        CouiMenu(
            expanded = expanded, onDismissRequest = { onExpandedChange(false) }, items = items, anchor = anchor,
            onItemClick = { item ->
                onItemClick(item)
                onExpandedChange(false)
            },
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
    OverlayDropdownPreference(
        items = items, selectedIndex = selectedIndex, title = title, modifier = modifier, summary = summary,
        enabled = enabled && items.isNotEmpty(), startAction = icon, onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
internal fun CouiItem(
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    title: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    BasicComponent(
        modifier = modifier, startAction = icon, onClick = onClick, enabled = enabled,
        endActions = trailing?.let { { it() } },
    ) {
        title()
        summary?.invoke()
    }
}

@Composable
internal fun CouiSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    PreferenceCategory(text, modifier.semantics { heading() }, endWidget = trailing)
}

@Composable
internal fun CouiPreferenceFooter(text: String, modifier: Modifier = Modifier, color: Color = COUITheme.colorScheme.labelSecondary) {
    CouiText(
        text = text, modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        color = color, kind = CouiTextKind.GroupFooter,
    )
}

@Composable
internal fun CouiPreferenceGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
    content: CardListGroupScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) CouiSectionTitle(title, trailing = headerActions)
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CouiPageDefaults.horizontalMargin)
                .padding(bottom = if (footer.isNullOrEmpty()) CouiPageDefaults.groupSpacing else 0.dp),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = colors.card),
        ) {
            CardListGroup(content = content)
        }
        if (!footer.isNullOrEmpty()) CouiPreferenceFooter(footer)
    }
}

/** 逐项组合长列表，首尾条目沿用设置卡片的位置语义。 */
@Composable
internal fun CouiGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    hasLeadingIcon: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val position = when {
        isFirst && isLast -> CardListPosition.Full
        isFirst -> CardListPosition.Head
        isLast -> CardListPosition.Tail
        else -> CardListPosition.Middle
    }
    val topRadius = if (isFirst) 24.dp else 0.dp
    val bottomRadius = if (isLast) 24.dp else 0.dp
    val surfaceModifier = if (isFirst || isLast) {
        Modifier.squircleSurface(
            color = colors.card,
            topStart = topRadius,
            topEnd = topRadius,
            bottomEnd = bottomRadius,
            bottomStart = bottomRadius,
        )
    } else {
        Modifier.background(colors.card)
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = CouiPageDefaults.horizontalMargin)
            .padding(bottom = if (isLast) CouiPageDefaults.groupSpacing else 0.dp)
            .then(surfaceModifier),
    ) {
        CompositionLocalProvider(LocalCardListPosition provides position, content = content)
        if (!isLast) {
            if (hasLeadingIcon) CouiIconDivider()
            else HorizontalDivider(Modifier.padding(horizontal = CouiPageDefaults.rowPadding))
        }
    }
}
