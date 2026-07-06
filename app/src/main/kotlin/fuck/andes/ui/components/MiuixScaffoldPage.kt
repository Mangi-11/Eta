package fuck.andes.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 二级页面标准骨架（高层）：Scaffold + 大标题折叠 TopAppBar + 返回按钮 + 内置 LazyColumn
 * （含 overScrollVertical / scrollEndHaptic / nestedScroll 联动 + 横向安全区 + 底部导航栏留白）。
 *
 * 适用于纯列表式页面（SmallTitle + Card）。调用方只需提供 [content] 的 item 内容。
 *
 * 参考 InstallerX-Revived 的二级页面事实标准骨架。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = paddingValues,
            overscrollEffect = null,
        ) {
            content()
            item(key = "bottom_spacer") { Spacer(modifier = Modifier.navigationBarsPadding()) }
        }
    }
}

/**
 * 二级页面骨架（底层）：只提供 Scaffold + 大标题折叠 TopAppBar + 返回按钮 + scrollBehavior，
 * content 由调用方自行决定容器（Column / LazyColumn）并负责挂 [.nestedScroll] 联动。
 *
 * 适用于非纯列表布局（如带 TabRow、网格的页面）。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        content(paddingValues, scrollBehavior)
    }
}
