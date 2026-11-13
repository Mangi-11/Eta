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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可选中的胶囊标签：用于筛选与少量互斥/多选项。
 * COUI 模式使用柔和无描边胶囊（未选中浅灰微底，选中主题色浅底 + 强调色文字）；
 * Miuix 模式保留细描边与选中容器底色。
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
            val container by animateColorAsState(
                targetValue = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    Color.Transparent
                },
                animationSpec = tween(durationMillis = 160),
                label = "choice_chip_container",
            )
            val content by animateColorAsState(
                targetValue = when {
                    !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
                    selected -> MiuixTheme.colorScheme.onTertiaryContainer
                    else -> MiuixTheme.colorScheme.onSurface
                },
                animationSpec = tween(durationMillis = 160),
                label = "choice_chip_content",
            )
            val border by animateColorAsState(
                targetValue = if (selected) Color.Transparent else MiuixTheme.colorScheme.dividerLine,
                animationSpec = tween(durationMillis = 160),
                label = "choice_chip_border",
            )
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1,
                color = content,
                maxLines = 1,
                modifier = modifier
                    .semantics { this.selected = selected }
                    .squircleBorder(width = 1.dp, color = border, cornerRadius = 16.dp)
                    .squircleSurface(color = container, cornerRadius = 16.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
