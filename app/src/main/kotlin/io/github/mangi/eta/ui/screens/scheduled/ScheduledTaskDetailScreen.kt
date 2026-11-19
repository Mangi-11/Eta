package io.github.mangi.eta.ui.screens.scheduled

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit as OutlinedEdit
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixTopBarActionButton
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Edit
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScheduledTaskDetailScreen(
    taskId: String,
    onNavigate: (AppRoute) -> Unit,
    onOpenRun: (ScheduledTaskRun, ScheduledTask, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task by remember(context, taskId) { ScheduledTaskRepository.observeTask(taskId, context) }
        .collectAsState(initial = null)
    val runs by remember(context, taskId) { ScheduledTaskRepository.observeRuns(taskId, limit = 50, context = context) }
        .collectAsState(initial = emptyList())
    val activeTaskIds by ScheduledTaskRunner.activeTaskIds.collectAsState()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearMemoConfirm by remember { mutableStateOf(false) }

    val isRunning = taskId in activeTaskIds
    val currentTask = task
    val nowMs = System.currentTimeMillis()

    val modelDisplay = remember(providers, currentTask?.modelId) {
        val id = currentTask?.modelId?.takeIf { it.isNotBlank() }
        if (id == null) {
            "跟随全局默认模型"
        } else {
            providers.enabledModel(id)?.let { "${it.provider.name} / ${it.model.displayName}" }
                ?: "模型已失效（将回退默认）"
        }
    }

    val onRunOrStop: () -> Unit = {
        if (isRunning) {
            ScheduledTaskRunner.cancelRunningTask(context, taskId)
            Toast.makeText(context, "正在停止当前运行…", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    ScheduledTaskRunner.triggerManualRun(context, taskId)
                }
                when (res) {
                    ScheduledTaskRunner.ManualTriggerResult.Started ->
                        Toast.makeText(context, "已开始后台试跑", Toast.LENGTH_SHORT).show()
                    ScheduledTaskRunner.ManualTriggerResult.AlreadyRunning ->
                        Toast.makeText(context, "该任务正在运行中", Toast.LENGTH_SHORT).show()
                    ScheduledTaskRunner.ManualTriggerResult.NotFound ->
                        Toast.makeText(context, "任务不存在", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onToggleEnabled: (Boolean) -> Unit = { enabled ->
        scope.launch {
            withContext(Dispatchers.IO) {
                ScheduledTaskRepository.setEnabled(taskId, enabled, context = context)
            }
        }
    }

    val onConfirmDelete: () -> Unit = {
        showDeleteConfirm = false
        scope.launch {
            ScheduledTaskRunner.cancelRunningTask(context, taskId)
            withContext(Dispatchers.IO) {
                ScheduledTaskRepository.deleteTask(taskId, rescheduleAlarm = true, context = context)
            }
            onBack()
        }
    }

    val onConfirmClearMemo: () -> Unit = {
        showClearMemoConfirm = false
        scope.launch {
            withContext(Dispatchers.IO) {
                ScheduledTaskRepository.updateMemo(taskId, "", context = context)
            }
            Toast.makeText(context, "已清空任务小记事本", Toast.LENGTH_SHORT).show()
        }
    }

    val title = currentTask?.name?.ifBlank { stringResource(R.string.route_scheduled_task_detail) }
        ?: stringResource(R.string.route_scheduled_task_detail)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiLazyScaffoldPage(
                title = title,
                onBack = onBack,
                actions = {
                    if (currentTask != null) {
                        CouiTopAppBarIconButton(
                            icon = Icons.Outlined.OutlinedEdit,
                            contentDescription = "编辑定时任务",
                            onClick = { onNavigate(AppRoute.ScheduledTaskEditor(taskId = taskId)) },
                        )
                    }
                },
            ) {
                if (currentTask == null) {
                    item(key = "missing") {
                        CouiText(
                            text = "该定时任务已不存在或已被删除。",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    item(key = "hero") {
                        CouiTaskControlCard(
                            task = currentTask,
                            isRunning = isRunning,
                            nowMs = nowMs,
                            onRunOrStop = onRunOrStop,
                            onToggleEnabled = onToggleEnabled,
                        )
                    }
                    item(key = "prompt_card") {
                        Group(title = "任务指令与配置") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CouiText(
                                    text = currentTask.prompt,
                                    kind = CouiTextKind.ItemSummary,
                                    color = colors.title,
                                )
                            }
                            Divider()
                            CouiPreference(
                                title = "执行模型",
                                value = modelDisplay,
                                showChevron = false,
                            )
                            Divider()
                            CouiPreference(
                                title = "通知与会话",
                                value = "${ScheduledTaskUiFormatting.deliveryBadge(currentTask.deliveryMode)} · ${
                                    if (currentTask.showRunningNotification) "显示进度" else "静默运行"
                                } · ${
                                    if (currentTask.sessionMode.wireValue == "dedicated") "固定频道" else "独立归档"
                                }",
                                showChevron = false,
                            )
                            Divider()
                            CouiPreference(
                                title = "能力范围",
                                summary = buildCapabilitiesSummary(currentTask),
                                showChevron = false,
                            )
                        }
                    }
                    if (currentTask.memo.isNotBlank()) {
                        item(key = "memo_card") {
                            Group(title = "任务小记事本（供下次运行对比）") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CouiText(
                                        text = currentTask.memo,
                                        kind = CouiTextKind.ItemSummary,
                                        color = colors.summary,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CouiButton(
                                        text = "清空记事本",
                                        role = CouiButtonRole.Secondary,
                                        onClick = { showClearMemoConfirm = true },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "history_section") {
                        CouiSectionTitle("运行时间线")
                    }
                    if (runs.isEmpty()) {
                        item(key = "history_empty") {
                            Footer(text = "还没有运行记录，点上方「立即试跑」即可验证实际效果。")
                        }
                    } else {
                        itemsIndexed(items = runs, key = { _, run -> run.id }) { _, run ->
                            val runIsLive = isRunning && run.status == ScheduledTaskRunStatus.RUNNING
                            CouiRunTimelineItem(
                                run = run,
                                isLive = runIsLive,
                                nowMs = nowMs,
                                onOpenConversation = {
                                    onOpenRun(run, currentTask, runIsLive)
                                },
                            )
                        }
                    }
                    item(key = "danger_zone") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 16.dp),
                        ) {
                            CouiButton(
                                text = "删除定时任务",
                                role = CouiButtonRole.Destructive,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showDeleteConfirm = true },
                            )
                        }
                    }
                    item(key = "bottom_spacer") {
                        CouiPageBottomSpacer()
                    }
                }
            }

            CouiConfirmDialog(
                visible = showDeleteConfirm,
                title = "删除定时任务",
                message = "确定删除「${currentTask?.name.orEmpty()}」？这会取消后续排期并清除其运行时间线，已导入主聊天的对话保留不受影响。",
                confirmText = "删除",
                destructive = true,
                onConfirm = onConfirmDelete,
                onDismissRequest = { showDeleteConfirm = false },
            )
            CouiConfirmDialog(
                visible = showClearMemoConfirm,
                title = "清空任务小记事本",
                message = "清空后，下一次定时运行将不再参考上次记录的状态或游标。",
                confirmText = "清空",
                destructive = true,
                onConfirm = onConfirmClearMemo,
                onDismissRequest = { showClearMemoConfirm = false },
            )
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = title,
                onBack = onBack,
                actions = {
                    if (currentTask != null) {
                        MiuixTopBarActionButton(
                            icon = MiuixIcons.Glass.Edit,
                            contentDescription = "编辑定时任务",
                            onClick = { onNavigate(AppRoute.ScheduledTaskEditor(taskId = taskId)) },
                        )
                    }
                },
            ) {
                if (currentTask == null) {
                    item(key = "missing") {
                        Text(
                            text = "该定时任务已不存在或已被删除。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    item(key = "hero") {
                        MiuixTaskControlCard(
                            task = currentTask,
                            isRunning = isRunning,
                            nowMs = nowMs,
                            onRunOrStop = onRunOrStop,
                            onToggleEnabled = onToggleEnabled,
                        )
                    }
                    item(key = "prompt_section") {
                        SmallTitle(text = "任务指令与配置")
                    }
                    item(key = "prompt_card") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = currentTask.prompt,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "模型：$modelDisplay · ${ScheduledTaskUiFormatting.deliveryBadge(currentTask.deliveryMode)} · ${
                                        if (currentTask.showRunningNotification) "显示运行进度" else "静默运行"
                                    }",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    text = "能力：${buildCapabilitiesSummary(currentTask)}",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    if (currentTask.memo.isNotBlank()) {
                        item(key = "memo_section") {
                            SmallTitle(text = "任务小记事本")
                        }
                        item(key = "memo_card") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = currentTask.memo,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(
                                        text = "清空记事本",
                                        onClick = { showClearMemoConfirm = true },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "history_section") {
                        SmallTitle(text = "运行时间线")
                    }
                    if (runs.isEmpty()) {
                        item(key = "history_empty") {
                            Text(
                                text = "还没有运行记录，点上方「立即试跑」即可验证实际效果。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        itemsIndexed(items = runs, key = { _, run -> run.id }) { _, run ->
                            val runIsLive = isRunning && run.status == ScheduledTaskRunStatus.RUNNING
                            MiuixRunTimelineItem(
                                run = run,
                                isLive = runIsLive,
                                nowMs = nowMs,
                                onOpenConversation = {
                                    onOpenRun(run, currentTask, runIsLive)
                                },
                            )
                        }
                    }
                    item(key = "danger_zone") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                        ) {
                            TextButton(
                                text = "删除定时任务",
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    textColor = MiuixTheme.colorScheme.error,
                                ),
                                onClick = { showDeleteConfirm = true },
                            )
                        }
                    }
                }
            }

            if (showDeleteConfirm) {
                GlassDialog(
                    show = true,
                    title = "删除定时任务",
                    summary = "确定删除「${currentTask?.name.orEmpty()}」？这会取消后续排期并清除运行时间线。",
                    backdrop = currentGlassOverlayBackdrop(),
                    onDismissRequest = { showDeleteConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = "删除",
                        destructive = true,
                        onCancel = { showDeleteConfirm = false },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
            if (showClearMemoConfirm) {
                GlassDialog(
                    show = true,
                    title = "清空任务小记事本",
                    summary = "清空后，下一次定时运行将不再参考上次记录的状态或游标。",
                    backdrop = currentGlassOverlayBackdrop(),
                    onDismissRequest = { showClearMemoConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = "清空",
                        destructive = true,
                        onCancel = { showClearMemoConfirm = false },
                        onConfirm = onConfirmClearMemo,
                    )
                }
            }
        }
    }
}

@Composable
private fun CouiTaskControlCard(
    task: ScheduledTask,
    isRunning: Boolean,
    nowMs: Long,
    onRunOrStop: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val scheduleText = ScheduledTaskScheduleCalculator.describeSchedule(task.schedule)
    val nextText = if (task.enabled && task.nextRunAt != null) {
        "下次运行：${ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs)}"
    } else if (!task.autoDisabledReason.isNullOrBlank()) {
        task.autoDisabledReason
    } else {
        "当前已暂停"
    }

    Group {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CouiText(
                text = scheduleText,
                kind = CouiTextKind.ItemTitle,
                color = colors.title,
            )
            CouiText(
                text = nextText,
                kind = CouiTextKind.ItemSummary,
                color = colors.summary,
            )
            CouiButton(
                text = if (isRunning) stringResource(R.string.scheduled_action_stop)
                else stringResource(R.string.scheduled_action_run_now),
                role = if (isRunning) CouiButtonRole.DestructiveFilled else CouiButtonRole.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = onRunOrStop,
            )
        }
        Divider()
        CouiSwitchPreference(
            title = "启用定时排期",
            summary = if (task.enabled) "将按上述排期在后台自动唤醒运行" else "已暂停自动触发，仍可随时手动试跑",
            checked = task.enabled,
            onCheckedChange = onToggleEnabled,
        )
    }
}

