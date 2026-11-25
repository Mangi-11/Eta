package io.github.mangi.eta.ui.screens.scheduled

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.scheduler.ScheduledTaskHealthChecker
import io.github.mangi.eta.agent.scheduler.ScheduledTaskHealthSnapshot
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskPresets
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScheduledTasksScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val tasks by remember(context) { ScheduledTaskRepository.observeTasks(context) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val activeTaskIds by ScheduledTaskRunner.activeTaskIds.collectAsStateWithLifecycle()
    var health by remember(context) { mutableStateOf(ScheduledTaskHealthChecker.inspect(context)) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = ScheduledTaskHealthChecker.inspect(context)
                nowMs = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onToggleEnabled: (ScheduledTask, Boolean) -> Unit = { task, enabled ->
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ScheduledTaskRepository.setEnabled(task.id, enabled, context = context)
                }
            }.onFailure {
                Toast.makeText(
                    context,
                    resources.getString(R.string.scheduled_toast_toggle_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val onFixHealth: () -> Unit = {
        openPrimaryHealthFix(context, health)
    }

    val title = stringResource(R.string.route_scheduled_tasks)
    val createLabel = stringResource(R.string.route_scheduled_task_new)

    ScaffoldPage(
        title = title,
        onBack = onBack,
        actions = {
            TopBarActionButton(
                icon = MiuixIcons.Glass.Add,
                contentDescription = createLabel,
                onClick = { onNavigate(AppRoute.ScheduledTaskEditor()) },
            )
        },
    ) {
        if (!health.allHealthy) {
            item(key = "health_banner") {
                HealthBannerCard(
                    health = health,
                    onFix = onFixHealth,
                )
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty_intro") {
                SmallTitle(text = stringResource(R.string.scheduled_section_quick_start))
            }
            item(key = "presets_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ScheduledTaskPresets.all.forEach { preset ->
                        ArrowPreference(
                            title = stringResource(preset.titleRes),
                            summary = "${ScheduledTaskScheduleCalculator.describeSchedule(preset.schedule)} · ${stringResource(preset.subtitleRes)}",
                            onClick = { onNavigate(AppRoute.ScheduledTaskEditor(presetId = preset.id)) },
                        )
                    }
                }
            }
            item(key = "empty_hint") {
                Text(
                    text = stringResource(R.string.scheduled_empty_hint),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                )
            }
        } else {
            item(key = "tasks_header") {
                val enabledCount = tasks.count { it.enabled }
                SmallTitle(text = stringResource(R.string.scheduled_section_all_tasks, enabledCount, tasks.size))
            }
            itemsIndexed(
                items = tasks,
                key = { _, item -> item.id },
            ) { _, task ->
                ScheduledTaskCard(
                    task = task,
                    isRunning = task.id in activeTaskIds,
                    nowMs = nowMs,
                    onClick = { onNavigate(AppRoute.ScheduledTaskDetail(task.id)) },
                    onToggle = { checked -> onToggleEnabled(task, checked) },
                )
            }
        }
    }
}

@Composable
private fun HealthBannerCard(
    health: ScheduledTaskHealthSnapshot,
    onFix: () -> Unit,
) {
    val (title, summary, actionText) = healthGuidance(health)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = actionText,
                onClick = onFix,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTask,
    isRunning: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val scheduleLine = buildScheduleMetaLine(task, nowMs)
    val statusLine = buildStatusMetaLine(task, isRunning, nowMs)
    val dotColor = ScheduledTaskUiFormatting.statusDotColor(
        status = task.lastStatus,
        runningNow = isRunning,
        autoDisabled = !task.autoDisabledReason.isNullOrBlank(),
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        BasicComponent(
            onClick = onClick,
            title = task.name.ifBlank { stringResource(R.string.scheduled_unnamed_task) },
            summary = scheduleLine,
            bottomAction = {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        text = statusLine,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            endActions = {
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggle,
                )
            },
        )
    }
}

@Composable
private fun buildScheduleMetaLine(task: ScheduledTask, nowMs: Long): String {
    val scheduleText = ScheduledTaskScheduleCalculator.describeSchedule(task.schedule)
    val deliveryText = ScheduledTaskUiFormatting.deliveryBadge(task.deliveryMode)
    val nextText = if (task.enabled && task.nextRunAt != null) {
        " · " + stringResource(
            R.string.scheduled_meta_next_run,
            ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs),
        )
    } else ""
    return "$scheduleText · $deliveryText$nextText"
}

@Composable
private fun buildStatusMetaLine(task: ScheduledTask, isRunning: Boolean, nowMs: Long): String {
    if (isRunning) return stringResource(R.string.scheduled_status_running_background)
    if (!task.autoDisabledReason.isNullOrBlank()) return task.autoDisabledReason
    if (task.lastStatus == null) {
        return stringResource(
            if (task.enabled) R.string.scheduled_status_awaiting_first_run else R.string.scheduled_status_paused,
        )
    }
    val statusText = ScheduledTaskUiFormatting.statusLabel(task.lastStatus, runningNow = false)
    val timeText = ScheduledTaskUiFormatting.formatTimestamp(task.lastRunAt, nowMs)
    val summarySnippet = task.lastSummary.trim().take(36)
    return buildString {
        if (timeText.isNotEmpty()) append(timeText).append(" · ")
        append(statusText)
        if (summarySnippet.isNotEmpty()) append(" · ").append(summarySnippet)
    }
}

@Composable
private fun healthGuidance(health: ScheduledTaskHealthSnapshot): Triple<String, String, String> = when {
    !health.ignoringBatteryOptimizations -> Triple(
        stringResource(R.string.scheduled_health_battery_title),
        stringResource(R.string.scheduled_health_battery_summary),
        stringResource(R.string.scheduled_health_battery_action),
    )
    !health.canScheduleExactAlarms -> Triple(
        stringResource(R.string.scheduled_health_exact_alarm_title),
        stringResource(R.string.scheduled_health_exact_alarm_summary),
        stringResource(R.string.scheduled_health_exact_alarm_action),
    )
    else -> Triple(
        stringResource(R.string.scheduled_health_notification_title),
        stringResource(R.string.scheduled_health_notification_summary),
        stringResource(R.string.scheduled_health_notification_action),
    )
}

private fun openPrimaryHealthFix(context: Context, health: ScheduledTaskHealthSnapshot) {
    val intent = when {
        !health.ignoringBatteryOptimizations -> ScheduledTaskHealthChecker.batteryOptimizationIntent(context)
        !health.canScheduleExactAlarms -> ScheduledTaskHealthChecker.exactAlarmSettingsIntent(context)
        else -> ScheduledTaskHealthChecker.notificationSettingsIntent(context)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            val fallback = if (!health.ignoringBatteryOptimizations) {
                ScheduledTaskHealthChecker.batterySettingsFallbackIntent(context)
            } else {
                ScheduledTaskHealthChecker.appDetailsIntent(context)
            }
            context.startActivity(fallback)
        }
    }
}
