package io.github.mangi.eta.ui.components.coui

import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageColors
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold
import com.itos.coui.scroll.CouiOverscrollEffect
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiContentColor
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiToolbarStyle
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.CouiTopAppBarScrollBehavior
import com.itos.coui.topappbar.LargeTopAppBar
import com.itos.coui.topappbar.TopAppBar
import com.itos.coui.topappbar.TopAppBarButton
import com.itos.coui.topappbar.TopAppBarButtonGroup
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.rememberIsWideScreen

@Composable
internal fun rememberCouiSystemToolbarStyle(
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
): CouiToolbarStyle {
    val density = LocalDensity.current.density
    val contour = LocalCouiContourGlowEnabled.current
    val textStyle = rememberCouiTextStyle(CouiTextKind.Toolbar)
    return CouiTopAppBarButtonDefaults.style(dark).copy(
        contentColor = colors.title,
        textStyle = textStyle,
        cursorColor = colors.accent,
        material = { CouiTopAppBarButtonDefaults.systemMaterial(dark, it) },
        shadow = { CouiTopAppBarButtonDefaults.shadow(dark, it, density, contour, alphaScale = 1f) },
    )
}

@Composable
internal fun CouiBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    backDescription: String = stringResource(R.string.action_back),
) {
    CouiTopAppBarIconButton(
        icon = CouiIcons.Back,
        contentDescription = backDescription,
        onClick = onClick,
        modifier = modifier,
        dark = dark,
        colors = colors,
    )
}

@Composable
internal fun CouiTopAppBarIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    iconSize: Dp = 24.dp,
    tint: Color = Color.Unspecified,
) {
    val renderer = remember {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { CouiFloatingRenderer.standalone() }.getOrNull()
        } else {
            null
        }
    }
    if (Build.VERSION.SDK_INT >= 34 && renderer != null) {
        val style = rememberCouiSystemToolbarStyle(dark, colors)
        TopAppBarButton(
            modifier = modifier,
            onClick = onClick,
            enabled = enabled,
            renderer = renderer,
            style = style,
        ) {
            val resolvedTint = tint.takeOrElse {
                LocalCouiContentColor.current.takeOrElse { colors.title }
            }
            CouiIcon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = resolvedTint,
            )
        }
    } else {
        val source = remember { MutableInteractionSource() }
        val resolvedTint = tint.takeOrElse { if (enabled) colors.title else colors.disabled }
        Box(
            modifier = modifier
                .size(48.dp)
                .clip(CouiShape(24.dp, 2f))
                .couiButtonFeedback(dark, source, enabled)
                .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            CouiIcon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = resolvedTint,
            )
        }
    }
}

