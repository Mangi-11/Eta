package io.github.mangi.eta.ui.screens.tasks

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.automation.AgentTaskScheduler
import io.github.mangi.eta.agent.automation.AgentTaskTools
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    MiuixScaffoldPage(
        title = stringResource(R.string.automation_title),
        onBack = onBack,
    ) {
        item {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.automation_create_hint),
                    summary = stringResource(R.string.automation_create_summary),
                )
            }
        }
        notice?.let { message ->
            item {
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 16.dp),
                )
            }
        }
        items(tasks, key = { it.id }) { task ->
            val rule = remember(task.triggerJson) { JSONObject(task.triggerJson) }
            val recentRuns = runs.filter { it.taskId == task.id }.take(3)
            val colors = MiuixTheme.colorScheme
            val statusColor = when (task.lastStatus) {
                "completed" -> EtaPreferenceColors.Green
                "failed", "interrupted" -> colors.error
                "queued", "running" -> colors.primary
                "configuration_required" -> EtaPreferenceColors.Orange
                else -> colors.onSurfaceVariantSummary
            }
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = task.name,
                    summary = task.prompt,
                    checked = task.enabled,
                    onCheckedChange = {
                        execute(
                            "tasks_update",
                            JSONObject().put("taskId", task.id)
                                .put("expectedUpdatedAt", task.updatedAt).put("enabled", it),
                        )
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusLabel(context, task.lastStatus),
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium,
                            color = statusColor,
                            modifier = Modifier.weight(1f, fill = false)
                                .background(statusColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Text(
                            text = stringResource(R.string.automation_run_count, task.runCount, task.maxRuns),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(colors.onBackground.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TaskDetail(stringResource(R.string.automation_trigger_label), triggerSummary(context, rule))
                        triggerFilters(context, rule).takeIf { it.isNotBlank() }?.let {
                            TaskDetail(stringResource(R.string.automation_filters_label), it)
                        }
                        triggerConditions(context, rule).takeIf { it.isNotBlank() }?.let {
                            TaskDetail(stringResource(R.string.automation_conditions_label), it)
                        }
                        task.nextRunAt?.let {
                            TaskDetail(stringResource(R.string.automation_next_run_label), formatTime(context, it))
                        }
                    }
                    if (recentRuns.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = stringResource(R.string.automation_recent_runs_label),
                                style = MiuixTheme.textStyles.footnote1,
                                color = colors.onSurfaceVariantSummary,
                            )
                            recentRuns.forEach { run ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = statusLabel(context, run.status) + " · " +
                                            formatTime(context, run.startedAt ?: run.queuedAt),
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = colors.onSurfaceVariantSummary,
                                    )
                                    if (run.resultPreview.isNotBlank()) {
                                        Text(
                                            text = run.resultPreview.take(300),
                                            style = MiuixTheme.textStyles.body2,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TaskActions(
                        onRun = { execute("tasks_run", JSONObject().put("taskId", task.id)) },
                        onCancel = { execute("tasks_cancel", JSONObject().put("taskId", task.id)) },
                        onDelete = { execute("tasks_delete", JSONObject().put("taskId", task.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(text = value, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun TaskActions(onRun: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EtaTextButton(
            text = stringResource(R.string.automation_run),
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 48.dp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            textStyle = MiuixTheme.textStyles.body2,
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EtaTextButton(
                text = stringResource(R.string.automation_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                minWidth = 0.dp,
                minHeight = 48.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                textStyle = MiuixTheme.textStyles.body2,
            )
            EtaTextButton(
                text = stringResource(R.string.automation_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                minWidth = 0.dp,
                minHeight = 48.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error.copy(alpha = 0.1f),
                    textColor = MiuixTheme.colorScheme.error,
                ),
                textStyle = MiuixTheme.textStyles.body2,
            )
        }
    }
}

private fun triggerSummary(context: Context, rule: JSONObject): String =
    when (rule.optString("type")) {
        "once" -> runCatching {
            formatTime(context, OffsetDateTime.parse(rule.optString("at")).toInstant().toEpochMilli())
        }.getOrDefault(rule.optString("at"))
        "daily" -> {
            val weekdays = rule.optJSONArray("weekdays")
            val days = if (weekdays == null) emptyList() else {
                (0 until weekdays.length()).mapNotNull { index ->
                    weekdays.optInt(index).takeIf { it in 1..7 }?.let {
                        DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, context.resources.configuration.locales[0])
                    }
                }
            }
            context.getString(R.string.automation_daily, rule.optString("time"), rule.optString("timeZone")) +
                if (days.isEmpty()) "" else " · ${days.joinToString(", ")}"
        }
        "interval" -> context.getString(R.string.automation_interval, rule.optLong("seconds") / 60)
        else -> eventLabel(context, rule.optString("type"))
    }

private fun eventLabel(context: Context, type: String): String {
    val label = when (type) {
        "notification_posted" -> R.string.automation_event_notification_posted
        "notification_removed" -> R.string.automation_event_notification_removed
        "app_foreground" -> R.string.automation_event_app_foreground
        "device_unlocked" -> R.string.automation_event_device_unlocked
        "screen_on" -> R.string.automation_event_screen_on
        "screen_off" -> R.string.automation_event_screen_off
        "charging_connected" -> R.string.automation_event_charging_connected
        "charging_disconnected" -> R.string.automation_event_charging_disconnected
        "battery_low" -> R.string.automation_event_battery_low
        "battery_okay" -> R.string.automation_event_battery_okay
        "network_connected" -> R.string.automation_event_network_connected
        "network_disconnected" -> R.string.automation_event_network_disconnected
        "bluetooth_connected" -> R.string.automation_event_bluetooth_connected
        "bluetooth_disconnected" -> R.string.automation_event_bluetooth_disconnected
        "headset_connected" -> R.string.automation_event_headset_connected
        "headset_disconnected" -> R.string.automation_event_headset_disconnected
        "device_boot" -> R.string.automation_event_device_boot
        "memory_updated" -> R.string.automation_event_memory_updated
        "task_completed" -> R.string.automation_event_task_completed
        "task_failed" -> R.string.automation_event_task_failed
        else -> return context.getString(R.string.automation_event, type)
    }
    return context.getString(label)
}

private fun triggerFilters(context: Context, rule: JSONObject): String {
    val filters = rule.optJSONObject("filters") ?: return ""
    return listOf(
        "packageName" to R.string.automation_filter_app,
        "titleContains" to R.string.automation_filter_title,
        "textContains" to R.string.automation_filter_text,
        "taskId" to R.string.automation_filter_task,
        "transport" to R.string.automation_filter_transport,
    ).mapNotNull { (key, label) ->
        filters.optString(key).takeIf { it.isNotBlank() }?.let { context.getString(label, it) }
    }.joinToString("\n")
}

private fun triggerConditions(context: Context, rule: JSONObject): String {
    val conditions = rule.optJSONObject("conditions") ?: return ""
    return listOf(
        Triple("charging", R.string.automation_condition_charging, R.string.automation_condition_not_charging),
        Triple("unlocked", R.string.automation_condition_unlocked, R.string.automation_condition_locked),
        Triple("networkConnected", R.string.automation_condition_online, R.string.automation_condition_offline),
    ).mapNotNull { (key, yes, no) ->
        if (conditions.has(key)) context.getString(if (conditions.optBoolean(key)) yes else no) else null
    }.joinToString(" · ")
}

private fun formatTime(context: Context, time: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(context.resources.configuration.locales[0])
        .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

private fun statusLabel(context: Context, status: String): String =
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
