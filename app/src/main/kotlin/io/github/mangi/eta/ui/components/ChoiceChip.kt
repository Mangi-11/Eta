package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可选中的胶囊标签：用于筛选与少量互斥/多选项。
 * 采用无硬描边的柔和半透胶囊（未选中轻灰底、选中主题色浅底 + 强调色文字），
 * 避免在玻璃弹层上出现空心线框或高饱和不透明色块的割裂感。
 */
@Composable
internal fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val container by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f)
        } else {
            MiuixTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.09f else 0.059f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "choice_chip_container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurfaceSecondary
        },
        animationSpec = tween(durationMillis = 160),
        label = "choice_chip_content",
    )
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = content,
        maxLines = 1,
        modifier = modifier
            .semantics { this.selected = selected }
            .squircleSurface(color = container, cornerRadius = 16.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
