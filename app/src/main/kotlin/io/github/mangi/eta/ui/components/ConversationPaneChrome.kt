package io.github.mangi.eta.ui.components

import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.LinearGradient
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.ProvideCouiBackdrop
import com.itos.coui.backdrop.drawCouiBackdrop
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.CouiFloatingSurface
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.effect.couiFloatingSurface
import com.itos.coui.effect.couiToolbarFeedback
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.couiTouchMotion
import com.itos.coui.motion.rememberCouiPressMotion
import com.itos.coui.motion.rememberCouiTouchMotion
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.TopAppBarSurface
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 8.dp
    val ActionIconSize = 20.dp
    val EmptyVerticalPadding = 36.dp
}

/**
 * 侧栏与外层推移容器共用的背景色。
 *
 * 浅色下保持与各自系统设置页一致的温和浅底（COUI #F0F1F2 / Miuix surface），使纯白分组卡片浮雕分明；
 * 深色下将侧栏稳稳抬到介于聊天黑底与前景卡片之间的层级，确保两侧界面与卡片各自独立可辨。
 */
@Composable
internal fun conversationPaneContainerColor(): Color {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        return if (CouiTheme.isDark) Color(0xFF16171B) else CouiPageDefaults.themeColors(false).background
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
        return if (CouiTheme.isDark) Color(0xFF212328) else Color.White
    }
    return MiuixTheme.colorScheme.surfaceContainer
}

@Composable
internal fun conversationPaneEdgeBorderColor(): Color {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        return if (CouiTheme.isDark) Color(0x16FFFFFF) else Color(0x10000000)
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
                onClick = onNewConversation,
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            if (couiBackdrop != null) {
                TopAppBarSurface(
                    modifier = modifier.fillMaxWidth(),
                    backdrop = couiBackdrop,
                    gradientColor = containerColor,
                    dark = dark,
                ) {
                    barContent()
                }
            } else {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0.0f to containerColor,
                                0.8f to containerColor.copy(alpha = 0.94f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                ) {
                    barContent()
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            TopBarBackdrop(backdrop = miuixBackdrop) {
                Box(modifier = modifier.fillMaxWidth()) {
                    barContent()
                }
            }
        }
    }
}

