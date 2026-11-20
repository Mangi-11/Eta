package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchBar
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchState
import io.github.suqi8.coui.kmp.basic.TopAppBarState
import io.github.suqi8.coui.kmp.basic.glowScrollOffsetPx
import io.github.suqi8.coui.kmp.basic.rememberTopAppBarSearchState
import io.github.suqi8.coui.kmp.basic.rememberTopAppBarState
import io.github.suqi8.coui.kmp.basic.topAppBarSearchContent
import io.github.suqi8.coui.kmp.basic.topAppBarSearchResults
import io.github.suqi8.coui.kmp.blur.TopAppBarGlow
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.rememberCOUIFlingBehavior
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

@Composable
internal fun CouiBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = COUITheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
    backDescription: String = stringResource(R.string.action_back),
) = CouiTopAppBarIconButton(COUIIcons.Back, backDescription, onClick, modifier, tint = colors.title)

@Composable
internal fun CouiTopAppBarIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = COUITheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
    iconSize: Dp = 24.dp,
    tint: Color = Color.Unspecified,
) {
    IconButton(
        onClick = onClick,
        modifier = if (contentDescription == null) modifier else modifier.semantics {
            this.contentDescription = contentDescription
        },
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = if (!enabled) colors.disabled else if (tint == Color.Unspecified) colors.title else tint,
        )
    }
}

@Composable
internal fun CouiTopAppBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = COUITheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
) {
    ToolbarTextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}

