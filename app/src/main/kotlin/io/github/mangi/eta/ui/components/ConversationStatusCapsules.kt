package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import io.github.mangi.eta.ui.model.latestContextUsage
import io.github.mangi.eta.ui.model.TokenUsageUi
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationStatusCapsules(messages: List<AgentChatMessageUi>, model: AgentModelOptionUi?, lastUsage: TokenUsageUi?) {
    val usage = latestContextUsage(messages, model).let { it.copy(contextTokens = lastUsage?.contextTokens ?: it.contextTokens) }
    val count = usage.contextTokens?.let { formatCompactTokenCount(it) } ?: "—"
    val limit = usage.contextWindow?.takeIf { it > 0 }?.let { formatCompactTokenCount(it) }
    val percent = usage.contextWindow?.takeIf { it > 0 }?.let { window ->
        usage.contextTokens?.takeIf { it >= 0 }?.let { tokens ->
            NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(tokens.toDouble() / window)
        }
    }
    val contextText = count + (limit?.let { "/$it" } ?: "") + (percent?.let { " · $it" } ?: "")
    val contextDescription = stringResource(R.string.conversation_context_capsule) + " " + contextText
    val speed = (lastUsage ?: messages.asReversed().filterIsInstance<AgentMessageUi>()
        .firstOrNull { it.usage != null }?.usage)?.averageTokensPerSecond
    val speedText = speed?.let {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it)
    } ?: "—"
    Row(Modifier.widthIn(max = 232.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusCapsule(contextText, Modifier.weight(1f, fill = false), contextDescription)
        StatusCapsule("$speedText tok/s", Modifier, stringResource(R.string.conversation_speed_description, speedText))
    }
}

@Composable
private fun StatusCapsule(text: String, modifier: Modifier, description: String = text) {
    Text(text,
        modifier = modifier.background(MiuixTheme.colorScheme.surfaceContainer, CircleShape)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 5.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
