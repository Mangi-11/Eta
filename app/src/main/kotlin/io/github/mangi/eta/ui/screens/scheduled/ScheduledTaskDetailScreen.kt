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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit as OutlinedEdit
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
import io.github.mangi.eta.ui.components.coui.CouiCardDivider
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceFooter
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiText
import io.github.mangi.eta.ui.components.coui.CouiTextKind
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.coui.couiButtonFeedback
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
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
    val resources = LocalResources.current
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

    val customModelId = currentTask?.modelId?.takeIf { it.isNotBlank() }
    val resolvedModel = remember(providers, customModelId) {
        customModelId?.let { providers.enabledModel(it) }
    }
    val modelDisplay = when {
        customModelId == null -> stringResource(R.string.scheduled_model_follow_default)
        resolvedModel != null -> "${resolvedModel.provider.name} / ${resolvedModel.model.displayName}"
        else -> stringResource(R.string.scheduled_model_unavailable_fallback)
    }

    val onRunOrStop: () -> Unit = {
        if (isRunning) {
            ScheduledTaskRunner.cancelRunningTask(context, taskId)
            Toast.makeText(
                context,
                resources.getString(R.string.scheduled_toast_stopping),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    ScheduledTaskRunner.triggerManualRun(context, taskId)
                }
                when (res) {
                    ScheduledTaskRunner.ManualTriggerResult.Started ->
                        Toast.makeText(
                            context,
                            resources.getString(R.string.scheduled_toast_manual_started),
                            Toast.LENGTH_SHORT,
                        ).show()
                    ScheduledTaskRunner.ManualTriggerResult.AlreadyRunning ->
                        Toast.makeText(
                            context,
                            resources.getString(R.string.scheduled_toast_already_running),
                            Toast.LENGTH_SHORT,
                        ).show()
                    ScheduledTaskRunner.ManualTriggerResult.NotFound ->
                        Toast.makeText(
                            context,
                            resources.getString(R.string.scheduled_toast_task_not_found),
                            Toast.LENGTH_SHORT,
                        ).show()
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
            Toast.makeText(
                context,
                resources.getString(R.string.scheduled_toast_memo_cleared),
                Toast.LENGTH_SHORT,
            ).show()
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
                            contentDescription = stringResource(R.string.route_scheduled_task_edit),
                            onClick = { onNavigate(AppRoute.ScheduledTaskEditor(taskId = taskId)) },
                        )
                    }
                },
                overlay = {
                    CouiConfirmDialog(
                        visible = showDeleteConfirm,
                        title = stringResource(R.string.scheduled_action_delete_task),
                        message = stringResource(
                            R.string.scheduled_delete_dialog_message,
                            currentTask?.name.orEmpty(),
                        ),
                        confirmText = stringResource(R.string.action_delete),
                        destructive = true,
                        onConfirm = onConfirmDelete,
                        onDismissRequest = { showDeleteConfirm = false },
                    )
                    CouiConfirmDialog(
                        visible = showClearMemoConfirm,
                        title = stringResource(R.string.scheduled_clear_memo_dialog_title),
                        message = stringResource(R.string.scheduled_clear_memo_dialog_message),
                        confirmText = stringResource(R.string.scheduled_action_clear),
                        destructive = true,
                        onConfirm = onConfirmClearMemo,
                        onDismissRequest = { showClearMemoConfirm = false },
                    )
                },
            ) {
                if (currentTask == null) {
                    item(key = "missing") {
                        CouiText(
                            text = stringResource(R.string.scheduled_detail_missing),
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
                        CouiPreferenceGroup(title = stringResource(R.string.scheduled_detail_section_config)) {
                            item {
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
                                CouiCardDivider()
                            }
                            item {
                                CouiPreference(
                                    title = stringResource(R.string.scheduled_field_model),
                                    value = modelDisplay,
                                    showChevron = false,
                                )
                            }
                            item {
                                CouiPreference(
                                    title = stringResource(R.string.scheduled_detail_field_notify_session),
                                    value = "${ScheduledTaskUiFormatting.deliveryBadge(currentTask.deliveryMode)} · ${
                                        stringResource(
                                            if (currentTask.showRunningNotification) {
                                                R.string.scheduled_progress_badge_show
                                            } else {
                                                R.string.scheduled_progress_badge_silent
                                            },
                                        )
                                    } · ${
                                        stringResource(
                                            if (currentTask.sessionMode.wireValue == "dedicated") {
                                                R.string.scheduled_session_badge_dedicated
                                            } else {
                                                R.string.scheduled_session_badge_isolated
                                            },
                                        )
                                    }",
                                    showChevron = false,
                                )
                            }
                            item {
                                CouiPreference(
                                    title = stringResource(R.string.scheduled_detail_field_capabilities),
                                    summary = buildCapabilitiesSummary(currentTask),
                                    showChevron = false,
                                )
                            }
                        }
                    }
                    if (currentTask.memo.isNotBlank()) {
                        item(key = "memo_card") {
                            CouiPreferenceGroup(title = stringResource(R.string.scheduled_detail_memo_title_with_hint)) {
                                item {
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
                                            text = stringResource(R.string.scheduled_action_clear_memo),
                                            role = CouiButtonRole.Secondary,
                                            onClick = { showClearMemoConfirm = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "history_section") {
                        CouiSectionTitle(stringResource(R.string.scheduled_detail_section_timeline))
                    }
                    if (runs.isEmpty()) {
                        item(key = "history_empty") {
                            CouiPreferenceFooter(text = stringResource(R.string.scheduled_detail_timeline_empty))
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
                                text = stringResource(R.string.scheduled_action_delete_task),
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
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = title,
                onBack = onBack,
                actions = {
                    if (currentTask != null) {
                        MiuixTopBarActionButton(
                            icon = MiuixIcons.Glass.Edit,
                            contentDescription = stringResource(R.string.route_scheduled_task_edit),
                            onClick = { onNavigate(AppRoute.ScheduledTaskEditor(taskId = taskId)) },
                        )
                    }
                },
            ) {
                if (currentTask == null) {
                    item(key = "missing") {
                        Text(
                            text = stringResource(R.string.scheduled_detail_missing),
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
                        SmallTitle(text = stringResource(R.string.scheduled_detail_section_config))
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
                                    text = stringResource(
                                        R.string.scheduled_detail_miuix_model_meta,
                                        modelDisplay,
                                        ScheduledTaskUiFormatting.deliveryBadge(currentTask.deliveryMode),
                                        stringResource(
                                            if (currentTask.showRunningNotification) {
                                                R.string.scheduled_progress_badge_show_full
                                            } else {
                                                R.string.scheduled_progress_badge_silent
                                            },
                                        ),
                                    ),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.scheduled_detail_miuix_capabilities_meta,
                                        buildCapabilitiesSummary(currentTask),
                                    ),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    if (currentTask.memo.isNotBlank()) {
                        item(key = "memo_section") {
                            SmallTitle(text = stringResource(R.string.scheduled_detail_memo_title))
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
                                        text = stringResource(R.string.scheduled_action_clear_memo),
                                        onClick = { showClearMemoConfirm = true },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "history_section") {
                        SmallTitle(text = stringResource(R.string.scheduled_detail_section_timeline))
                    }
                    if (runs.isEmpty()) {
                        item(key = "history_empty") {
                            Text(
                                text = stringResource(R.string.scheduled_detail_timeline_empty),
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
                                text = stringResource(R.string.scheduled_action_delete_task),
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
                    title = stringResource(R.string.scheduled_action_delete_task),
                    summary = stringResource(
                        R.string.scheduled_delete_dialog_message,
                        currentTask?.name.orEmpty(),
                    ),
                    backdrop = currentGlassOverlayBackdrop(),
                    onDismissRequest = { showDeleteConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_delete),
                        destructive = true,
                        onCancel = { showDeleteConfirm = false },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
            if (showClearMemoConfirm) {
                GlassDialog(
                    show = true,
                    title = stringResource(R.string.scheduled_clear_memo_dialog_title),
                    summary = stringResource(R.string.scheduled_clear_memo_dialog_message),
                    backdrop = currentGlassOverlayBackdrop(),
                    onDismissRequest = { showClearMemoConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.scheduled_action_clear),
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
        stringResource(
            R.string.scheduled_next_run_prefix,
            ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs),
        )
    } else if (!task.autoDisabledReason.isNullOrBlank()) {
        task.autoDisabledReason
    } else {
        stringResource(R.string.scheduled_status_currently_paused)
    }

    CouiPreferenceGroup {
        item {
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
            CouiCardDivider()
        }
        item {
            CouiSwitchPreference(
                title = stringResource(R.string.scheduled_detail_enable_schedule_title),
                summary = stringResource(
                    if (task.enabled) {
                        R.string.scheduled_detail_enable_schedule_on_summary
                    } else {
                        R.string.scheduled_detail_enable_schedule_off_summary
                    },
                ),
                checked = task.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
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
        stringResource(
            R.string.scheduled_next_run_prefix,
            ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs),
        )
    } else if (!task.autoDisabledReason.isNullOrBlank()) {
        task.autoDisabledReason
    } else {
        stringResource(R.string.scheduled_status_currently_paused)
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
            title = stringResource(R.string.scheduled_detail_enable_schedule_title),
            summary = stringResource(
                if (task.enabled) {
                    R.string.scheduled_detail_enable_schedule_on_summary
                } else {
                    R.string.scheduled_detail_enable_schedule_off_summary
                },
            ),
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
    val colors = CouiPageDefaults.themeColors()
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

    CouiPreferenceGroup {
        item {
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
                        text = stringResource(
                            if (isLive) R.string.scheduled_timeline_tap_live else R.string.scheduled_timeline_tap_conversation,
                        ),
                        kind = CouiTextKind.ItemSummary,
                        color = colors.accent,
                    )
                }
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
                    isLive -> stringResource(R.string.scheduled_timeline_tap_live)
                    canOpen -> stringResource(R.string.scheduled_timeline_tap_conversation)
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

@Composable
private fun buildCapabilitiesSummary(task: ScheduledTask): String {
    val caps = task.capabilities
    val list = buildList {
        if (caps.browserTools) add(stringResource(R.string.scheduled_cap_short_browser))
        if (caps.deviceReadTools) add(stringResource(R.string.scheduled_cap_device_read_title))
        if (caps.deviceActionTools) add(stringResource(R.string.scheduled_cap_device_action_title))
        if (caps.terminalTools) add(stringResource(R.string.scheduled_cap_short_terminal))
        if (caps.mcpTools) add("MCP")
        if (caps.guiTools) add(stringResource(R.string.scheduled_cap_short_gui))
    }
    return if (list.isEmpty()) stringResource(R.string.scheduled_cap_none) else list.joinToString(" · ")
}
