package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 长列表保持逐项组合，仅对分组首尾绘制 Miuix Card 标准圆角，避免将大量动态行塞入单个 Card。 */
@Composable
internal fun EtaPreferenceGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val radius = CardDefaults.CornerRadius
    val surface = if (isFirst || isLast) {
        Modifier.squircleSurface(
            color = colors.surfaceContainer,
            topStart = if (isFirst) radius else 0.dp,
            topEnd = if (isFirst) radius else 0.dp,
            bottomStart = if (isLast) radius else 0.dp,
            bottomEnd = if (isLast) radius else 0.dp,
        )
    } else {
        Modifier.background(colors.surfaceContainer)
    }
    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceContainer) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = if (isLast) 12.dp else 0.dp)
                .then(surface),
        ) {
            content()
        }
    }
}
