package fuck.andes.ui.screens.rundetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.RunTimeline
import fuck.andes.ui.components.SectionHeader
import fuck.andes.ui.model.AgentRunDetailAction
import fuck.andes.ui.model.AgentRunDetailUiState
import fuck.andes.ui.model.RunStatusUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AgentRunDetailScreen(
    state: AgentRunDetailUiState,
    onAction: (AgentRunDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "header") {
            RunDetailHeader(
                state = state,
                onRetry = { onAction(AgentRunDetailAction.RetryRun(state.runId)) },
            )
        }
        item(key = "timeline_header") {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(
                text = "执行时间线",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "timeline") {
            RunTimeline(items = state.timeline)
        }
    }
}

@Composable
private fun RunDetailHeader(
    state: AgentRunDetailUiState,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = state.title,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.startedAt}${state.finishedAt?.let { " - $it" }.orEmpty()} · ${state.durationLabel ?: "--"}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            val statusText = when (state.status) {
                RunStatusUi.Running -> "运行中"
                RunStatusUi.Success -> "已完成"
                RunStatusUi.Failed -> "失败"
                RunStatusUi.Cancelled -> "已取消"
            }
            Text(
                text = "状态：$statusText",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
            )
            if (state.status == RunStatusUi.Failed || state.status == RunStatusUi.Cancelled) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    text = "重试",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
