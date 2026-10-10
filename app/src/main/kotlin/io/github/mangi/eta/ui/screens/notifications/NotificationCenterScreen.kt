package io.github.mangi.eta.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.LearningProposalEntity
import io.github.mangi.eta.ui.app.NotificationCenterStore
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NotificationCenterScreen(store: NotificationCenterStore, onDetail: (String) -> Unit, onBack: () -> Unit) {
    var pendingOnly by rememberSaveable { mutableStateOf(false) }
    val visible = store.proposals.filter { !pendingOnly || it.status == "pending" }
    MiuixScaffoldPage(title = stringResource(R.string.inbox_title), onBack = onBack) {
        item(key = "intro") { InboxText(stringResource(R.string.inbox_description)) }
        store.notice?.let { item(key = "notice") { InboxText(it) } }
        item(key = "filters") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton(stringResource(R.string.inbox_all), { pendingOnly = false },
                    colors = if (!pendingOnly) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                EtaTextButton(stringResource(R.string.inbox_pending_count, store.pendingCount), { pendingOnly = true },
                    colors = if (pendingOnly) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
            }
        }
        if (store.loaded && visible.isEmpty()) item(key = "empty") {
            ListEmptyState(title = stringResource(R.string.inbox_empty), summary = stringResource(R.string.inbox_empty_summary))
        }
        items(visible, key = { it.id }) { proposal ->
            EtaCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                insideMargin = PaddingValues(16.dp), onClick = { onDetail(proposal.id) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(proposal.kindLabel() + " · " + proposal.statusLabel(), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(proposal.title.ifBlank { proposal.actionLabel() }, style = MiuixTheme.textStyles.body1,
                        fontWeight = if (proposal.read) FontWeight.Normal else FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(proposal.detailMarkdown.ifBlank { proposal.actionLabel() }, style = MiuixTheme.textStyles.body2,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun LearningProposalDetailScreen(id: String, store: NotificationCenterStore,
    onRefine: (String) -> Unit, onBack: () -> Unit) {
    var cached by remember(id) { mutableStateOf<LearningProposalEntity?>(null) }
    val proposal = store.proposals.firstOrNull { it.id == id } ?: cached
    LaunchedEffect(id) { store.markRead(id) }
    LaunchedEffect(id, store.proposals) { cached = store.detail(id) }
    MiuixScaffoldPage(title = stringResource(R.string.inbox_detail), onBack = onBack) {
        store.notice?.let { item(key = "notice") { InboxText(it) } }
        if (proposal == null) {
            item { InboxText(stringResource(R.string.inbox_not_found)) }
        } else {
            item(key = "state") {
                InboxText(proposal.kindLabel() + " · " + proposal.statusLabel() + "\n" + proposal.actionLabel())
            }
            item(key = "help") { InboxText(proposal.statusHelp()) }
            if (proposal.kind == "skill") item(key = "skill-description") {
                InboxText(JSONObject(proposal.argumentsJson).optString("description"))
            }
            item(key = "content") {
                EtaCard(Modifier.fillMaxWidth().padding(16.dp), insideMargin = PaddingValues(16.dp)) {
                    SelectionContainer { StaticMarkdown(proposal.detailMarkdown.ifBlank { proposal.actionLabel() }, MarkdownTone.Answer) }
                }
            }
            item(key = "actions") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (proposal.status == "pending") EtaTextButton(stringResource(R.string.inbox_approve),
                        { store.approve(id) }, enabled = !store.busy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (proposal.status in setOf("pending", "failed", "stale", "uncertain")) {
                            EtaTextButton(stringResource(R.string.inbox_reject), { store.reject(id) },
                                enabled = !store.busy, modifier = Modifier.weight(1f))
                        }
                        EtaTextButton(stringResource(R.string.inbox_refine), { store.refine(id, onRefine) },
                            enabled = !store.busy && proposal.status !in setOf("applying", "refining"), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxText(text: String) = Text(text, style = MiuixTheme.textStyles.body2,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun LearningProposalEntity.kindLabel() = stringResource(if (kind == "memory") R.string.inbox_memory else R.string.inbox_skill)

@Composable
private fun LearningProposalEntity.actionLabel(): String {
    val args = JSONObject(argumentsJson)
    return stringResource(when {
        kind == "skill" && args.optString("action") == "create" -> R.string.inbox_create_skill
        kind == "skill" -> R.string.inbox_update_skill
        args.optString("mode") == "clear" -> R.string.inbox_clear_memory
        args.optString("mode") == "replace_range" -> R.string.inbox_replace_memory
        else -> R.string.inbox_append_memory
    }) + if (kind == "memory" && args.optString("mode") == "replace_range")
        " (${args.optInt("start_line")}–${args.optInt("end_line")})" else ""
}

@Composable
private fun LearningProposalEntity.statusLabel() = stringResource(when (status) {
    "pending" -> R.string.inbox_pending; "applying" -> R.string.inbox_applying
    "approved" -> R.string.inbox_approved; "rejected" -> R.string.inbox_rejected
    "refining" -> R.string.inbox_refining; "stale" -> R.string.inbox_stale
    "uncertain" -> R.string.inbox_uncertain; else -> R.string.inbox_failed
})

@Composable
private fun LearningProposalEntity.statusHelp() = stringResource(when {
    status == "pending" -> R.string.inbox_pending_help
    status == "approved" -> R.string.inbox_approved_help
    status == "stale" -> R.string.inbox_stale_help
    status == "uncertain" -> R.string.inbox_uncertain_help
    status == "refining" -> R.string.inbox_refining_help
    errorCode == "LEARNING_PERMISSION_REVOKED" -> R.string.inbox_permission_help
    status == "failed" -> R.string.inbox_failed_help
    else -> R.string.inbox_description
})
