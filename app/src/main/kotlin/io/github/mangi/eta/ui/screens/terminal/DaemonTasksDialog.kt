package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.DaemonTaskUi
import io.github.mangi.eta.ui.app.displayName
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.StatusSuccess
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 守护任务列表面板；终端块视图与控制台视图共用。 */
@Composable
internal fun DaemonTasksDialog(
    tasks: List<DaemonTaskUi>,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.terminal_daemon_tasks),
        onDismissRequest = onDismiss,
    ) {
        if (tasks.isEmpty()) {
            Text(
                text = stringResource(R.string.terminal_daemon_empty),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = tasks, key = { it.id }) { task ->
                    DaemonTaskRow(task = task, onStop = onStop, onLoadLogs = onLoadLogs)
                }
            }
        }
    }
}

@Composable
private fun DaemonTaskRow(
    task: DaemonTaskUi,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    var logsExpanded by remember(task.id) { mutableStateOf(false) }
    var logs by remember(task.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .squircleBackground(colors.surfaceContainer, 14.dp)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = daemonStatusColor(task))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = task.description.ifBlank { task.command },
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = daemonMeta(task),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            RowIconAction(
                icon = Icons.AutoMirrored.Rounded.Article,
                contentDescription = stringResource(
                    if (logsExpanded) R.string.terminal_daemon_hide_logs else R.string.terminal_daemon_view_logs,
                ),
                tint = if (logsExpanded) colors.primary else colors.onSurfaceVariantActions,
                onClick = {
                    logsExpanded = !logsExpanded
                    if (logsExpanded && logs == null) {
                        scope.launch { logs = onLoadLogs(task.id) }
                    }
                },
            )
            if (task.running) {
                RowIconAction(
                    icon = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.terminal_stop),
                    tint = colors.error,
                    onClick = { onStop(task.id) },
                )
            }
        }
        if (logsExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, end = 10.dp)
                    .heightIn(max = 180.dp)
                    .squircleBackground(colors.surface, 10.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (logs == null) {
                    InfiniteProgressIndicator(size = 14.dp)
                } else {
                    Text(
                        text = logs.orEmpty(),
                        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                        color = colors.onSurface.copy(alpha = 0.86f),
                    )
                }
            }
        }
    }
}

@Composable
private fun daemonStatusColor(task: DaemonTaskUi) = when {
    task.running -> StatusSuccess
    task.status == "failed" || task.status == "timed_out" -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
}

@Composable
private fun daemonMeta(task: DaemonTaskUi): String {
    val environmentLabel = task.environment.displayName
    val stateLabel = when (task.status) {
        "completed" -> "已完成"
        "failed" -> "执行失败"
        "timed_out" -> "已超时"
        "stopped" -> "已停止"
        "interrupted" -> "已中断"
        else -> stringResource(if (task.running) R.string.terminal_daemon_running else R.string.terminal_daemon_exited)
    }
    return "$environmentLabel · ${task.identity} · $stateLabel"
}
