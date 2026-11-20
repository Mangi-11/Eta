package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MapsUgc
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiFixedTopBar
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiText
import io.github.mangi.eta.ui.components.coui.CouiTextKind
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.coui.ProvideCouiMaterial
import io.github.mangi.eta.ui.layout.rememberFloatingBottomBarInsets
import io.github.suqi8.coui.kmp.anim.couiSpring
import io.github.suqi8.coui.kmp.basic.CancelButton
import io.github.suqi8.coui.kmp.basic.IconButton as CouiIconButton
import io.github.suqi8.coui.kmp.basic.InputField as CouiInputField
import io.github.suqi8.coui.kmp.basic.SearchBar as CouiSearchBar
import io.github.suqi8.coui.kmp.blur.LayerBackdrop as CouiBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField as MiuixInputField
import top.yukonga.miuix.kmp.basic.SearchBar as MiuixSearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.NewMessage
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.SecurityStatus
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.lerp

internal object ConversationPanelMetrics {
    val PaneHorizontalPadding = 16.dp
    val TopBarHeight = 56.dp
    val BottomDockHeight = 44.dp
    val BottomDockVerticalPadding = 10.dp
    val BottomDockGap = 8.dp
    val DockButtonSize = 44.dp
    val DockIconSize = 20.dp
    val BrandBadgeSize = 26.dp
    val BrandIconSize = 16.dp
    val NewChatIconSize = 19.dp

    val WorkbenchCornerRadius = 22.dp
    val WorkbenchRowMinHeight = 46.dp
    val WorkbenchIconSize = 19.dp
    val WorkbenchRowHorizontalPadding = 16.dp
    val WorkbenchIconGap = 12.dp

    val FirstSectionTopPadding = 22.dp
    val SectionTopPadding = 20.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 44.dp
    val RowGap = 3.dp
    val RowCornerRadius = 14.dp
    val RowHorizontalPadding = 14.dp
    val RowVerticalPadding = 10.dp
    val CharacterAvatarSize = 24.dp
    val CharacterAvatarGap = 10.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 8.dp
    val ActionIconSize = 20.dp
    val EmptyVerticalPadding = 36.dp
}

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 浅色下保持与各自系统设置页一致的温和浅底（COUI backgroundWithCard / Miuix surface），使卡片层次分明；
 * 深色下使用抬升表面色（COUI backgroundElevatedWithCard / Miuix surface-surfaceContainer 插值），
 * 确保侧栏与右侧聊天黑底之间边界清晰。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val scheme = CouiTheme.colorScheme
        return if (scheme.isDark) scheme.backgroundElevatedWithCard else scheme.backgroundWithCard
    }
    val colors = MiuixTheme.colorScheme
    return if (colors.surface.luminance() > 0.5f) {
        colors.surface
    } else {
        lerp(colors.surface, colors.surfaceContainer, 0.45f)
    }
}

@Composable
internal fun conversationPaneCardColor(): Color {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        return CouiTheme.colorScheme.cardBackground
    }
    return MiuixTheme.colorScheme.surfaceContainer
}

@Composable
internal fun conversationPaneEdgeBorderColor(): Color {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        return CouiTheme.colorScheme.divider
    }
    return MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.42f)
}

@Composable
internal fun ConversationPaneTopBar(
    containerColor: Color,
    couiBackdrop: CouiBackdrop?,
    miuixBackdrop: LayerBackdrop?,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding)
                .height(ConversationPanelMetrics.TopBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaneBrandHeader(modifier = Modifier.weight(1f))
            PaneNewChatButton(
                hasCouiToolbarBackdrop = LocalUiStyle.current == AppearanceUiStyle.COUI && couiBackdrop != null,
                miuixBackdrop = miuixBackdrop,
                onClick = onNewConversation,
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiFixedTopBar(couiBackdrop, containerColor, modifier, barContent)
        AppearanceUiStyle.MIUIX -> {
            val fallbackBandBrush = remember(containerColor) {
                Brush.verticalGradient(
                    0.0f to containerColor,
                    0.8f to containerColor.copy(alpha = 0.94f),
                    1.0f to Color.Transparent,
                )
            }
            MiuixTopBarBackdrop(
                progressiveBackdrop = miuixProgressiveTopBarBackdrop(miuixBackdrop),
                backgroundColor = containerColor,
                fallbackBrush = fallbackBandBrush,
                modifier = modifier.fillMaxWidth(),
                content = barContent,
            )
        }
    }
}

