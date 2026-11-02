package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.ui.app.displayName
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Immutable
internal data class SessionDialogRow(
    val id: String,
    val environment: TerminalEnvironment,
    /** 块式终端传 cwd；控制台传空串。 */
    val subtitle: String,
    val active: Boolean,
    val running: Boolean,
    val alive: Boolean,
)

/** 终端会话列表面板；块式终端与控制台共用。点按行切换会话，行内提供重启与关闭。 */
@Composable
internal fun SessionListDialog(
    rows: List<SessionDialogRow>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onRestart: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.terminal_sessions),
        onDismissRequest = onDismiss,
    ) {
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.terminal_session_empty),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = rows, key = { it.id }) { row ->
                    SessionRow(
                        row = row,
                        onSelect = { onSelect(row.id); onDismiss() },
                        onRestart = { onRestart(row.id) },
                        onClose = { onClose(row.id) },
                    )
                }
            }
        }
        TextButton(
            text = stringResource(R.string.terminal_new_session),
            onClick = { onNew(); onDismiss() },
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
    }
}

@Composable
private fun SessionRow(
    row: SessionDialogRow,
    onSelect: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .squircleBackground(
                if (row.active) colors.primary.copy(alpha = 0.08f) else colors.surfaceContainer,
                14.dp,
            )
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = when {
                !row.alive -> colors.error
                row.running -> StatusSuccess
                else -> colors.onSurfaceVariantSummary.copy(alpha = 0.5f)
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.environment.displayName,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = if (row.active) colors.primary else colors.onSurface,
                )
                Text(
                    text = if (row.active) {
                        stringResource(R.string.terminal_session_current) + " · " + sessionStateLabel(row)
                    } else {
                        sessionStateLabel(row)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (row.subtitle.isNotEmpty()) {
                Text(
                    text = row.subtitle,
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        RowIconAction(
            icon = Icons.Rounded.RestartAlt,
            contentDescription = stringResource(R.string.terminal_restart_session),
            onClick = onRestart,
        )
        RowIconAction(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.terminal_close_session),
            onClick = onClose,
        )
    }
}

@Composable
private fun sessionStateLabel(row: SessionDialogRow): String = stringResource(
    when {
        !row.alive -> R.string.terminal_session_exited
        row.running -> R.string.terminal_daemon_running
        else -> R.string.terminal_session_idle
    },
)