@Composable
private fun PaneBrandHeader(modifier: Modifier = Modifier) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val dark = if (isCoui) CouiTheme.isDark else MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accent = if (isCoui) CouiTheme.colorScheme.accent else MiuixTheme.colorScheme.primary
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
                kind = CouiTextKind.SectionTitle,
                color = CouiPageDefaults.themeColors(dark).title,
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
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.action_new_conversation)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            if (hasCouiToolbarBackdrop) {
                CouiTopAppBarIconButton(
                    icon = ChatActionIcons.NewChat,
                    contentDescription = description,
                    onClick = onClick,
                    dark = dark,
                    colors = colors,
                    iconSize = ConversationPanelMetrics.NewChatIconSize,
                )
            } else {
                val source = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (dark) Color(0xFF252830) else Color.White, CircleShape)
                        .border(0.5.dp, if (dark) Color(0x1EFFFFFF) else Color(0x12000000), CircleShape)
                        .couiButtonFeedback(dark, source, enabled = true)
                        .clickable(
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    CouiIcon(
                        imageVector = ChatActionIcons.NewChat,
                        contentDescription = null,
                        modifier = Modifier.size(ConversationPanelMetrics.NewChatIconSize),
                        tint = colors.title,
                    )
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            val colors = MiuixTheme.colorScheme
            val isDark = colors.surface.luminance() < 0.5f
            val bg = if (isDark) colors.surfaceContainer else colors.surfaceContainer
            IconButton(
                onClick = onClick,
                minWidth = 40.dp,
                minHeight = 40.dp,
                backgroundColor = bg.copy(alpha = if (isDark) 0.85f else 0.92f),
                modifier = Modifier.border(
                    width = 0.5.dp,
                    color = colors.outline.copy(alpha = if (isDark) 0.36f else 0.22f),
                    shape = CircleShape,
                ),
            ) {
                Icon(
                    imageVector = ChatActionIcons.NewChat,
                    contentDescription = description,
                    modifier = Modifier.size(ConversationPanelMetrics.NewChatIconSize),
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
    val dockInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        .let { base -> if (applyIme) base.union(WindowInsets.ime) else base }

    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val view = LocalView.current
    val activeMiuixBackdrop = if (
        !isCoui &&
        LocalBlurEnabled.current &&
        isRuntimeShaderSupported()
    ) {
        miuixBackdrop
    } else {
        null
    }
    val activeCouiBackdrop = if (isCoui && view.display != null) couiBackdrop else null

    val bottomScrimBrush = remember(containerColor) {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.22f to containerColor.copy(alpha = 0.68f),
            0.52f to containerColor.copy(alpha = 0.94f),
            1.0f to containerColor,
        )
    }

    val backdropModifier = when {
        activeMiuixBackdrop != null -> {
            Modifier
                .progressiveTextureBlur(
                    backdrop = activeMiuixBackdrop,
                    shape = RectangleShape,
                    gradient = ProgressiveBlur.Bottom.copy(curve = 2.1f),
                    blurRadius = 22f,
                    colors = BlurColors(
                        blendColors = listOf(
                            BlendColorEntry(containerColor.copy(alpha = 0.64f)),
                        ),
                    ),
                )
                .background(bottomScrimBrush)
        }
        activeCouiBackdrop != null -> {
            // 底部羽化高斯模糊：将滑入底栏下方的列表文字先虚化再经渐隐遮罩吸收，
            // 既保留 COUI 悬浮玻璃胶囊的边缘光学，又避免清晰文字穿透到搜索图标下方干扰阅读。
            Modifier
                .drawCouiBackdrop(
                    backdrop = activeCouiBackdrop,
                    shape = { RectangleShape },
                    effects = {
                        if (size.width > 0f && size.height > 0f) {
                            val blurPx = (16.dp.toPx()).coerceAtLeast(1f)
                            padding = kotlin.math.ceil(blurPx)
                            val blurred = AndroidRenderEffect.createBlurEffect(
                                blurPx,
                                blurPx,
                                Shader.TileMode.CLAMP,
                            )
                            val fadeTop = padding
                            val fadeBottom = padding + size.height * 0.48f
                            val mask = AndroidRenderEffect.createShaderEffect(
                                LinearGradient(
                                    0f,
                                    fadeTop,
                                    0f,
                                    fadeBottom.coerceAtLeast(fadeTop + 1f),
                                    intArrayOf(
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Color.argb(180, 0, 0, 0),
                                        android.graphics.Color.BLACK,
                                    ),
                                    floatArrayOf(0f, 0.45f, 1f),
                                    Shader.TileMode.CLAMP,
                                ),
                            )
                            renderEffect = AndroidRenderEffect
                                .createBlendModeEffect(blurred, mask, AndroidBlendMode.DST_IN)
                                .asComposeRenderEffect()
                        }
                    },
                )
                .background(bottomScrimBrush)
        }
        else -> Modifier.background(bottomScrimBrush)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(backdropModifier)
            .windowInsetsPadding(dockInsets)
            .padding(
                start = ConversationPanelMetrics.PaneHorizontalPadding,
                end = ConversationPanelMetrics.PaneHorizontalPadding,
                top = ConversationPanelMetrics.BottomDockVerticalPadding + 14.dp,
                bottom = ConversationPanelMetrics.BottomDockVerticalPadding,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.BottomDockGap),
        ) {
            PaneSearchCapsule(
                query = query,
                onSearchChange = onSearchChange,
                couiBackdrop = couiBackdrop,
                modifier = Modifier.weight(1f),
            )
            PaneDockCircleButton(
                icon = ConversationPaneIcons.Permissions,
                contentDescription = if (hasPermissionAttention) {
                    stringResource(R.string.conversation_permission_attention)
                } else {
                    stringResource(R.string.route_permissions)
                },
                showBadge = hasPermissionAttention,
                couiBackdrop = couiBackdrop,
                onClick = onOpenPermissions,
            )
            PaneDockCircleButton(
                icon = ConversationPaneIcons.Settings,
                contentDescription = stringResource(R.string.route_settings),
                showBadge = false,
                couiBackdrop = couiBackdrop,
                onClick = onOpenSettings,
            )
        }
    }
}

