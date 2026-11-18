package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Eta 二级列表页的统一 OS4 玻璃骨架：手机折叠大标题、宽屏固定小标题、GlassTopAppBar、
 * 横屏安全区、宽屏内容居中、滚动边界触感与越界回弹均由此处统一提供。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable (Backdrop?) -> Unit = {},
    overlay: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    compactTitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val pageBackdrop = rememberGlassBackdrop()
    val isContentScrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val expandable = !compactTitle && !rememberIsWideScreen()
    val scrollState = scrollBehavior.state
    val onOverscrollChanged: (Float) -> Unit = remember(scrollState) {
        { scrollState.overscrollOffset = it }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                backdrop = pageBackdrop,
                expandable = expandable,
                scrollBehavior = scrollBehavior,
                isContentScrolled = isContentScrolled,
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                actions = actions,
                bottomContent = { topBarBottomContent(pageBackdrop) },
            )
        },
    ) { innerPadding ->
        CompositionLocalProvider(LocalGlassPageBackdrop provides pageBackdrop) {
            WidePageContent { sidePadding ->
                // 保留 MiuixTheme 注入的默认越界工厂，并同步回弹偏移给顶栏。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .captureGlassBackdrop(pageBackdrop)
                        .horizontalCutoutPadding()
                        .scrollEndHaptic()
                        .overScrollVertical(onOffsetChanged = onOverscrollChanged)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = sidePadding,
                        top = innerPadding.calculateTopPadding(),
                        end = sidePadding,
                    ),
                ) {
                    content()
                    item(key = "bottom_spacer") {
                        MiuixPageBottomSpacer()
                    }
                }
            }
            overlay()
        }
    }
}

/**
 * 自定义内容二级页的低层 OS4 玻璃骨架。调用方负责把顶部 padding、横向安全区与 nested scroll
 * 接入自己的内容；[sidePadding] 用于在宽屏限制实际内容宽度，滚动容器本身仍应保持全宽。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable (Backdrop?) -> Unit = {},
    overlay: @Composable () -> Unit = {},
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    isContentScrolled: Boolean? = null,
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    val pageBackdrop = rememberGlassBackdrop()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                backdrop = pageBackdrop,
                scrollBehavior = scrollBehavior,
                isContentScrolled = isContentScrolled,
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                actions = actions,
                bottomContent = { topBarBottomContent(pageBackdrop) },
            )
        },
    ) { paddingValues ->
        CompositionLocalProvider(LocalGlassPageBackdrop provides pageBackdrop) {
            Box(modifier = Modifier.fillMaxSize().captureGlassBackdrop(pageBackdrop)) {
                WidePageContent { sidePadding ->
                    content(paddingValues, scrollBehavior, sidePadding)
                }
            }
            overlay()
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
