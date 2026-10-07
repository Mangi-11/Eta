package io.github.mangi.eta.agent.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun VirtualScreenAppConflictDialog() {
    val requests by MainScreenFallbackApproval.state.collectAsState()
    val request = requests.firstOrNull() ?: return
    fun decide(decision: MainScreenFallbackDecision) = MainScreenFallbackApproval.resolve(request.token, decision)
    EtaWindowDialog(show = true, title = stringResource(R.string.virtual_screen_conflict_title),
        onDismissRequest = { decide(MainScreenFallbackDecision.TASK_CANCELLED) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(request.reason, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
            listOf(
                R.string.virtual_screen_conflict_restart to MainScreenFallbackDecision.RESTART_VIRTUAL,
                R.string.virtual_screen_conflict_primary to MainScreenFallbackDecision.ALLOWED,
                R.string.virtual_screen_conflict_cancel to MainScreenFallbackDecision.TASK_CANCELLED,
            ).forEach { (label, decision) ->
                EtaTextButton(text = stringResource(label), modifier = Modifier.fillMaxWidth(),
                    onClick = { decide(decision) })
            }
        }
    }
}