private class PaneSearchSyncState(var lastSynced: String)

@Composable
private fun PaneSearchCapsule(
    query: String,
    onSearchChange: (String) -> Unit,
    couiBackdrop: CouiBackdrop?,
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.conversation_search_hint)
    val clearDescription = stringResource(R.string.page_clear_84fcd7)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }

    val textFieldState = rememberTextFieldState(initialText = query)
    val syncState = remember { PaneSearchSyncState(query) }
    val currentOnSearchChange by rememberUpdatedState(onSearchChange)

    LaunchedEffect(query) {
        syncState.lastSynced = query
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { currentText ->
                if (currentText != syncState.lastSynced) {
                    syncState.lastSynced = currentText
                    currentOnSearchChange(currentText)
                }
            }
    }

    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val miuixColors = MiuixTheme.colorScheme
    val miuixDark = miuixColors.surface.luminance() < 0.5f

    val textColor = if (isCoui) {
        CouiPageDefaults.themeColors(couiDark).title
    } else {
        LocalContentColor.current
    }
    var activeTextColor by remember { mutableStateOf(textColor) }
    LaunchedEffect(textColor) {
        activeTextColor = textColor
    }
    val accentColor = if (isCoui) CouiTheme.colorSchemeFor(couiDark).accent else miuixColors.primary
    val hintColor = if (isCoui) {
        if (couiDark) Color(0x70FFFFFF) else Color(0x66000000)
    } else {
        miuixColors.onSurfaceVariantSummary
    }
    val clearBgColor = if (isCoui) {
        if (couiDark) Color(0x4DFFFFFF) else Color(0x42000000)
    } else {
        miuixColors.onSurfaceVariantActions.copy(alpha = 0.65f)
    }
    // 延迟一帧到节点附着完成后再切换 TextStyle 颜色，规避 Compose 1.12 BasicTextField 在
    // AgentAppTheme movableContentOf 跨容器迁移节点时于未附着状态触发 TextFieldSizeConstrainerNode.update。
    val textStyle = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = activeTextColor,
    )

    val searchContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ConversationPanelMetrics.BottomDockHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ConversationPaneIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isFocused) accentColor else hintColor,
            )
            Spacer(Modifier.width(9.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (textFieldState.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = hintColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                key(isCoui) {
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(accentColor),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = {
                            keyboard?.hide()
                            focusManager.clearFocus()
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = textFieldState.text.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = clearDescription }
                        .clickable(role = Role.Button) {
                            syncState.lastSynced = ""
                            textFieldState.clearText()
                            currentOnSearchChange("")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PaneClearGlyph(
                        modifier = Modifier.size(17.dp),
                        color = clearBgColor,
                    )
                }
            }
        }
    }

    if (isCoui) {
        CouiPaneFloatingSurface(
            modifier = modifier.height(ConversationPanelMetrics.BottomDockHeight),
            shape = CouiCapsuleShape,
            capsule = true,
            couiBackdrop = couiBackdrop,
            focused = isFocused,
            accentColor = accentColor,
            onClick = null,
        ) {
            searchContent()
        }
    } else {
        val pillShape = CircleShape
        val bgColor = miuixColors.surfaceContainer
        val borderColor = if (isFocused) {
            accentColor.copy(alpha = 0.55f)
        } else {
            miuixColors.outline.copy(alpha = if (miuixDark) 0.36f else 0.24f)
        }
        Box(
            modifier = modifier
                .height(ConversationPanelMetrics.BottomDockHeight)
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = if (miuixDark) 0.22f else 0.05f,
                    ),
                )
                .clip(pillShape)
                .background(bgColor, pillShape)
                .border(if (isFocused) 1.dp else 0.5.dp, borderColor, pillShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            searchContent()
        }
    }
}