@Composable
internal fun CouiTopAppBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
) {
    val renderer = remember {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { CouiFloatingRenderer.standalone() }.getOrNull()
        } else {
            null
        }
    }
    if (Build.VERSION.SDK_INT >= 34 && renderer != null) {
        val style = rememberCouiSystemToolbarStyle(dark, colors)
        TopAppBarButton(
            modifier = modifier,
            onClick = onClick,
            enabled = enabled,
            text = true,
            renderer = renderer,
            style = style,
        ) {
            val textColor = LocalCouiContentColor.current.takeOrElse {
                if (enabled) colors.title else colors.disabled
            }
            BasicText(
                text = text,
                style = LocalCouiTextStyle.current.copy(color = textColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        val source = remember { MutableInteractionSource() }
        val textStyle = rememberCouiTextStyle(CouiTextKind.SearchCancel)
        val textColor = if (enabled) colors.accent else colors.disabled
        Box(
            modifier = modifier
                .widthIn(min = 60.dp)
                .heightIn(min = 40.dp)
                .clip(CouiCapsuleShape)
                .couiButtonFeedback(dark, source, enabled)
                .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = text,
                style = textStyle.copy(color = textColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * COUI 二级页骨架（ScrollState + Column）：
 * 手机使用大标题弧线折叠顶栏，宽屏或 compactTitle 使用紧凑顶栏；
 * 仅在宿主窗口持有焦点时应用 imePadding，避免输入弹窗打开时背景页面提前空出键盘高度。
 */
@Composable
internal fun CouiScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    scrollState: ScrollState = rememberScrollState(),
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    overscrollEffect: CouiOverscrollEffect? = rememberCouiOverscrollEffect(),
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val useCompact = compactTitle || rememberIsWideScreen()
    val behavior = if (useCompact) {
        null
    } else {
        CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            canScroll = { scrollState.maxValue > 0 },
        )
    }
    val localDensity = LocalDensity.current
    val imePadding = if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier
    val barColors = CouiTopAppBarDefaults.colors(titleColor = colors.title, dark = dark)
    val navigation: @Composable () -> Unit = {
        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
    }
    val topBarActions: @Composable RowScope.() -> Unit = {
        TopAppBarButtonGroup {
            actions()
        }
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(imePadding)
            .then(if (behavior != null) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier),
        containerColor = colors.background,
        floatingActionButton = floatingAction,
        topBar = {
            if (useCompact) {
                TopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = LocalCouiTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = navigation,
                    actions = topBarActions,
                    dark = dark,
                    colors = barColors,
                )
            } else {
                LargeTopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = LocalCouiTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = navigation,
                    actions = topBarActions,
                    scrollBehavior = behavior,
                    dark = dark,
                    colors = barColors,
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = remember(behavior, innerPadding, localDensity) {
            behavior?.state?.contentPadding(innerPadding, localDensity) ?: innerPadding
        }
        WidePageContent { sidePadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .couiVerticalScroll(scrollState, overscrollEffect = overscrollEffect)
                    .padding(contentPadding)
                    .padding(horizontal = sidePadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(bottom = CouiPageDefaults.groupSpacing),
                content = content,
            )
        }
        overlay()
    }
}

/**
 * COUI 二级页骨架（LazyColumn），用于长动态列表页。
 */
@Composable
internal fun CouiLazyScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val useCompact = compactTitle || rememberIsWideScreen()
    val behavior = if (useCompact) {
        null
    } else {
        CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            canScroll = { listState.canScrollForward || listState.canScrollBackward },
        )
    }
    val overscroll = rememberCouiOverscrollEffect()
    val localDensity = LocalDensity.current
    val imePadding = if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier
    val barColors = CouiTopAppBarDefaults.colors(titleColor = colors.title, dark = dark)
    val navigation: @Composable () -> Unit = {
        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
    }
    val topBarActions: @Composable RowScope.() -> Unit = {
        TopAppBarButtonGroup {
            actions()
        }
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(imePadding)
            .then(if (behavior != null) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier),
        containerColor = colors.background,
        floatingActionButton = floatingAction,
        topBar = {
            if (useCompact) {
                TopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = LocalCouiTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = navigation,
                    actions = topBarActions,
                    dark = dark,
                    colors = barColors,
                )
            } else {
                LargeTopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = LocalCouiTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = navigation,
                    actions = topBarActions,
                    scrollBehavior = behavior,
                    dark = dark,
                    colors = barColors,
                )
            }
        },
    ) { innerPadding ->
        val topBarAdjustedPadding = remember(behavior, innerPadding, localDensity) {
            behavior?.state?.contentPadding(innerPadding, localDensity) ?: innerPadding
        }
        WidePageContent { sidePadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    top = topBarAdjustedPadding.calculateTopPadding(),
                    end = sidePadding,
                    bottom = topBarAdjustedPadding.calculateBottomPadding() + CouiPageDefaults.groupSpacing,
                ),
                overscrollEffect = overscroll,
            ) {
                content()
            }
        }
        overlay()
    }
}

/**
 * 自定义容器（如分页、整页编辑器）使用的低层 COUI 二级页骨架。
 */
@Composable
internal fun CouiScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    compactTitle: Boolean = false,
    canScroll: () -> Boolean = { true },
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (
        contentPadding: PaddingValues,
        rawPadding: PaddingValues,
        nestedScrollConnection: NestedScrollConnection?,
        sidePadding: Dp,
    ) -> Unit,
) {
    val useCompact = compactTitle || rememberIsWideScreen()
    val behavior: CouiTopAppBarScrollBehavior? = if (useCompact) {
        null
    } else {
        CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(canScroll = canScroll)
    }
    val localDensity = LocalDensity.current
    val barColors = CouiTopAppBarDefaults.colors(titleColor = colors.title, dark = dark)
    val navigation: @Composable () -> Unit = {
        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
    }
    val topBarActions: @Composable RowScope.() -> Unit = {
        TopAppBarButtonGroup {
            actions()
        }
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(if (behavior != null) Modifier.nestedScroll(behavior.nestedScrollConnection) else Modifier),
        containerColor = colors.background,
        floatingActionButton = floatingAction,
        topBar = {
            if (useCompact) {
                TopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = LocalCouiTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = navigation,
                    actions = topBarActions,
                    dark = dark,
                    colors = barColors,
                )
            } else {
                LargeTopAppBar(
                    title = {
                        BasicText(
                            text = title,
                            style = LocalCouiTextStyle.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = navigation,
                    actions = topBarActions,
                    scrollBehavior = behavior,
                    dark = dark,
                    colors = barColors,
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = remember(behavior, innerPadding, localDensity) {
            behavior?.state?.contentPadding(innerPadding, localDensity) ?: innerPadding
        }
        WidePageContent { sidePadding ->
            content(contentPadding, innerPadding, behavior?.nestedScrollConnection, sidePadding)
        }
    }
}

@Composable
internal fun CouiPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
