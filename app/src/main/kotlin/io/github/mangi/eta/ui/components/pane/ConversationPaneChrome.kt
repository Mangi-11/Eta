package io.github.mangi.eta.ui.components.pane

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.layout.rememberFloatingBottomBarInsets
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
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
import top.yukonga.miuix.kmp.icon.glass.SettingsFill
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.components.progressiveTopBarBackdrop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    // MiuixIcons.Glass 视口四周带安全边距，24.dp 渲染对应约 18.dp 光学主体，匹配 15.sp 标题。
    val WorkbenchIconSize = 24.dp
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
 * 浅色下保持与设置页一致的温和浅底（surface），使卡片层次分明；
 * 深色下使用抬升表面色（surface 与 surfaceContainer 插值），
 * 确保侧栏与右侧聊天黑底之间边界清晰。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return if (colors.surface.luminance() > 0.5f) {
        colors.surface
    } else {
        lerp(colors.surface, colors.surfaceContainer, 0.45f)
    }
}

@Composable
internal fun conversationPaneCardColor(): Color = MiuixTheme.colorScheme.surfaceContainer

@Composable
internal fun conversationPaneEdgeBorderColor(): Color =
    MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.42f)

@Composable
internal fun ConversationPaneTopBar(
    containerColor: Color,
    backdrop: LayerBackdrop?,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fallbackBandBrush = remember(containerColor) {
        Brush.verticalGradient(
            0.0f to containerColor,
            0.8f to containerColor.copy(alpha = 0.94f),
            1.0f to Color.Transparent,
        )
    }
    TopBarBackdrop(
        progressiveBackdrop = progressiveTopBarBackdrop(backdrop),
        backgroundColor = containerColor,
        fallbackBrush = fallbackBandBrush,
        modifier = modifier.fillMaxWidth(),
    ) {
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
                backdrop = backdrop,
                onClick = onNewConversation,
            )
        }
    }
}

@Composable
private fun PaneBrandHeader(modifier: Modifier = Modifier) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accent = MiuixTheme.colorScheme.primary
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

@Composable
private fun PaneNewChatButton(
    backdrop: Backdrop?,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.action_new_conversation)
    val colors = MiuixTheme.colorScheme
    GlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
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