@Composable
private fun PaneBrandHeader(modifier: Modifier = Modifier) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val dark = if (isCoui) CouiTheme.isDark else MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accent = if (isCoui) CouiTheme.colorScheme.primary else MiuixTheme.colorScheme.primary
    val badgeBrush = remember(accent, dark) {
        Brush.linearGradient(
            colors = listOf(
                accent,
                lerp(accent, if (dark) Color.White else Color.Black, 0.12f),
            ),
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ConversationPanelMetrics.BrandBadgeSize)
                .clip(CircleShape)
                .background(badgeBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ConversationPaneIcons.BrandEta,
                contentDescription = null,
                modifier = Modifier.size(ConversationPanelMetrics.BrandIconSize),
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (isCoui) {
            CouiText(
                text = stringResource(R.string.app_name),
                kind = CouiTextKind.TopBar,
                color = CouiPageDefaults.themeColors().title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.app_name),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PaneNewChatButton(
    hasCouiToolbarBackdrop: Boolean,
    miuixBackdrop: Backdrop?,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.action_new_conversation)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            if (hasCouiToolbarBackdrop) {
                CouiTopAppBarIconButton(
                    icon = Icons.Rounded.MapsUgc,
                    contentDescription = description,
                    onClick = onClick,
                    colors = colors,
                    iconSize = ConversationPanelMetrics.NewChatIconSize,
                )
            } else {
                CouiIconButton(
                    onClick = onClick,
                    modifier = Modifier.semantics { contentDescription = description },
                    material = false,
                    backgroundColor = CouiTheme.colorScheme.surface,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                ) {
                    CouiIcon(
                        imageVector = Icons.Rounded.MapsUgc,
                        contentDescription = null,
                        modifier = Modifier.size(ConversationPanelMetrics.NewChatIconSize),
                        tint = colors.title,
                    )
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            val colors = MiuixTheme.colorScheme
            GlassIconButton(
                onClick = onClick,
                backdrop = miuixBackdrop,
                size = GlassTopAppBarDefaults.ButtonSize,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.NewMessage,
                    contentDescription = description,
                    modifier = Modifier.size(28.dp),
                    tint = colors.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun ConversationPaneBottomDock(
    query: String,
    isRevealed: Boolean,
    hasPermissionAttention: Boolean,
    containerColor: Color,
    couiBackdrop: CouiBackdrop?,
    miuixBackdrop: LayerBackdrop?,
    onSearchChange: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 仅在侧边栏实际展开且当前窗口持有焦点时避让 IME，防止弹窗或主聊天页键盘触发侧栏重排。
    val applyIme = isRevealed && LocalWindowInfo.current.isWindowFocused
    val safeDockInsets = rememberFloatingBottomBarInsets(
        baseInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        fixedBottomPadding = ConversationPanelMetrics.BottomDockVerticalPadding,
    )
    val dockInsets = if (applyIme) safeDockInsets.union(WindowInsets.ime) else safeDockInsets

    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val progressiveBlurEnabled = LocalAppearanceSettings.current.progressiveBlurEnabled
    val activeMiuixBackdrop = if (!isCoui && isRuntimeShaderSupported()) {
        miuixBackdrop
    } else {
        null
    }
    val progressiveMiuixBackdrop = if (progressiveBlurEnabled) activeMiuixBackdrop else null
    val activeCouiBackdrop = if (isCoui) couiBackdrop else null

    val bottomScrimBrush = remember(containerColor) {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.25f to containerColor.copy(alpha = 0.72f),
            0.55f to containerColor.copy(alpha = 0.94f),
            1.0f to containerColor,
        )
    }

    val backdropModifier = if (progressiveMiuixBackdrop != null) {
        Modifier
            .progressiveTextureBlur(
                backdrop = progressiveMiuixBackdrop,
                shape = RectangleShape,
                gradient = ProgressiveBlur.Bottom.copy(curve = 1.6f),
                blurRadius = 56f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(containerColor.copy(alpha = 0.36f)),
                    ),
                ),
            )
            .background(bottomScrimBrush)
    } else {
        Modifier.background(bottomScrimBrush)
    }

    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val setSearchExpanded: (Boolean) -> Unit = { expanded ->
        if (searchExpanded != expanded) {
            searchExpanded = expanded
            if (expanded) {
                keyboard?.show()
            } else {
                if (query.isNotEmpty()) onSearchChange("")
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
    }
    // 侧栏收起时同步退出搜索态，确保不会在后台残留焦点或抢占返回键。
    LaunchedEffect(isRevealed) {
        if (!isRevealed && searchExpanded) {
            searchExpanded = false
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val permissionsDesc = if (hasPermissionAttention) {
        stringResource(R.string.conversation_permission_attention)
    } else {
        stringResource(R.string.route_permissions)
    }
    val settingsDesc = stringResource(R.string.route_settings)
    val cancelText = stringResource(R.string.action_cancel)
    val placeholder = stringResource(R.string.conversation_search_hint)
    val clearDescription = stringResource(R.string.page_clear_84fcd7)

    val buttonsProgress by animateFloatAsState(
        targetValue = if (searchExpanded) 0f else 1f,
        animationSpec = if (isCoui) {
            if (searchExpanded) {
                couiSpring(response = 0.35f, bounce = 0f)
            } else {
                couiSpring(response = 0.4f, bounce = 0.3f)
            }
        } else {
            spring(dampingRatio = 0.88f, stiffness = 360f)
        },
        label = "PaneDockButtonsProgress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(backdropModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(dockInsets)
                .padding(
                    top = ConversationPanelMetrics.BottomDockVerticalPadding + 10.dp,
                    bottom = ConversationPanelMetrics.BottomDockVerticalPadding,
                ),
        ) {
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    ProvideCouiMaterial(activeCouiBackdrop) {
                        CoordinatedDockSearchLayout(
                            searchExpanded = searchExpanded,
                            buttonsProgress = { buttonsProgress },
                            horizontalMargin = ConversationPanelMetrics.PaneHorizontalPadding,
                            buttonGap = ConversationPanelMetrics.BottomDockGap,
                            searchBar = {
                                CouiSearchBar(
                                    modifier = Modifier.fillMaxWidth(),
                                    insideMargin = DpSize(0.dp, 0.dp),
                                    expanded = searchExpanded,
                                    onExpandedChange = setSearchExpanded,
                                    inputField = {
                                        CouiInputField(
                                            query = query,
                                            onQueryChange = onSearchChange,
                                            onSearch = {
                                                keyboard?.hide()
                                                focusManager.clearFocus()
                                            },
                                            expanded = searchExpanded,
                                            onExpandedChange = setSearchExpanded,
                                            label = placeholder,
                                        )
                                    },
                                    outsideEndAction = {
                                        CancelButton(
                                            onClick = { setSearchExpanded(false) },
                                            text = cancelText,
                                        )
                                    },
                                    content = {},
                                )
                            },
                            trailingButtons = {
                                PaneDockActionButtons(
                                    enabled = !searchExpanded,
                                    hasPermissionAttention = hasPermissionAttention,
                                    permissionsDesc = permissionsDesc,
                                    settingsDesc = settingsDesc,
                                    couiBackdrop = activeCouiBackdrop,
                                    miuixBackdrop = null,
                                    onOpenPermissions = onOpenPermissions,
                                    onOpenSettings = onOpenSettings,
                                )
                            },
                        )
                    }
                }
                AppearanceUiStyle.MIUIX -> {
                    val miuixColors = MiuixTheme.colorScheme
                    val miuixDark = miuixColors.surface.luminance() < 0.5f
                    val pillShape = remember { GlassShape(ConversationPanelMetrics.BottomDockHeight / 2) }
                    val fallbackBg = GlassTopAppBarDefaults.buttonFill()
                    CoordinatedDockSearchLayout(
                        searchExpanded = searchExpanded,
                        buttonsProgress = { buttonsProgress },
                        horizontalMargin = ConversationPanelMetrics.PaneHorizontalPadding,
                        buttonGap = ConversationPanelMetrics.BottomDockGap,
                        searchBar = {
                            MiuixSearchBar(
                                modifier = Modifier.fillMaxWidth(),
                                insideMargin = DpSize(0.dp, 0.dp),
                                expanded = searchExpanded,
                                onExpandedChange = setSearchExpanded,
                                inputField = {
                                    MiuixInputField(
                                        query = query,
                                        onQueryChange = onSearchChange,
                                        onSearch = {
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                        },
                                        expanded = searchExpanded,
                                        onExpandedChange = setSearchExpanded,
                                        label = placeholder,
                                        color = Color.Transparent,
                                        textStyle = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
                                        modifier = Modifier
                                            .height(ConversationPanelMetrics.BottomDockHeight)
                                            .glassPanel(
                                                backdrop = activeMiuixBackdrop,
                                                shape = pillShape,
                                                style = GlassDefaults.style(miuixDark),
                                                material = GlassTopAppBarDefaults.buttonMaterial(),
                                                stroke = GlassTopAppBarDefaults.buttonStroke(),
                                                shadow = GlassShadows.Regular,
                                                shading = false,
                                                fallback = Modifier
                                                    .clip(CircleShape)
                                                    .background(fallbackBg),
                                            ),
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MiuixIcons.Glass.Search,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .padding(start = 14.dp, end = 8.dp)
                                                    .size(20.dp),
                                                tint = if (searchExpanded) miuixColors.primary else miuixColors.onSurfaceVariantSummary,
                                            )
                                        },
                                        trailingIcon = {
                                            AnimatedVisibility(
                                                visible = query.isNotEmpty(),
                                                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                                                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(end = 8.dp)
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .semantics { contentDescription = clearDescription }
                                                        .clickable(role = Role.Button) { onSearchChange("") },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = MiuixIcons.Basic.SearchCleanup,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = miuixColors.onSurfaceVariantActions,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                },
                                outsideEndAction = {
                                    Box(modifier = Modifier.padding(start = ConversationPanelMetrics.BottomDockGap)) {
                                        GlassIconButton(
                                            onClick = { setSearchExpanded(false) },
                                            backdrop = activeMiuixBackdrop,
                                            size = ConversationPanelMetrics.DockButtonSize,
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.Glass.Close,
                                                contentDescription = cancelText,
                                                modifier = Modifier.size(24.dp),
                                                tint = miuixColors.onSurface,
                                            )
                                        }
                                    }
                                },
                                content = {},
                            )
                        },
                        trailingButtons = {
                            PaneDockActionButtons(
                                enabled = !searchExpanded,
                                hasPermissionAttention = hasPermissionAttention,
                                permissionsDesc = permissionsDesc,
                                settingsDesc = settingsDesc,
                                couiBackdrop = null,
                                miuixBackdrop = activeMiuixBackdrop,
                                onOpenPermissions = onOpenPermissions,
                                onOpenSettings = onOpenSettings,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 协调原生 SearchBar 与右侧权限/设置双按钮的同步过渡：
 * 展开时双按钮立即禁用点击、焦点和语义，并随弹簧同步缩放滑出，搜索栏平滑扩展至全宽；
 * 收起时按反向弹簧回归，避免与取消按钮争抢位置或点击范围。
 */
@Composable
private fun CoordinatedDockSearchLayout(
    searchExpanded: Boolean,
    buttonsProgress: () -> Float,
    horizontalMargin: Dp,
    buttonGap: Dp,
    searchBar: @Composable () -> Unit,
    trailingButtons: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { searchBar() }
            Box(
                modifier = Modifier
                    .focusProperties { canFocus = !searchExpanded }
                    .then(if (searchExpanded) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                trailingButtons()
            }
        },
    ) { measurables, constraints ->
        val marginPx = horizontalMargin.roundToPx()
        val gapPx = buttonGap.roundToPx()
        val progress = buttonsProgress().coerceIn(0f, 1f)
        val buttonsPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        val reservedEndPx = ((buttonsPlaceable.width + gapPx) * progress).roundToInt().coerceAtLeast(0)
        val searchWidth = (constraints.maxWidth - 2 * marginPx - reservedEndPx).coerceAtLeast(1)
        val searchPlaceable = measurables[0].measure(
            Constraints.fixedWidth(searchWidth).copy(
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            ),
        )
        val layoutHeight = maxOf(searchPlaceable.height, buttonsPlaceable.height)
        layout(constraints.maxWidth, layoutHeight) {
            searchPlaceable.placeRelative(
                x = marginPx,
                y = (layoutHeight - searchPlaceable.height) / 2,
            )
            if (progress > 0.01f) {
                val slideOutPx = ((1f - progress) * (buttonsPlaceable.width + marginPx) * 0.35f).roundToInt()
                buttonsPlaceable.placeRelativeWithLayer(
                    x = constraints.maxWidth - marginPx - buttonsPlaceable.width + slideOutPx,
                    y = (layoutHeight - buttonsPlaceable.height) / 2,
                ) {
                    alpha = progress
                    val scale = 0.8f + 0.2f * progress
                    scaleX = scale
                    scaleY = scale
                }
            }
        }
    }
}

@Composable
private fun PaneDockActionButtons(
    enabled: Boolean,
    hasPermissionAttention: Boolean,
    permissionsDesc: String,
    settingsDesc: String,
    couiBackdrop: CouiBackdrop?,
    miuixBackdrop: Backdrop?,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val permissionsIcon = if (isCoui) ConversationPaneIcons.Permissions else MiuixIcons.Glass.SecurityStatus
    val settingsIcon = if (isCoui) ConversationPaneIcons.Settings else MiuixIcons.Glass.SettingsOutline
    Row(verticalAlignment = Alignment.CenterVertically) {
        PaneDockCircleButton(
            icon = permissionsIcon,
            contentDescription = permissionsDesc,
            showBadge = hasPermissionAttention,
            enabled = enabled,
            couiBackdrop = couiBackdrop,
            miuixBackdrop = miuixBackdrop,
            onClick = onOpenPermissions,
        )
        Spacer(Modifier.width(ConversationPanelMetrics.BottomDockGap))
        PaneDockCircleButton(
            icon = settingsIcon,
            contentDescription = settingsDesc,
            showBadge = false,
            enabled = enabled,
            couiBackdrop = couiBackdrop,
            miuixBackdrop = miuixBackdrop,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun PaneDockCircleButton(
    icon: ImageVector,
    contentDescription: String,
    showBadge: Boolean,
    enabled: Boolean,
    couiBackdrop: CouiBackdrop?,
    miuixBackdrop: Backdrop? = null,
    onClick: () -> Unit,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val miuixColors = MiuixTheme.colorScheme

    val iconTint = if (isCoui) {
        CouiPageDefaults.themeColors().title
    } else {
        miuixColors.onSurface
    }
    val badgeBorderColor = if (isCoui) {
        CouiTheme.colorScheme.surface
    } else {
        GlassTopAppBarDefaults.buttonFill()
    }

    val buttonSize = if (isCoui) 40.dp else ConversationPanelMetrics.DockButtonSize
    Box(modifier = Modifier.size(buttonSize)) {
        if (isCoui) {
            CouiIconButton(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { this.contentDescription = contentDescription },
                enabled = enabled,
                material = couiBackdrop != null,
                backgroundColor = if (couiBackdrop == null) CouiTheme.colorScheme.surface else Color.Unspecified,
                minWidth = 40.dp,
                minHeight = 40.dp,
            ) {
                CouiIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ConversationPanelMetrics.DockIconSize),
                    tint = iconTint,
                )
            }
        } else {
            GlassIconButton(
                onClick = { if (enabled) onClick() },
                backdrop = miuixBackdrop,
                size = ConversationPanelMetrics.DockButtonSize,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(28.dp),
                    tint = iconTint,
                )
            }
        }

        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (isCoui) 6.dp else 7.dp, end = if (isCoui) 6.dp else 7.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(badgeBorderColor)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(StatusWarning),
            )
        }
    }
}