@Composable
internal fun CouiScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = COUITheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
    scrollState: ScrollState = rememberScrollState(),
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    CouiScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier.then(if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier),
        dark = dark,
        colors = colors,
        compactTitle = compactTitle,
        actions = actions,
        floatingAction = floatingAction,
        overlay = overlay,
    ) { padding, rawPadding, nestedScroll, sidePadding ->
        // 延迟到测量阶段读取 Scaffold 顶栏高度，与 COUI 折叠弹簧同帧联动且不截断穿过顶栏的凝光模糊。
        val contentPadding = remember(padding, sidePadding) {
            CouiPageContentPadding(
                inner = padding,
                sidePadding = sidePadding,
                extraBottom = CouiPageDefaults.groupSpacing,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(rawPadding)
                .scrollEndHaptic()
                .overScrollVertical()
                .then(if (nestedScroll != null) Modifier.nestedScroll(nestedScroll) else Modifier)
                .verticalScroll(
                    state = scrollState,
                    flingBehavior = rememberCOUIFlingBehavior(),
                    overscrollEffect = null,
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

internal class CouiPageSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val label: String,
)

@Composable
internal fun CouiLazyScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = COUITheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    search: CouiPageSearch? = null,
    content: LazyListScope.(searching: Boolean) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchState = if (search == null) null else rememberTopAppBarSearchState(searching)
    val resultsListState = if (search == null) null else rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (resultsListState != null) {
        LaunchedEffect(resultsListState.isScrollInProgress, searching) {
            if (searching && resultsListState.isScrollInProgress) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
    }
    CouiScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier.then(if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier),
        dark = dark,
        colors = colors,
        compactTitle = compactTitle,
        canScroll = { !searching },
        actions = actions,
        floatingAction = floatingAction,
        searchState = searchState,
        overlay = overlay,
        topBarBottomContent = {
            if (search != null) {
                TopAppBarSearchBar(
                    query = search.query,
                    onQueryChange = search.onQueryChange,
                    expanded = searching,
                    onExpandedChange = {
                        searching = it
                        if (!it) {
                            search.onQueryChange("")
                            keyboard?.hide()
                        }
                    },
                    cancelText = stringResource(R.string.action_cancel),
                    label = search.label,
                )
            }
        },
    ) { padding, rawPadding, nestedScroll, sidePadding ->
        val contentPadding = remember(padding, sidePadding) {
            CouiPageContentPadding(
                inner = padding,
                sidePadding = sidePadding,
                extraBottom = CouiPageDefaults.groupSpacing,
            )
        }
        Box(Modifier.fillMaxSize().consumeWindowInsets(rawPadding)) {
            if (searchState == null || searchState.isContentVisible) {
                CouiPageList(
                    state = listState,
                    contentPadding = contentPadding,
                    nestedScroll = nestedScroll,
                    modifier = if (searchState == null) Modifier else Modifier.topAppBarSearchContent(searchState),
                ) { content(false) }
            }
            if (searchState?.isResultsVisible == true && resultsListState != null) {
                CouiPageList(
                    state = resultsListState,
                    contentPadding = contentPadding,
                    nestedScroll = null,
                    modifier = Modifier.topAppBarSearchResults(searchState),
                ) {
                    content(true)
                }
            }
        }
    }
}

@Composable
private fun CouiPageList(
    state: LazyListState,
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection?,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .then(if (nestedScroll != null) Modifier.nestedScroll(nestedScroll) else Modifier),
        contentPadding = contentPadding,
        flingBehavior = rememberCOUIFlingBehavior(),
        overscrollEffect = null,
        content = content,
    )
}

@Composable
internal fun CouiScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = COUITheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(),
    compactTitle: Boolean = false,
    topBarState: TopAppBarState = rememberTopAppBarState(),
    canScroll: () -> Boolean = { true },
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    searchState: TopAppBarSearchState? = null,
    topBarBottomContent: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, PaddingValues, NestedScrollConnection?, Dp) -> Unit,
) {
    val compact = searchState == null && (compactTitle || rememberIsWideScreen())
    val behavior = COUIScrollBehavior(state = topBarState, canScroll = canScroll)
    val backdrop = rememberCouiPageBackdrop(colors.background)
    val barColor = if (backdrop == null) colors.background else Color.Transparent
    val glow: (@Composable BoxScope.() -> Unit)? = if (backdrop == null) {
        null
    } else {
        {
            TopAppBarGlow(
                backdrop = backdrop,
                scrollOffsetPx = behavior.glowScrollOffsetPx(),
                isDark = dark,
                baseColor = colors.background,
                enabled = true,
            )
        }
    }
    val navigation: @Composable () -> Unit = { CouiBackButton(onBack, colors = colors) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        floatingActionButton = {
            if (floatingAction != null) {
                ProvideCouiMaterial(backdrop) {
                    Box(content = floatingAction)
                }
            }
        },
        topBar = {
            ProvideCouiMaterial(backdrop) {
                if (compact) {
                    SmallTopAppBar(
                        title = title,
                        color = barColor,
                        titleColor = colors.title,
                        navigationIcon = navigation,
                        actions = actions,
                        scrollBehavior = behavior,
                        glow = glow,
                        bottomContent = topBarBottomContent,
                    )
                } else {
                    TopAppBar(
                        title = title,
                        color = barColor,
                        titleColor = colors.title,
                        navigationIcon = navigation,
                        actions = actions,
                        glow = glow,
                        scrollBehavior = behavior,
                        searchState = searchState,
                        bottomContent = topBarBottomContent,
                    )
                }
            }
        },
    ) { padding ->
        CouiBackdropContent(backdrop) {
            WidePageContent { sidePadding ->
                content(padding, padding, behavior.nestedScrollConnection, sidePadding)
            }
            overlay()
        }
    }
}

@Stable
private class CouiPageContentPadding(
    private val inner: PaddingValues,
    private val sidePadding: Dp,
    private val extraBottom: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        inner.calculateLeftPadding(layoutDirection) + sidePadding

    override fun calculateTopPadding(): Dp = inner.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        inner.calculateRightPadding(layoutDirection) + sidePadding

    override fun calculateBottomPadding(): Dp =
        inner.calculateBottomPadding() + extraBottom
}

@Composable
internal fun CouiPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.height(24.dp))
}