@Composable
internal fun ConversationPaneBottomDock(
    query: String,
    isRevealed: Boolean,
    hasPermissionAttention: Boolean,
    containerColor: Color,
    backdrop: LayerBackdrop?,
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

    val progressiveBlurEnabled = LocalAppearanceSettings.current.progressiveBlurEnabled
    val activeBackdrop = if (isRuntimeShaderSupported()) backdrop else null
    val progressiveBackdrop = if (progressiveBlurEnabled) activeBackdrop else null

    val bottomScrimBrush = remember(containerColor) {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.25f to containerColor.copy(alpha = 0.72f),
            0.55f to containerColor.copy(alpha = 0.94f),
            1.0f to containerColor,
        )
    }

    val backdropModifier = if (progressiveBackdrop != null) {
        Modifier
            .progressiveTextureBlur(
                backdrop = progressiveBackdrop,
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
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 360f),
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
            val colors = MiuixTheme.colorScheme
            val isDark = colors.surface.luminance() < 0.5f
            val pillShape = remember { GlassShape(ConversationPanelMetrics.BottomDockHeight / 2) }
            val fallbackBg = GlassTopAppBarDefaults.buttonFill()
            CoordinatedDockSearchLayout(
                searchExpanded = searchExpanded,
                buttonsProgress = { buttonsProgress },
                horizontalMargin = ConversationPanelMetrics.PaneHorizontalPadding,
                buttonGap = ConversationPanelMetrics.BottomDockGap,
                searchBar = {
                    SearchBar(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = DpSize(0.dp, 0.dp),
                        expanded = searchExpanded,
                        onExpandedChange = setSearchExpanded,
                        inputField = {
                            InputField(
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
                                        backdrop = activeBackdrop,
                                        shape = pillShape,
                                        style = GlassDefaults.style(isDark),
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
                                        tint = if (searchExpanded) colors.primary else colors.onSurfaceVariantSummary,
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
                                                tint = colors.onSurfaceVariantActions,
                                            )
                                        }
                                    }
                                },
                            )
                        },
                        outsideEndAction = {
                            Box(
                                modifier = Modifier.padding(
                                    start = ConversationPanelMetrics.BottomDockGap,
                                    top = 16.dp,
                                    bottom = 16.dp,
                                ),
                            ) {
                                GlassIconButton(
                                    onClick = { setSearchExpanded(false) },
                                    backdrop = activeBackdrop,
                                    size = ConversationPanelMetrics.DockButtonSize,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.Close,
                                        contentDescription = cancelText,
                                        modifier = Modifier.size(24.dp),
                                        tint = colors.onSurface,
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
                        backdrop = activeBackdrop,
                        onOpenPermissions = onOpenPermissions,
                        onOpenSettings = onOpenSettings,
                    )
                },
            )
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
                Box(modifier = Modifier.padding(16.dp)) {
                    trailingButtons()
                }
            }
        },
    ) { measurables, constraints ->
        val marginPx = horizontalMargin.roundToPx()
        val gapPx = buttonGap.roundToPx()
        val progress = buttonsProgress().fastCoerceIn(0f, 1f)
        val shadowPadPx = 16.dp.roundToPx()
        val buttonsPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        val buttonsWidth = (buttonsPlaceable.width - 2 * shadowPadPx).coerceAtLeast(0)
        val buttonsHeight = (buttonsPlaceable.height - 2 * shadowPadPx).coerceAtLeast(0)
        val reservedEndPx = ((buttonsWidth + gapPx) * progress).fastRoundToInt().coerceAtLeast(0)
        val searchWidth = (constraints.maxWidth - 2 * marginPx - reservedEndPx).coerceAtLeast(1)
        val searchPlaceable = measurables[0].measure(
            Constraints.fixedWidth(searchWidth).copy(
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )
        val layoutHeight = buttonsHeight.coerceAtLeast(ConversationPanelMetrics.BottomDockHeight.roundToPx())
        layout(constraints.maxWidth, layoutHeight) {
            searchPlaceable.placeRelative(
                x = marginPx,
                y = (layoutHeight - searchPlaceable.height) / 2,
            )
            if (progress > 0.01f) {
                val slideOutPx = ((1f - progress) * (buttonsWidth + marginPx) * 0.35f).fastRoundToInt()
                val x = constraints.maxWidth - marginPx - buttonsWidth - shadowPadPx + slideOutPx
                val y = (layoutHeight - buttonsPlaceable.height) / 2
                if (progress >= 0.999f) {
                    buttonsPlaceable.placeRelative(x = x, y = y)
                } else {
                    buttonsPlaceable.placeRelativeWithLayer(x = x, y = y) {
                        alpha = progress
                        val scale = 0.8f + 0.2f * progress
                        scaleX = scale
                        scaleY = scale
                    }
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
    backdrop: Backdrop?,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PaneDockCircleButton(
            icon = MiuixIcons.Glass.SecurityStatus,
            contentDescription = permissionsDesc,
            showBadge = hasPermissionAttention,
            enabled = enabled,
            backdrop = backdrop,
            onClick = onOpenPermissions,
        )
        Spacer(Modifier.width(ConversationPanelMetrics.BottomDockGap))
        PaneDockCircleButton(
            icon = MiuixIcons.Glass.SettingsFill,
            contentDescription = settingsDesc,
            showBadge = false,
            enabled = enabled,
            backdrop = backdrop,
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
    backdrop: Backdrop? = null,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val iconTint = colors.onSurface
    val badgeBorderColor = GlassTopAppBarDefaults.buttonFill()

    Box(modifier = Modifier.size(ConversationPanelMetrics.DockButtonSize)) {
        GlassIconButton(
            onClick = { if (enabled) onClick() },
            backdrop = backdrop,
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

        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
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
