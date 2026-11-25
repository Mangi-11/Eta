package io.github.mangi.eta.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.components.color

@Composable
internal fun AgentConversationLoadState(
    isLoading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isLoading || !failed) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.conversation_loading),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(text = stringResource(R.string.conversation_load_failed))
                TextButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
internal fun AgentHistoryLoadStatus(
    state: ConversationHistoryLoadState,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            ConversationHistoryLoadState.Loading -> CircularProgressIndicator(size = 20.dp)
            ConversationHistoryLoadState.Failed -> TextButton(
                text = stringResource(R.string.conversation_retry_earlier_messages),
                enabled = enabled,
                onClick = onRetry,
            )
            ConversationHistoryLoadState.Idle -> Unit
        }
    }
}
