package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun EtaFeatureCard(
    title: String,
    summary: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            val source = remember { MutableInteractionSource() }
            Column(
                modifier = modifier
                    .heightIn(min = 136.dp)
                    .clip(CouiPageDefaults.shape)
                    .background(colors.card)
                    .couiButtonFeedback(dark, source, enabled = true)
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .padding(16.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon()
                    Spacer(Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(8.dp))
                CouiText(
                    text = title,
                    kind = CouiTextKind.PreferenceTitle,
                    color = colors.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                CouiText(
                    text = summary,
                    kind = CouiTextKind.PreferenceSummary,
                    color = colors.summary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                footer?.invoke(this)
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Card(
                modifier = modifier.heightIn(min = 136.dp),
                insideMargin = PaddingValues(16.dp),
                pressFeedbackType = PressFeedbackType.Sink,
                showIndication = true,
                onClick = onClick,
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                footer?.invoke(this)
            }
        }
    }
}