@Composable
private fun PaneDockCircleButton(
    icon: ImageVector,
    contentDescription: String,
    showBadge: Boolean,
    couiBackdrop: CouiBackdrop?,
    onClick: () -> Unit,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val miuixColors = MiuixTheme.colorScheme
    val miuixDark = miuixColors.surface.luminance() < 0.5f

    val iconTint = if (isCoui) {
        CouiPageDefaults.themeColors(couiDark).title
    } else {
        miuixColors.onSurface
    }
    val badgeBorderColor = if (isCoui) {
        if (couiDark) Color(0xFF23252A) else Color.White
    } else {
        miuixColors.surfaceContainer
    }

    Box(modifier = Modifier.size(ConversationPanelMetrics.DockButtonSize)) {
        if (isCoui) {
            CouiPaneFloatingSurface(
                modifier = Modifier.fillMaxSize(),
                shape = CouiTopAppBarButtonDefaults.circleShape,
                capsule = false,
                couiBackdrop = couiBackdrop,
                focused = false,
                accentColor = CouiTheme.colorSchemeFor(couiDark).accent,
                contentDescription = contentDescription,
                onClick = onClick,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CouiIcon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(ConversationPanelMetrics.DockIconSize),
                        tint = iconTint,
                    )
                }
            }
        } else {
            val bgColor = miuixColors.surfaceContainer
            val borderColor = miuixColors.outline.copy(alpha = if (miuixDark) 0.36f else 0.24f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .dropShadow(
                        shape = CircleShape,
                        shadow = Shadow(
                            radius = 10.dp,
                            color = Color.Black,
                            alpha = if (miuixDark) 0.22f else 0.05f,
                        ),
                    ),
            ) {
                IconButton(
                    onClick = onClick,
                    minWidth = ConversationPanelMetrics.DockButtonSize,
                    minHeight = ConversationPanelMetrics.DockButtonSize,
                    backgroundColor = bgColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .border(0.5.dp, borderColor, CircleShape),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(ConversationPanelMetrics.DockIconSize),
                        tint = iconTint,
                    )
                }
            }
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

/**
 * COUI 侧边栏浮层胶囊/圆钮：在 API 34+ 且真实 Display 与背景源就绪时接入 COUI 流体材质与光效，
 * 其余环境平滑降级为连续圆角实色浮层，保持相同的按压回弹与轮廓对比度。
 */
