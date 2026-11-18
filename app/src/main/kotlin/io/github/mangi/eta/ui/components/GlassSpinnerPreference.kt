package io.github.mangi.eta.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalEtaTopLayer
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassPopupVisuals
import top.yukonga.miuix.kmp.glass.glassPopupAnchorRow
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/**
 * 支持双行摘要（[DropdownItem.summary]）与单行禁用态的 OS4 玻璃下拉设置行。
 * 存在顶层承载器时直接挂载在 [EtaTopLayer] 上以保持完整退场动效；独立测试环境回退至 [PopupLayout]。
 */
@Composable
internal fun EtaGlassSpinnerPreference(
    title: String,
    items: List<DropdownItem>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = currentGlassOverlayBackdrop(),
    enabled: Boolean = true,
    showValue: Boolean = true,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    sizing: GlassPopupSizing = GlassPopupSizing(),
    visuals: GlassPopupVisuals = GlassPopupDefaults.visuals(),
) {
    var expanded by remember { mutableStateOf(false) }
    val mounted = remember { mutableStateOf(false) }
    val anchor = rememberGlassPopupAnchor()
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val actualEnabled = enabled && items.isNotEmpty()
    val hapticFeedback = LocalHapticFeedback.current
    val currentOnSelected by rememberUpdatedState(onSelectedIndexChange)

    LaunchedEffect(actualEnabled) {
        if (!actualEnabled) expanded = false
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted.value = true
        } else if (mounted.value) {
            delay(SpinnerPopupExitDelayMs)
            mounted.value = false
        }
    }

    val actionColor = if (actualEnabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }

    BasicComponent(
        modifier = modifier
            .glassPopupAnchorRow(anchor)
            .onGloballyPositioned { rowBounds = it.boundsInWindow() },
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            Row(
                modifier = Modifier.glassPopupAnchorValue(anchor),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val value = items.getOrNull(selectedIndex)?.text
                if (showValue && !value.isNullOrEmpty()) {
                    Text(
                        text = value,
                        modifier = Modifier.padding(end = 8.dp).weight(1f, fill = false),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = actionColor,
                        textAlign = TextAlign.End,
                    )
                }
                DropdownArrowEndAction(actionColor = actionColor)
            }
        },
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        onClick = {
            expanded = !expanded
            if (expanded) hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
        },
        role = Role.DropdownList,
        holdDownState = expanded,
        enabled = actualEnabled,
    )

    val popupBody: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        GlassDropdownPopup(
            show = expanded && actualEnabled,
            onDismissRequest = { expanded = false },
            anchorBounds = rowBounds,
            backdrop = backdrop,
            anchor = anchor,
            sizing = sizing,
            visuals = visuals,
        ) {
            items.forEachIndexed { index, item ->
                GlassPopupItem(
                    text = item.text,
                    summary = item.summary,
                    enabled = item.enabled,
                    selected = selectedIndex == index,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        expanded = false
                        currentOnSelected(index)
                    },
                )
            }
        }
    }

    if (LocalEtaTopLayer.current != null) {
        EtaTopLayer(content = popupBody)
    } else if (mounted.value) {
        PopupLayout(
            visible = mounted,
            enableWindowDim = false,
            enableBackHandler = false,
            enterTransition = EnterTransition.None,
            exitTransition = ExitTransition.None,
            renderInRootScaffold = true,
        ) {
            Box(modifier = Modifier.fillMaxSize(), content = popupBody)
        }
    }
}

private const val SpinnerPopupExitDelayMs = 420L
