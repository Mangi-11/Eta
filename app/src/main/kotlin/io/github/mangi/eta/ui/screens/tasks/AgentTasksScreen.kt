package io.github.mangi.eta.ui.screens.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.automation.AgentTaskScheduler
import io.github.mangi.eta.agent.automation.AgentTaskTools
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AgentTasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val dao = remember { EtaDatabase.get(context).agentTaskDao() }
    val tasks by dao.observeTasks().collectAsState(initial = emptyList())
    val runs by dao.observeRuns().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    fun execute(tool: String, args: JSONObject) {
        scope.launch(Dispatchers.IO) {
            val result = JSONObject(AgentTaskTools(context).execute(tool, args))
            withContext(Dispatchers.Main) {
                notice = if (result.optBoolean("ok")) null else result.optString("message")
            }
        }
    }
    LaunchedEffect(Unit) { scope.launch(Dispatchers.IO) { AgentTaskScheduler.refresh(context) } }
    MiuixScaffold(
        title = stringResource(R.string.automation_title),
        onBack = onBack
    ) { padding, _, sidePadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 12.dp)
        ) {
            item {
                EtaPreference(
                    title = stringResource(R.string.automation_create_hint),
                    summary = stringResource(R.string.automation_create_summary)
                )
                notice?.let { Text(it) }
            }
            items(tasks, key = { it.id }) { task ->
                EtaCard(Modifier.padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        EtaSwitchPreference(
                            title = task.name, summary = task.prompt, checked = task.enabled,
                            onCheckedChange = {
                                execute(
                                    "tasks_update",
                                    JSONObject().put("taskId", task.id)
                                        .put("expectedUpdatedAt", task.updatedAt).put("enabled", it)
                                )
                            })
                        Text(
                            triggerSummary(
                                context,
                                JSONObject(task.triggerJson)
                            ) + " · ${task.runCount}/${task.maxRuns} · " + statusLabel(
                                context,
                                task.lastStatus
                            )
                        )
                        task.nextRunAt?.let {
                            Text(
                                java.time.Instant.ofEpochMilli(it)
                                    .atZone(java.time.ZoneId.systemDefault()).toString()
                            )
                        }
                        runs.filter { it.taskId == task.id }.take(3).forEach { run ->
                            Text(
                                statusLabel(
                                    context,
                                    run.status
                                ) + if (run.resultPreview.isNotBlank()) " · ${
                                    run.resultPreview.take(
                                        300
                                    )
                                }" else ""
                            )
                        }
                        EtaTextButton(
                            text = stringResource(R.string.automation_run),
                            onClick = { execute("tasks_run", JSONObject().put("taskId", task.id)) })
                        EtaTextButton(
                            text = stringResource(R.string.automation_cancel),
                            onClick = {
                                execute(
                                    "tasks_cancel",
                                    JSONObject().put("taskId", task.id)
                                )
                            })
                        EtaTextButton(
                            text = stringResource(R.string.automation_delete),
                            onClick = {
                                execute(
                                    "tasks_delete",
                                    JSONObject().put("taskId", task.id)
                                )
                            })
                    }
                }
            }
        }
    }
}

private fun triggerSummary(context: android.content.Context, rule: JSONObject): String =
    when (rule.optString("type")) {
        "once" -> rule.optString("at")
        "daily" -> context.getString(
            R.string.automation_daily,
            rule.optString("time"),
            rule.optString("timeZone")
        ) + (rule.optJSONArray("weekdays")?.let { " · $it" } ?: "")

        "interval" -> context.getString(R.string.automation_interval, rule.optLong("seconds") / 60)
        else -> context.getString(
            R.string.automation_event,
            rule.optString("type")
        ) + (rule.optJSONObject("filters")?.let { " · $it" } ?: "")
    }

private fun statusLabel(context: android.content.Context, status: String): String =
    context.getString(
        when (status) {
            "ready" -> R.string.automation_ready
            "queued" -> R.string.automation_queued
            "running" -> R.string.automation_running
            "completed" -> R.string.automation_completed
            "failed" -> R.string.automation_failed
            "cancelled" -> R.string.automation_cancelled
            "interrupted" -> R.string.automation_interrupted
            "configuration_required" -> R.string.automation_config_required
            else -> R.string.automation_ready
        }
    )
