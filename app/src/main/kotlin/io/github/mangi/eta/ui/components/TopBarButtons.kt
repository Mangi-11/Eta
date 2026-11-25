package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页面统一 OS4 玻璃返回按钮，保持图标、语义、RTL 方向与点击区域一致。
 */
@Composable
fun TopBarBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    GlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = stringResource(R.string.action_back),
            modifier = Modifier.size(28.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 玻璃顶栏内的动作按钮：统一 28.dp Glass 图标尺寸，并在禁用态阻断点击与补齐无障碍语义。
 */
@Composable
internal fun TopBarActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    val resolvedTint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    GlassIconButton(
        onClick = { if (enabled) onClick() },
        backdrop = backdrop,
        modifier = modifier.then(if (!enabled) Modifier.semantics { disabled() } else Modifier),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp),
            tint = resolvedTint,
        )
    }
}
