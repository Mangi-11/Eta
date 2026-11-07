package io.github.mangi.eta.ui.screens.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
internal fun BrowserAddressBar(
    snapshot: BrowserSessionSnapshot,
    address: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    actionPending: Boolean,
    onExit: () -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onOpenExternal: () -> Unit,
    onClosePage: () -> Unit,
    onClearData: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val addressLabel = stringResource(R.string.browser_search_or_address)
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = !focused,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row {
                BrowserControlButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    description = stringResource(if (snapshot.canGoBack) R.string.browser_back else R.string.action_back),
                    enabled = !snapshot.canGoBack || !actionPending,
                    onClick = if (snapshot.canGoBack) onBack else onExit,
                )
                BrowserControlButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    description = stringResource(R.string.browser_forward),
                    enabled = snapshot.canGoForward && !actionPending,
                    onClick = onForward,
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .height(40.dp)
                .squircleSurface(color = colors.surfaceContainerHigh, cornerRadius = 20.dp)
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (snapshot.url.startsWith("https://")) Icons.Rounded.Lock else Icons.Rounded.Language,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (address.text.isEmpty()) {
                    Text(
                        text = addressLabel,
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    singleLine = true,
                    textStyle = MiuixTheme.textStyles.body2.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = addressLabel }
                        .onFocusChanged { onFocusChange(it.isFocused) },
                )
            }
            BrowserSubmitAction(
                focused = focused,
                canSubmit = address.text.isNotBlank() && !actionPending,
                onGo = onGo,
            )
        }
        AnimatedVisibility(
            visible = !focused,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            BrowserRefreshButton(
                loading = snapshot.isLoading,
                enabled = !focused && snapshot.available && (snapshot.isLoading || !actionPending),
                onClick = onRefreshOrStop,
            )
        }
        BrowserOverflowMenu(
            items = listOf(
                DropdownItem(
                    text = stringResource(R.string.browser_open_external),
                    enabled = snapshot.url.startsWith("http://") || snapshot.url.startsWith("https://"),
                    onClick = onOpenExternal,
                ),
                DropdownItem(
                    text = stringResource(R.string.browser_close_page),
                    enabled = snapshot.available && !actionPending,
                    onClick = onClosePage,
                ),
                DropdownItem(
                    text = stringResource(R.string.browser_clear_shared_data),
                    enabled = !actionPending,
                    onClick = onClearData,
                ),
            ),
        )
    }
}

@Composable
private fun BrowserRefreshButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(if (loading) R.string.browser_stop_loading else R.string.browser_refresh)
    val tint by animateColorAsState(
        targetValue = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        animationSpec = tween(180),
        label = "browser_refresh_tint",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        // 只交叉淡化图标，点击区域和地址框宽度不参与加载状态切换。
        Crossfade(
            targetState = loading,
            animationSpec = tween(180),
            modifier = Modifier.size(20.dp),
            label = "browser_refresh_icon",
        ) { isLoading ->
            Icon(
                imageVector = if (isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    }
}

@Composable
private fun BrowserSubmitAction(
    focused: Boolean,
    canSubmit: Boolean,
    onGo: () -> Unit,
) {
    // 操作槽保持固定宽度，输入动画只作用于图标，不改变网页的布局尺寸。
    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = focused,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
        ) {
            BrowserControlButton(
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                description = stringResource(R.string.browser_search_or_address),
                enabled = focused && canSubmit,
                onClick = onGo,
                size = 36.dp,
            )
        }
    }
}

@Composable
private fun BrowserOverflowMenu(items: List<DropdownItem>) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberCouiMenuAnchor()
    Box {
        BrowserControlButton(
            icon = Icons.Rounded.MoreVert,
            description = stringResource(R.string.action_more),
            onClick = { anchor.open { expanded = true } },
            modifier = Modifier.couiMenuAnchor(anchor),
        )
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                val menuItems = remember(items) {
                    items.mapIndexed { index, item ->
                        CouiMenuItem(
                            value = index,
                            text = item.text,
                            enabled = item.enabled,
                        )
                    }
                }
                Menu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    items = menuItems,
                    onItemClick = { menuItem ->
                        expanded = false
                        items.getOrNull(menuItem.value)?.onClick?.invoke()
                    },
                    anchor = anchor,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowListPopup(
                    show = expanded,
                    alignment = PopupPositionProvider.Align.End,
                    onDismissRequest = { expanded = false },
                ) {
                    ListPopupColumn {
                        items.forEachIndexed { index, item ->
                            DropdownImpl(
                                item = item,
                                optionSize = items.size,
                                isSelected = false,
                                index = index,
                                onSelectedIndexChange = {
                                    expanded = false
                                    item.onClick?.invoke()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, minWidth = size, minHeight = size, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        )
    }
}