@Composable
private fun CouiPaneFloatingSurface(
    modifier: Modifier,
    shape: Shape,
    capsule: Boolean,
    couiBackdrop: CouiBackdrop?,
    focused: Boolean,
    accentColor: Color,
    contentDescription: String? = null,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val dark = CouiTheme.isDark
    val view = LocalView.current
    val renderer = remember {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { CouiFloatingRenderer.standalone() }.getOrNull()
        } else {
            null
        }
    }
    if (Build.VERSION.SDK_INT >= 34 &&
        couiBackdrop != null &&
        renderer != null &&
        view.display != null
    ) {
        ProvideCouiBackdrop(couiBackdrop) {
            CouiApi34FloatingSurface(
                modifier = modifier,
                shape = shape,
                capsule = capsule,
                backdrop = couiBackdrop,
                renderer = renderer,
                dark = dark,
                focused = focused,
                accentColor = accentColor,
                contentDescription = contentDescription,
                onClick = onClick,
                content = content,
            )
        }
    } else {
        val fallbackBg = if (dark) Color(0xFF252830) else Color.White
        val borderColor = when {
            focused -> accentColor.copy(alpha = 0.65f)
            dark -> Color(0x1EFFFFFF)
            else -> Color(0x12000000)
        }
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = modifier
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 12.dp,
                        color = Color.Black,
                        alpha = if (dark) 0.24f else 0.06f,
                    ),
                )
                .clip(shape)
                .background(fallbackBg, shape)
                .border(if (focused) 1.dp else 0.5.dp, borderColor, shape)
                .then(
                    if (onClick != null) {
                        Modifier
                            .couiButtonFeedback(dark, interactionSource, enabled = true)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Button,
                                onClick = onClick,
                            )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@RequiresApi(34)
@Composable
private fun CouiApi34FloatingSurface(
    modifier: Modifier,
    shape: Shape,
    capsule: Boolean,
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    dark: Boolean,
    focused: Boolean,
    accentColor: Color,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val contour = LocalCouiContourGlowEnabled.current
    val material = remember(dark, capsule) {
        CouiTopAppBarButtonDefaults.material(dark = dark, capsule = capsule, search = capsule)
    }
    val shadow = remember(dark, capsule, density, contour) {
        CouiTopAppBarButtonDefaults.shadow(
            dark = dark,
            capsule = capsule,
            density = density,
            contour = contour,
            search = capsule,
        )
    }
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }
    val contentLayer = rememberGraphicsLayer()
    val interactive = onClick != null
    val motion = rememberCouiTouchMotion(interactive)
    val press = rememberCouiPressMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = interactive,
        preset = if (capsule) SpotlightPreset.OpaqueMedium2 else SpotlightPreset.OpaqueSmall2,
        dark = dark,
    )

    Box(
        modifier = modifier
            .onSizeChanged { boundsSize = it }
            .drawWithCache {
                val lightClip = Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
                }
                onDrawWithContent {
                    drawContent()
                    if (drawLight != null) {
                        withTransform({
                            translate(motion.translationX.value, motion.translationY.value)
                            scale(
                                motion.scaleX.value + press.scale.value - 1f,
                                motion.scaleY.value + press.scale.value - 1f,
                                center,
                            )
                        }) {
                            clipPath(lightClip) { drawLight.invoke(this) }
                        }
                    }
                }
            }
            .couiFloatingSurface(
                backdrop = backdrop,
                renderer = renderer,
                material = material,
                shadow = shadow,
                shape = shape,
                contentLayer = contentLayer,
                contourGlowEnabled = contour,
                foregroundComposition = true,
            ) {
                CouiFloatingSurface(
                    bounds = Rect(
                        Offset(motion.translationX.value, motion.translationY.value),
                        Size(boundsSize.width.toFloat(), boundsSize.height.toFloat()),
                    ),
                    scaleX = motion.scaleX.value + press.scale.value - 1f,
                    scaleY = motion.scaleY.value + press.scale.value - 1f,
                    cornerWeight = if (capsule) 1f else 2f,
                )
            }
            .then(
                if (focused) {
                    Modifier.border(1.dp, accentColor.copy(alpha = 0.55f), shape)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = motion.translationX.value
                    translationY = motion.translationY.value
                    scaleX = motion.scaleX.value + press.scale.value - 1f
                    scaleY = motion.scaleY.value + press.scale.value - 1f
                }
                .then(
                    if (interactive) {
                        Modifier
                            .couiTouchMotion(motion, true)
                            .couiPressMotion(press, enabled = true)
                            .then(lightInput)
                    } else {
                        Modifier
                    },
                )
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                }
                .clip(shape)
                .then(
                    if (onClick != null) {
                        Modifier
                            .couiToolbarFeedback(dark, interactionSource, enabled = true)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Button,
                                onClick = onClick,
                            )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun PaneClearGlyph(
    modifier: Modifier,
    color: Color,
) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val unit = size.width / 22f
        drawCircle(color, 9f * unit)
        drawLine(
            Color.Transparent,
            Offset(8.03f, 8.03f) * unit,
            Offset(13.97f, 13.97f) * unit,
            1.65f * unit,
            StrokeCap.Round,
            blendMode = BlendMode.Clear,
        )
        drawLine(
            Color.Transparent,
            Offset(13.97f, 8.03f) * unit,
            Offset(8.03f, 13.97f) * unit,
            1.65f * unit,
            StrokeCap.Round,
            blendMode = BlendMode.Clear,
        )
    }
}
