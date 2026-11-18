package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可选中的胶囊标签：用于筛选与少量互斥/多选项。
 * COUI 与 MIUIX 均使用无硬描边的柔和半透胶囊（未选中轻灰底、选中主题色浅底 + 强调色文字），
 * 避免在玻璃弹层上出现空心线框或高饱和不透明色块的割裂感。
 */
@Composable
internal fun EtaChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            val source = remember { MutableInteractionSource() }
            val container by animateColorAsState(
                targetValue = when {
                    selected -> colors.accent.copy(alpha = if (dark) 0.24f else 0.14f)
                    else -> if (dark) Color(0x1AFFFFFF) else Color(0x0D000000)
                },
                animationSpec = tween(durationMillis = 160),
                label = "coui_choice_chip_container",
            )
            val content by animateColorAsState(
                targetValue = when {
                    selected && enabled -> colors.accent
                    selected -> colors.accent.copy(alpha = 0.55f)
                    enabled -> colors.title
                    else -> colors.disabled
                },
                animationSpec = tween(durationMillis = 160),
                label = "coui_choice_chip_content",
            )
            Box(
                modifier = modifier
                    .semantics { this.selected = selected }
                    .clip(CouiCapsuleShape)
                    .background(container, CouiCapsuleShape)
                    .couiButtonFeedback(dark, source, enabled)
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                CouiText(
                    text = text,
                    kind = CouiTextKind.GroupFooter,
                    color = content,
                    maxLines = 1,
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
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
    }
}
