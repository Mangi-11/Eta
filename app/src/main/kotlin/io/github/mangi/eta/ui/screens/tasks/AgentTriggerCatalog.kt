package io.github.mangi.eta.ui.screens.tasks

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.automation.AgentTaskRules
import io.github.mangi.eta.agent.automation.AgentTaskScheduler
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaWindowDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class TaskTriggerGroup(val label: Int) {
    TIME(R.string.automation_triggers_time), DEVICE(R.string.automation_triggers_device),
    CONNECTION(R.string.automation_triggers_connection), APPS(R.string.automation_triggers_apps),
    ETA(R.string.automation_triggers_eta),
}

internal data class TaskTriggerUi(val type: String, val available: Boolean) {
    val group: TaskTriggerGroup get() = when (type) {
        in AgentTaskRules.timeTypes -> TaskTriggerGroup.TIME
        "network_connected", "network_disconnected", "bluetooth_connected", "bluetooth_disconnected", "headset_connected", "headset_disconnected" -> TaskTriggerGroup.CONNECTION
        "notification_posted", "notification_removed", "app_foreground" -> TaskTriggerGroup.APPS
        "memory_updated", "task_completed", "task_failed" -> TaskTriggerGroup.ETA
        else -> TaskTriggerGroup.DEVICE
    }
}

internal fun loadTaskTriggers(context: Context): List<TaskTriggerUi> {
    val array = AgentTaskScheduler.capabilities(context).getJSONArray("triggers")
    val supported = AgentTaskRules.timeTypes + AgentTaskRules.eventTypes
    return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optString("type") in supported }
        .map { TaskTriggerUi(it.getString("type"), it.optBoolean("available")) }
}

@Composable
internal fun TriggerCatalogHeading(triggers: List<TaskTriggerUi>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.automation_triggers_title), style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackground)
        Text(stringResource(R.string.automation_triggers_count, triggers.count { it.available }, triggers.size),
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun TriggerCatalogGroup(group: TaskTriggerGroup, triggers: List<TaskTriggerUi>, onClick: (TaskTriggerUi) -> Unit) {
    val context = LocalContext.current
    EtaPreferenceGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(group.label), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                triggers.forEach { trigger ->
                    val title = triggerTitle(context, trigger.type)
                    val color = if (trigger.available) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.08f))
                        .clickable(onClickLabel = title, onClick = { onClick(trigger) }).padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (trigger.available) Icons.Rounded.CheckCircle else Icons.Rounded.Lock, modifier = Modifier.size(14.dp),
                            tint = color, contentDescription = stringResource(if (trigger.available) R.string.automation_trigger_available else R.string.automation_trigger_permission))
                        Text(title, style = MiuixTheme.textStyles.footnote1, color = color)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TriggerDetails(trigger: TaskTriggerUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val description = when (trigger.type) {
        in AgentTaskRules.timeTypes -> R.string.automation_trigger_scope_time
        "notification_posted", "notification_removed" -> R.string.automation_trigger_scope_notification
        "app_foreground" -> R.string.automation_trigger_scope_accessibility
        "bluetooth_connected", "bluetooth_disconnected" -> R.string.automation_trigger_scope_bluetooth
        "task_completed", "task_failed" -> R.string.automation_trigger_scope_task
        "memory_updated" -> R.string.automation_trigger_scope_memory
        "device_boot" -> R.string.automation_trigger_scope_boot
        else -> R.string.automation_trigger_scope_monitor
    }
    EtaWindowDialog(show = true, title = triggerTitle(context, trigger.type), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (trigger.available) R.string.automation_trigger_available else R.string.automation_trigger_permission),
                color = if (trigger.available) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(stringResource(description), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}

private fun triggerTitle(context: Context, type: String): String = when (type) {
    "once" -> context.getString(R.string.automation_trigger_once)
    "daily" -> context.getString(R.string.automation_trigger_daily)
    "interval" -> context.getString(R.string.automation_trigger_interval)
    else -> eventLabel(context, type)
}