@Composable
private fun MiuixTaskControlCard(
    task: ScheduledTask,
    isRunning: Boolean,
    nowMs: Long,
    onRunOrStop: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val scheduleText = ScheduledTaskScheduleCalculator.describeSchedule(task.schedule)
    val nextText = if (task.enabled && task.nextRunAt != null) {
        "下次运行：${ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs)}"
    } else if (!task.autoDisabledReason.isNullOrBlank()) {
        task.autoDisabledReason
    } else {
        "当前已暂停"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = scheduleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = nextText,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                text = if (isRunning) stringResource(R.string.scheduled_action_stop)
                else stringResource(R.string.scheduled_action_run_now),
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRunning) {
                    ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
                onClick = onRunOrStop,
            )
        }
        SwitchPreference(
            title = "启用定时排期",
            summary = if (task.enabled) "将按排期自动在后台执行" else "已暂停自动触发",
            checked = task.enabled,
            onCheckedChange = onToggleEnabled,
        )
    }
}

@Composable
private fun CouiRunTimelineItem(
    run: ScheduledTaskRun,
    isLive: Boolean,
    nowMs: Long,
    onOpenConversation: () -> Unit,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val canOpen = !run.archiveConversationKey.isNullOrBlank() &&
        (isLive || (run.status != ScheduledTaskRunStatus.RUNNING && run.status != ScheduledTaskRunStatus.SKIPPED))
    val dotColor = ScheduledTaskUiFormatting.statusDotColor(
        status = run.status,
        runningNow = isLive,
        autoDisabled = false,
    )
    val interaction = remember { MutableInteractionSource() }
    val headline = buildString {
        append(ScheduledTaskUiFormatting.statusLabel(run.status, runningNow = isLive))
        append(" · ")
        append(ScheduledTaskUiFormatting.triggerSourceLabel(run.triggerSource))
        val dur = ScheduledTaskUiFormatting.formatDuration(run.durationMs)
        if (dur.isNotEmpty()) append(" · ").append(dur)
    }
    val timeText = ScheduledTaskUiFormatting.formatTimestamp(run.startedAt, nowMs)

    Group {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canOpen) {
                        Modifier
                            .couiButtonFeedback(dark, interaction, enabled = true)
                            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpenConversation)
                    } else Modifier
                )
                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    CouiText(
                        text = headline,
                        kind = CouiTextKind.ItemTitle,
                        color = colors.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                CouiText(
                    text = timeText,
                    kind = CouiTextKind.ItemSummary,
                    color = colors.summary,
                )
            }
            if (run.summary.isNotBlank()) {
                CouiText(
                    text = run.summary,
                    kind = CouiTextKind.ItemSummary,
                    color = colors.summary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canOpen) {
                CouiText(
                    text = if (isLive) "点按实时查看运行过程" else "点按查看完整对话并继续追问",
                    kind = CouiTextKind.ItemSummary,
                    color = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun MiuixRunTimelineItem(
    run: ScheduledTaskRun,
    isLive: Boolean,
    nowMs: Long,
    onOpenConversation: () -> Unit,
) {
    val canOpen = !run.archiveConversationKey.isNullOrBlank() &&
        (isLive || (run.status != ScheduledTaskRunStatus.RUNNING && run.status != ScheduledTaskRunStatus.SKIPPED))
    val dotColor = ScheduledTaskUiFormatting.statusDotColor(
        status = run.status,
        runningNow = isLive,
        autoDisabled = false,
    )
    val headline = buildString {
        append(ScheduledTaskUiFormatting.statusLabel(run.status, runningNow = isLive))
        append(" · ")
        append(ScheduledTaskUiFormatting.triggerSourceLabel(run.triggerSource))
        val dur = ScheduledTaskUiFormatting.formatDuration(run.durationMs)
        if (dur.isNotEmpty()) append(" · ").append(dur)
    }
    val timeText = ScheduledTaskUiFormatting.formatTimestamp(run.startedAt, nowMs)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        BasicComponent(
            onClick = if (canOpen) onOpenConversation else null,
            title = headline,
            summary = run.summary.ifBlank {
                when {
                    isLive -> "点按实时查看运行过程"
                    canOpen -> "点按查看完整对话并继续追问"
                    else -> ""
                }
            },
            startAction = {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            },
            endActions = {
                Text(
                    text = timeText,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
    }
}

private fun buildCapabilitiesSummary(task: ScheduledTask): String {
    val caps = task.capabilities
    val list = buildList {
        if (caps.browserTools) add("网页与浏览器")
        if (caps.deviceReadTools) add("设备信息读取")
        if (caps.deviceActionTools) add("设备状态控制")
        if (caps.terminalTools) add("终端与文件")
        if (caps.mcpTools) add("MCP")
        if (caps.guiTools) add("应用界面操作")
    }
    return if (list.isEmpty()) "仅纯对话推理" else list.joinToString(" · ")
}
