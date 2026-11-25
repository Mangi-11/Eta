package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import io.github.mangi.eta.ui.app.LocalTopLayer

/**
 * 全窗口顶层承载器状态：将页内弹层提升到根宿主，避免被顶栏、导航容器或侧栏裁剪或遮挡。
 */
@Stable
internal class TopLayerState {
    val entries = mutableStateListOf<TopLayerEntry>()
}

@Stable
internal class TopLayerEntry {
    var content by mutableStateOf<@Composable BoxScope.() -> Unit>({})
}

/**
 * 将 [content] 提升到最近的 [TopLayerHost] 渲染；在独立测试等无顶层宿主的环境中回退为原地全尺寸容器。
 */
@Composable
internal fun TopLayer(content: @Composable BoxScope.() -> Unit) {
    val layer = LocalTopLayer.current
    if (layer == null) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
        return
    }
    val entry = remember { TopLayerEntry() }
    SideEffect { entry.content = content }
    DisposableEffect(layer, entry) {
        layer.entries += entry
        onDispose { layer.entries -= entry }
    }
}

@Composable
internal fun TopLayerHost(
    state: TopLayerState,
    modifier: Modifier = Modifier,
    onActiveOverlayChanged: (Boolean) -> Unit = {},
) {
    val currentOnActiveOverlayChanged by rememberUpdatedState(onActiveOverlayChanged)
    LaunchedEffect(state.entries.size) {
        if (state.entries.isEmpty()) {
            currentOnActiveOverlayChanged(false)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.onSizeChanged { size ->
                currentOnActiveOverlayChanged(size.width > 0 && size.height > 0)
            },
        ) {
            state.entries.forEach { entry ->
                key(entry) { entry.content(this) }
            }
        }
    }
}
