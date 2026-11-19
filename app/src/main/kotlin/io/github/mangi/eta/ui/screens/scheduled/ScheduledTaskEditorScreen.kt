package io.github.mangi.eta.ui.screens.scheduled

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskMisfirePolicy
import io.github.mangi.eta.data.model.ScheduledTaskPresets
import io.github.mangi.eta.data.model.ScheduledTaskQuietHours
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixTopBarActionButton
import io.github.mangi.eta.ui.components.OptionalModelPickerContent
import io.github.mangi.eta.ui.components.coui.CouiChipTabs
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarTextButton
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScheduledTaskEditorScreen(
    taskId: String? = null,
    presetId: String? = null,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val isEditing = !taskId.isNullOrBlank()
    val preset = remember(presetId) { ScheduledTaskPresets.find(presetId) }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)

    var loadedExisting by remember(taskId) { mutableStateOf<ScheduledTask?>(null) }
    var initialized by remember(taskId, presetId) { mutableStateOf(false) }

    val presetDefaultName = preset?.let { stringResource(it.defaultNameRes) }.orEmpty()
    val presetPrompt = preset?.let { stringResource(it.promptRes) }.orEmpty()
    var name by remember(taskId, presetId) { mutableStateOf(presetDefaultName) }
    var prompt by remember(taskId, presetId) { mutableStateOf(presetPrompt) }
    var triggerKind by remember { mutableStateOf(preset?.schedule?.kind ?: ScheduledTaskTriggerKind.DAILY) }
    var hour by remember { mutableIntStateOf(preset?.schedule?.hour ?: 9) }
    var minute by remember { mutableIntStateOf(preset?.schedule?.minute ?: 0) }
    var daysOfWeek by remember { mutableStateOf(preset?.schedule?.daysOfWeek ?: setOf(1, 2, 3, 4, 5)) }
    var intervalMinutes by remember { mutableIntStateOf(preset?.schedule?.intervalMinutes ?: 120) }
    var onceDelayMinutes by remember { mutableIntStateOf(60) }
    var onceTriggerAtMs by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    var cronExpression by remember { mutableStateOf("0 9 * * 1-5") }
    var deliveryMode by remember { mutableStateOf(preset?.deliveryMode ?: ScheduledTaskDeliveryMode.ALWAYS) }
    var showRunningNotification by remember { mutableStateOf(false) }
    var sessionMode by remember { mutableStateOf(preset?.sessionMode ?: ScheduledTaskSessionMode.ISOLATED) }
    var misfirePolicy by remember { mutableStateOf(ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW) }
    var quietHoursEnabled by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    var selectedReasoningEffort by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf(preset?.capabilities ?: ScheduledTaskCapabilities()) }
    var timeoutSeconds by remember { mutableIntStateOf(ScheduledTask.DEFAULT_TIMEOUT_SECONDS) }

    var showAdvanced by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    LaunchedEffect(taskId) {
        if (!taskId.isNullOrBlank()) {
            val existing = withContext(Dispatchers.IO) {
                ScheduledTaskRepository.getTask(taskId, context)
            }
            if (existing != null) {
                loadedExisting = existing
                name = existing.name
                prompt = existing.prompt
                triggerKind = existing.schedule.kind
                hour = existing.schedule.hour
                minute = existing.schedule.minute
                daysOfWeek = existing.schedule.daysOfWeek
                intervalMinutes = existing.schedule.intervalMinutes
                existing.schedule.triggerAtEpochMs?.let { onceTriggerAtMs = it }
                cronExpression = existing.schedule.cronExpression.ifBlank { "0 9 * * 1-5" }
                deliveryMode = existing.deliveryMode
                showRunningNotification = existing.showRunningNotification
                sessionMode = existing.sessionMode
                misfirePolicy = existing.misfirePolicy
                quietHoursEnabled = existing.quietHours.enabled
                selectedModelId = existing.modelId
                selectedReasoningEffort = existing.reasoningEffort
                capabilities = existing.capabilities
                timeoutSeconds = existing.timeoutSeconds
            }
        }
        initialized = true
    }

    val currentSchedule = remember(
        triggerKind, hour, minute, daysOfWeek, intervalMinutes, onceTriggerAtMs, cronExpression
    ) {
        ScheduledTaskSchedule(
            kind = triggerKind,
            triggerAtEpochMs = if (triggerKind == ScheduledTaskTriggerKind.ONCE) onceTriggerAtMs else null,
            hour = hour,
            minute = minute,
            daysOfWeek = daysOfWeek,
            intervalMinutes = intervalMinutes,
            cronExpression = cronExpression.trim(),
        )
    }

    val previewNowMs = remember(currentSchedule, quietHoursEnabled) { System.currentTimeMillis() }
    val previewQuietHours = remember(quietHoursEnabled) { ScheduledTaskQuietHours(enabled = quietHoursEnabled) }
    val scheduleError = ScheduledTaskScheduleCalculator.validateSchedule(currentSchedule, previewNowMs)
    val nextRunTimestamp = remember(currentSchedule, previewNowMs, previewQuietHours, scheduleError) {
        if (scheduleError != null) {
            null
        } else {
            ScheduledTaskScheduleCalculator.computeNextRunAt(
                schedule = currentSchedule,
                afterMs = previewNowMs,
                quietHours = previewQuietHours,
            )
        }
    }
    val nextRunPreview = if (scheduleError != null) {
        scheduleError
    } else {
        stringResource(
            R.string.scheduled_next_run_prefix,
            ScheduledTaskScheduleCalculator.formatNextRunLabel(nextRunTimestamp, previewNowMs),
        )
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedCustom = remember(providers, selectedModelId) {
        selectedModelId?.takeIf { it.isNotBlank() }?.let { providers.enabledModel(it) }
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val followTitle = stringResource(R.string.scheduled_model_follow_default)
    val followSubtitle = if (primaryModelName != null) {
        stringResource(R.string.scheduled_model_current_default, primaryModelName)
    } else {
        stringResource(R.string.scheduled_model_use_global_default)
    }
    val modelSummary = when {
        resolvedCustom != null -> "${resolvedCustom.provider.name} / ${resolvedCustom.model.displayName}"
        !primaryModelName.isNullOrBlank() ->
            stringResource(R.string.scheduled_model_follow_default_named, primaryModelName)
        else -> followTitle
    }
    val effectiveCapabilities = remember(providers, resolvedCustom, primaryModelId) {
        val target = resolvedCustom ?: providers.enabledModel(primaryModelId)
        target?.let { (provider, model) -> RuntimeConfigRepository.reasoningCapabilities(provider, model) }
    }
    val effortOptions = remember(effectiveCapabilities) {
        listOf<ReasoningEffort?>(null) + effectiveCapabilities?.selectableEfforts.orEmpty()
    }
    val effortLabels = effortOptions.map { effort ->
        when (effort) {
            null -> stringResource(R.string.scheduled_effort_follow_default)
            ReasoningEffort.DEFAULT -> stringResource(R.string.scheduled_effort_model_default)
            else -> effort.displayName
        }
    }
    val selectedEffortIndex = remember(effortOptions, selectedReasoningEffort) {
        val current = selectedReasoningEffort?.let(ReasoningEffort::fromWireValue)
        effortOptions.indexOf(current).takeIf { it >= 0 } ?: 0
    }

    val openTimePicker: () -> Unit = {
        TimePickerDialog(
            context,
            { _, pickedHour, pickedMinute ->
                hour = pickedHour
                minute = pickedMinute
            },
            hour,
            minute,
            true,
        ).show()
    }

    val canSave = initialized && !saving && name.isNotBlank() && prompt.isNotBlank() && scheduleError == null
    val onSaveClick: () -> Unit = {
        if (canSave) {
            saving = true
            scope.launch {
                val nowMs = System.currentTimeMillis()
                val existing = loadedExisting
                val finalSchedule = if (triggerKind == ScheduledTaskTriggerKind.ONCE && onceTriggerAtMs <= nowMs) {
                    currentSchedule.copy(triggerAtEpochMs = nowMs + onceDelayMinutes.coerceAtLeast(1) * 60_000L)
                } else {
                    currentSchedule
                }
                val taskToSave = (existing ?: ScheduledTask(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    prompt = prompt.trim(),
                )).copy(
                    name = name.trim(),
                    prompt = prompt.trim(),
                    enabled = existing?.enabled ?: true,
                    schedule = finalSchedule,
                    misfirePolicy = misfirePolicy,
                    quietHours = ScheduledTaskQuietHours(enabled = quietHoursEnabled),
                    deliveryMode = deliveryMode,
                    showRunningNotification = showRunningNotification,
                    sessionMode = sessionMode,
                    modelId = selectedModelId,
                    reasoningEffort = selectedReasoningEffort,
                    capabilities = capabilities,
                    timeoutSeconds = timeoutSeconds,
                )
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        ScheduledTaskRepository.saveTask(
                            task = taskToSave,
                            nowMs = nowMs,
                            rescheduleAlarm = true,
                            context = context,
                        )
                    }
                }
                saving = false
                result.fold(
                    onSuccess = { saved -> onSaved(saved.id) },
                    onFailure = { failure ->
                        Toast.makeText(
                            context,
                            failure.message ?: resources.getString(R.string.scheduled_toast_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    val pageTitle = stringResource(
        if (isEditing) R.string.route_scheduled_task_edit else R.string.route_scheduled_task_new,
    )

    val triggerTabs = listOf(
        ScheduledTaskTriggerKind.DAILY to stringResource(R.string.scheduled_trigger_daily),
        ScheduledTaskTriggerKind.WEEKDAYS to stringResource(R.string.scheduled_trigger_weekdays),
        ScheduledTaskTriggerKind.WEEKLY to stringResource(R.string.scheduled_trigger_weekly),
        ScheduledTaskTriggerKind.INTERVAL to stringResource(R.string.scheduled_trigger_interval),
        ScheduledTaskTriggerKind.ONCE to stringResource(R.string.scheduled_trigger_once),
        ScheduledTaskTriggerKind.CRON to stringResource(R.string.scheduled_trigger_cron),
    )
    val deliveryOptions = listOf(
        ScheduledTaskDeliveryMode.ALWAYS to stringResource(R.string.scheduled_delivery_option_always),
        ScheduledTaskDeliveryMode.ON_CHANGE to stringResource(R.string.scheduled_delivery_option_on_change),
        ScheduledTaskDeliveryMode.SILENT to stringResource(R.string.scheduled_delivery_option_silent),
    )
    val sessionOptions = listOf(
        ScheduledTaskSessionMode.ISOLATED to stringResource(R.string.scheduled_session_option_isolated),
        ScheduledTaskSessionMode.DEDICATED to stringResource(R.string.scheduled_session_option_dedicated),
    )
    val misfireOptions = listOf(
        ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW to stringResource(R.string.scheduled_misfire_option_catchup),
        ScheduledTaskMisfirePolicy.SKIP to stringResource(R.string.scheduled_misfire_option_skip),
    )
    val timeoutChoices = remember { listOf(120, 300, 600, 900) }
    val timeoutLabels = listOf(
        stringResource(R.string.scheduled_timeout_2m),
        stringResource(R.string.scheduled_timeout_5m_recommended),
        stringResource(R.string.scheduled_timeout_10m),
        stringResource(R.string.scheduled_timeout_15m),
    )
    val intervalChoices = remember { listOf(15, 30, 60, 120, 240, 360, 720) }
    val onceDelayChoices = remember { listOf(15, 30, 60, 120, 360, 1440) }
    val onceDelayOptions = onceDelayChoices.map { mins ->
        mins to when {
            mins >= 1440 -> pluralStringResource(R.plurals.scheduled_delay_days, mins / 1440, mins / 1440)
            mins >= 60 -> pluralStringResource(R.plurals.scheduled_delay_hours, mins / 60, mins / 60)
            else -> pluralStringResource(R.plurals.scheduled_delay_minutes, mins, mins)
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            CouiScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                compactTitle = true,
                actions = {
                    CouiTopAppBarTextButton(
                        text = stringResource(R.string.action_save),
                        enabled = canSave,
                        onClick = onSaveClick,
                    )
                },
                overlay = {
                    Dialog(
                        visible = showModelSheet,
                        onDismissRequest = { showModelSheet = false },
                        dark = dark,
                        title = stringResource(R.string.scheduled_field_model),
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.action_close),
                            onClick = { showModelSheet = false },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        OptionalModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedCustom?.model?.id,
                            followTitle = followTitle,
                            followSubtitle = followSubtitle,
                            onSelectModel = { id ->
                                showModelSheet = false
                                selectedModelId = id
                            },
                        )
                    }
                },
            ) {
                Group(title = stringResource(R.string.scheduled_editor_section_content)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CouiTextField(
                            value = name,
                            onValueChange = { name = it.take(80) },
                            label = stringResource(R.string.scheduled_editor_name_label),
                            placeholder = stringResource(R.string.scheduled_editor_name_placeholder),
                            singleLine = true,
                        )
                        CouiTextField(
                            value = prompt,
                            onValueChange = { prompt = it.take(8000) },
                            label = stringResource(R.string.scheduled_editor_prompt_label),
                            placeholder = stringResource(R.string.scheduled_editor_prompt_placeholder),
                            singleLine = false,
                            minLines = 4,
                            maxLines = 10,
                        )
                    }
                }

                CouiSectionTitle(stringResource(R.string.scheduled_editor_section_schedule))
                CouiChipTabs(
                    items = triggerTabs,
                    selectedKey = triggerKind,
                    onSelectedKeyChange = { next ->
                        triggerKind = next
                        if (next == ScheduledTaskTriggerKind.ONCE && onceTriggerAtMs <= System.currentTimeMillis()) {
                            onceTriggerAtMs = System.currentTimeMillis() + onceDelayMinutes * 60_000L
                        }
                    },
                )
                Group {
                    when (triggerKind) {
                        ScheduledTaskTriggerKind.DAILY,
                        ScheduledTaskTriggerKind.WEEKDAYS -> {
                            CouiPreference(
                                title = stringResource(R.string.scheduled_editor_trigger_time),
                                value = ScheduledTaskScheduleCalculator.formatHourMinute(hour, minute),
                                onClick = openTimePicker,
                            )
                        }
                        ScheduledTaskTriggerKind.WEEKLY -> {
                            CouiPreference(
                                title = stringResource(R.string.scheduled_editor_trigger_time),
                                value = ScheduledTaskScheduleCalculator.formatHourMinute(hour, minute),
                                onClick = openTimePicker,
                            )
                            Divider()
                            WeekdaySelectorRow(
                                selectedDays = daysOfWeek,
                                onToggleDay = { day ->
                                    daysOfWeek = if (day in daysOfWeek && daysOfWeek.size > 1) {
                                        daysOfWeek - day
                                    } else {
                                        daysOfWeek + day
                                    }
                                },
                                isCoui = true,
                            )
                        }
                        ScheduledTaskTriggerKind.INTERVAL -> {
                            QuickOptionPillsRow(
                                title = stringResource(R.string.scheduled_editor_interval_label),
                                options = intervalChoices.map { it to ScheduledTaskScheduleCalculator.formatInterval(it) },
                                selected = intervalMinutes,
                                onSelect = { intervalMinutes = it },
                                isCoui = true,
                            )
                        }
                        ScheduledTaskTriggerKind.ONCE -> {
                            QuickOptionPillsRow(
                                title = stringResource(R.string.scheduled_editor_once_delay_label),
                                options = onceDelayOptions,
                                selected = onceDelayMinutes,
                                onSelect = { mins ->
                                    onceDelayMinutes = mins
                                    onceTriggerAtMs = System.currentTimeMillis() + mins * 60_000L
                                },
                                isCoui = true,
                            )
                        }
                        ScheduledTaskTriggerKind.CRON -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
                            ) {
                                CouiTextField(
                                    value = cronExpression,
                                    onValueChange = { cronExpression = it.take(80) },
                                    label = stringResource(R.string.scheduled_editor_cron_label),
                                    placeholder = "0 9 * * 1-5",
                                    singleLine = true,
                                )
                            }
                        }
                    }
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.scheduled_editor_schedule_preview),
                        summary = nextRunPreview,
                        showChevron = false,
                    )
                }

                Group(title = stringResource(R.string.scheduled_editor_section_delivery)) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.scheduled_editor_result_notification),
                        items = deliveryOptions.map { it.second },
                        selectedIndex = deliveryOptions.indexOfFirst { it.first == deliveryMode }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx -> deliveryMode = deliveryOptions[idx].first },
                    )
                    Divider()
                    CouiSwitchPreference(
                        title = stringResource(R.string.scheduled_editor_show_progress_title),
                        summary = stringResource(R.string.scheduled_editor_show_progress_summary),
                        checked = showRunningNotification,
                        onCheckedChange = { showRunningNotification = it },
                    )
                }

                Spacer(modifier = Modifier.height(CouiPageDefaults.groupSpacing))
                Group {
                    CouiPreference(
                        title = stringResource(
                            if (showAdvanced) {
                                R.string.scheduled_editor_advanced_collapse
                            } else {
                                R.string.scheduled_editor_advanced_expand
                            },
                        ),
                        summary = if (showAdvanced) null else stringResource(R.string.scheduled_editor_advanced_summary),
                        onClick = { showAdvanced = !showAdvanced },
                    )
                }

                if (showAdvanced) {
                    Group(title = stringResource(R.string.scheduled_editor_section_model)) {
                        CouiPreference(
                            title = stringResource(R.string.scheduled_field_model),
                            summary = modelSummary,
                            onClick = { showModelSheet = true },
                        )
                        if (effortOptions.size > 1) {
                            Divider()
                            CouiDropdownPreference(
                                title = stringResource(R.string.scheduled_editor_reasoning_effort),
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                onSelectedIndexChange = { idx ->
                                    selectedReasoningEffort = effortOptions[idx]?.wireValue
                                },
                            )
                        }
                    }

                    Group(title = stringResource(R.string.scheduled_editor_section_capabilities)) {
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_cap_browser_title),
                            summary = stringResource(R.string.scheduled_cap_browser_summary),
                            checked = capabilities.browserTools,
                            onCheckedChange = { capabilities = capabilities.copy(browserTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_cap_device_read_title),
                            summary = stringResource(R.string.scheduled_cap_device_read_summary),
                            checked = capabilities.deviceReadTools,
                            onCheckedChange = { capabilities = capabilities.copy(deviceReadTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_cap_device_action_title),
                            summary = stringResource(R.string.scheduled_cap_device_action_summary),
                            checked = capabilities.deviceActionTools,
                            onCheckedChange = { capabilities = capabilities.copy(deviceActionTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_cap_terminal_title),
                            summary = stringResource(R.string.scheduled_cap_terminal_summary),
                            checked = capabilities.terminalTools,
                            onCheckedChange = { capabilities = capabilities.copy(terminalTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_cap_mcp_title),
                            summary = stringResource(R.string.scheduled_cap_mcp_summary),
                            checked = capabilities.mcpTools,
                            onCheckedChange = { capabilities = capabilities.copy(mcpTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_cap_gui_title),
                            summary = stringResource(R.string.scheduled_cap_gui_summary),
                            checked = capabilities.guiTools,
                            onCheckedChange = { capabilities = capabilities.copy(guiTools = it) },
                        )
                    }

                    Group(title = stringResource(R.string.scheduled_editor_section_session_policy)) {
                        CouiDropdownPreference(
                            title = stringResource(R.string.scheduled_editor_session_mode),
                            items = sessionOptions.map { it.second },
                            selectedIndex = sessionOptions.indexOfFirst { it.first == sessionMode }.coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> sessionMode = sessionOptions[idx].first },
                        )
                        Divider()
                        CouiDropdownPreference(
                            title = stringResource(R.string.scheduled_editor_misfire_policy),
                            items = misfireOptions.map { it.second },
                            selectedIndex = misfireOptions.indexOfFirst { it.first == misfirePolicy }.coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> misfirePolicy = misfireOptions[idx].first },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.scheduled_editor_quiet_hours_title),
                            summary = stringResource(R.string.scheduled_editor_quiet_hours_summary),
                            checked = quietHoursEnabled,
                            onCheckedChange = { quietHoursEnabled = it },
                        )
                        Divider()
                        CouiDropdownPreference(
                            title = stringResource(R.string.scheduled_editor_timeout),
                            items = timeoutLabels,
                            selectedIndex = timeoutChoices.indexOf(timeoutSeconds).coerceAtLeast(1),
                            onSelectedIndexChange = { idx -> timeoutSeconds = timeoutChoices[idx] },
                        )
                    }
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                modifier = Modifier.imePadding(),
                actions = {
                    MiuixTopBarActionButton(
                        icon = MiuixIcons.Glass.Ok,
                        contentDescription = stringResource(R.string.action_save),
                        enabled = canSave,
                        onClick = onSaveClick,
                    )
                },
                overlay = {
                    GlassBottomSheet(
                        show = showModelSheet,
                        backdrop = currentGlassOverlayBackdrop(),
                        title = stringResource(R.string.scheduled_field_model),
                        onDismissRequest = { showModelSheet = false },
                        insideMargin = DpSize(0.dp, 0.dp),
                    ) {
                        OptionalModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedCustom?.model?.id,
                            followTitle = followTitle,
                            followSubtitle = followSubtitle,
                            onSelectModel = { id ->
                                showModelSheet = false
                                selectedModelId = id
                            },
                        )
                    }
                },
            ) {
                item(key = "basic_content") {
                    SmallTitle(text = stringResource(R.string.scheduled_editor_section_content))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextField(
                                value = name,
                                onValueChange = { name = it.take(80) },
                                label = stringResource(R.string.scheduled_editor_name_label_with_example),
                                singleLine = true,
                            )
                            TextField(
                                value = prompt,
                                onValueChange = { prompt = it.take(8000) },
                                label = stringResource(R.string.scheduled_editor_prompt_label),
                                minLines = 4,
                                maxLines = 10,
                            )
                        }
                    }
                }

                item(key = "schedule_section") {
                    SmallTitle(text = stringResource(R.string.scheduled_editor_section_schedule))
                    QuickOptionPillsRow(
                        title = "",
                        options = triggerTabs,
                        selected = triggerKind,
                        onSelect = { next ->
                            triggerKind = next
                            if (next == ScheduledTaskTriggerKind.ONCE && onceTriggerAtMs <= System.currentTimeMillis()) {
                                onceTriggerAtMs = System.currentTimeMillis() + onceDelayMinutes * 60_000L
                            }
                        },
                        isCoui = false,
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        when (triggerKind) {
                            ScheduledTaskTriggerKind.DAILY,
                            ScheduledTaskTriggerKind.WEEKDAYS -> {
                                ArrowPreference(
                                    title = stringResource(R.string.scheduled_editor_trigger_time),
                                    summary = ScheduledTaskScheduleCalculator.formatHourMinute(hour, minute),
                                    onClick = openTimePicker,
                                )
                            }
                            ScheduledTaskTriggerKind.WEEKLY -> {
                                ArrowPreference(
                                    title = stringResource(R.string.scheduled_editor_trigger_time),
                                    summary = ScheduledTaskScheduleCalculator.formatHourMinute(hour, minute),
                                    onClick = openTimePicker,
                                )
                                WeekdaySelectorRow(
                                    selectedDays = daysOfWeek,
                                    onToggleDay = { day ->
                                        daysOfWeek = if (day in daysOfWeek && daysOfWeek.size > 1) {
                                            daysOfWeek - day
                                        } else {
                                            daysOfWeek + day
                                        }
                                    },
                                    isCoui = false,
                                )
                            }
                            ScheduledTaskTriggerKind.INTERVAL -> {
                                QuickOptionPillsRow(
                                    title = stringResource(R.string.scheduled_editor_interval_label),
                                    options = intervalChoices.map { it to ScheduledTaskScheduleCalculator.formatInterval(it) },
                                    selected = intervalMinutes,
                                    onSelect = { intervalMinutes = it },
                                    isCoui = false,
                                )
                            }
                            ScheduledTaskTriggerKind.ONCE -> {
                                QuickOptionPillsRow(
                                    title = stringResource(R.string.scheduled_editor_once_delay_label),
                                    options = onceDelayOptions,
                                    selected = onceDelayMinutes,
                                    onSelect = { mins ->
                                        onceDelayMinutes = mins
                                        onceTriggerAtMs = System.currentTimeMillis() + mins * 60_000L
                                    },
                                    isCoui = false,
                                )
                            }
                            ScheduledTaskTriggerKind.CRON -> {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    TextField(
                                        value = cronExpression,
                                        onValueChange = { cronExpression = it.take(80) },
                                        label = stringResource(R.string.scheduled_editor_cron_label),
                                        singleLine = true,
                                    )
                                }
                            }
                        }
                        Text(
                            text = nextRunPreview,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                item(key = "delivery_section") {
                    SmallTitle(text = stringResource(R.string.scheduled_editor_section_delivery))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        GlassDropdownPreference(
                            title = stringResource(R.string.scheduled_editor_result_notification),
                            items = deliveryOptions.map { it.second },
                            selectedIndex = deliveryOptions.indexOfFirst { it.first == deliveryMode }.coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> deliveryMode = deliveryOptions[idx].first },
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                        SwitchPreference(
                            title = stringResource(R.string.scheduled_editor_show_progress_title),
                            summary = stringResource(R.string.scheduled_editor_show_progress_summary),
                            checked = showRunningNotification,
                            onCheckedChange = { showRunningNotification = it },
                        )
                    }
                }

                item(key = "advanced_toggle") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(
                                if (showAdvanced) {
                                    R.string.scheduled_editor_advanced_collapse
                                } else {
                                    R.string.scheduled_editor_advanced_expand
                                },
                            ),
                            summary = if (showAdvanced) null else stringResource(R.string.scheduled_editor_advanced_summary),
                            onClick = { showAdvanced = !showAdvanced },
                        )
                    }
                }

                if (showAdvanced) {
                    item(key = "advanced_model") {
                        SmallTitle(text = stringResource(R.string.scheduled_editor_section_model))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            ArrowPreference(
                                title = stringResource(R.string.scheduled_field_model),
                                summary = modelSummary,
                                onClick = { showModelSheet = true },
                            )
                            if (effortOptions.size > 1) {
                                GlassDropdownPreference(
                                    title = stringResource(R.string.scheduled_editor_reasoning_effort),
                                    items = effortLabels,
                                    selectedIndex = selectedEffortIndex,
                                    onSelectedIndexChange = { idx ->
                                        selectedReasoningEffort = effortOptions[idx]?.wireValue
                                    },
                                    backdrop = currentGlassOverlayBackdrop(),
                                )
                            }
                        }
                    }

                    item(key = "advanced_caps") {
                        SmallTitle(text = stringResource(R.string.scheduled_editor_section_capabilities))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_cap_browser_title),
                                summary = stringResource(R.string.scheduled_cap_browser_summary),
                                checked = capabilities.browserTools,
                                onCheckedChange = { capabilities = capabilities.copy(browserTools = it) },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_cap_device_read_title),
                                summary = stringResource(R.string.scheduled_cap_device_read_summary),
                                checked = capabilities.deviceReadTools,
                                onCheckedChange = { capabilities = capabilities.copy(deviceReadTools = it) },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_cap_device_action_title),
                                summary = stringResource(R.string.scheduled_cap_device_action_summary),
                                checked = capabilities.deviceActionTools,
                                onCheckedChange = { capabilities = capabilities.copy(deviceActionTools = it) },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_cap_terminal_title),
                                summary = stringResource(R.string.scheduled_cap_terminal_summary),
                                checked = capabilities.terminalTools,
                                onCheckedChange = { capabilities = capabilities.copy(terminalTools = it) },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_cap_mcp_title),
                                summary = stringResource(R.string.scheduled_cap_mcp_summary),
                                checked = capabilities.mcpTools,
                                onCheckedChange = { capabilities = capabilities.copy(mcpTools = it) },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_cap_gui_title),
                                summary = stringResource(R.string.scheduled_cap_gui_summary),
                                checked = capabilities.guiTools,
                                onCheckedChange = { capabilities = capabilities.copy(guiTools = it) },
                            )
                        }
                    }

                    item(key = "advanced_session") {
                        SmallTitle(text = stringResource(R.string.scheduled_editor_section_session_policy))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            GlassDropdownPreference(
                                title = stringResource(R.string.scheduled_editor_session_mode),
                                items = sessionOptions.map { it.second },
                                selectedIndex = sessionOptions.indexOfFirst { it.first == sessionMode }.coerceAtLeast(0),
                                onSelectedIndexChange = { idx -> sessionMode = sessionOptions[idx].first },
                                backdrop = currentGlassOverlayBackdrop(),
                            )
                            GlassDropdownPreference(
                                title = stringResource(R.string.scheduled_editor_misfire_policy),
                                items = misfireOptions.map { it.second },
                                selectedIndex = misfireOptions.indexOfFirst { it.first == misfirePolicy }.coerceAtLeast(0),
                                onSelectedIndexChange = { idx -> misfirePolicy = misfireOptions[idx].first },
                                backdrop = currentGlassOverlayBackdrop(),
                            )
                            SwitchPreference(
                                title = stringResource(R.string.scheduled_editor_quiet_hours_title),
                                summary = stringResource(R.string.scheduled_editor_quiet_hours_summary),
                                checked = quietHoursEnabled,
                                onCheckedChange = { quietHoursEnabled = it },
                            )
                            GlassDropdownPreference(
                                title = stringResource(R.string.scheduled_editor_timeout),
                                items = timeoutLabels,
                                selectedIndex = timeoutChoices.indexOf(timeoutSeconds).coerceAtLeast(1),
                                onSelectedIndexChange = { idx -> timeoutSeconds = timeoutChoices[idx] },
                                backdrop = currentGlassOverlayBackdrop(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdaySelectorRow(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    isCoui: Boolean,
) {
    val labels = listOf(
        1 to stringResource(R.string.scheduled_weekday_narrow_mon),
        2 to stringResource(R.string.scheduled_weekday_narrow_tue),
        3 to stringResource(R.string.scheduled_weekday_narrow_wed),
        4 to stringResource(R.string.scheduled_weekday_narrow_thu),
        5 to stringResource(R.string.scheduled_weekday_narrow_fri),
        6 to stringResource(R.string.scheduled_weekday_narrow_sat),
        7 to stringResource(R.string.scheduled_weekday_narrow_sun),
    )
    val accent = if (isCoui) CouiPageDefaults.themeColors().accent else MiuixTheme.colorScheme.primary
    val inactiveBg = if (isCoui) {
        if (CouiTheme.isDark) androidx.compose.ui.graphics.Color(0x1FFFFFFF) else androidx.compose.ui.graphics.Color(0x0F000000)
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }
    val inactiveFg = if (isCoui) CouiPageDefaults.themeColors().title else MiuixTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach { (day, label) ->
            val selected = day in selectedDays
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent else inactiveBg)
                    .clickable(role = Role.Checkbox) { onToggleDay(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) androidx.compose.ui.graphics.Color.White else inactiveFg,
                )
            }
        }
    }
}

@Composable
private fun <T> QuickOptionPillsRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    isCoui: Boolean,
) {
    val accent = if (isCoui) CouiPageDefaults.themeColors().accent else MiuixTheme.colorScheme.primary
    val inactiveBg = if (isCoui) {
        if (CouiTheme.isDark) androidx.compose.ui.graphics.Color(0x1FFFFFFF) else androidx.compose.ui.graphics.Color(0x0F000000)
    } else {
        MiuixTheme.colorScheme.surfaceVariant
    }
    val inactiveFg = if (isCoui) CouiPageDefaults.themeColors().title else MiuixTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = if (isCoui) CouiPageDefaults.themeColors().summary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) accent else inactiveBg)
                        .clickable(role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else inactiveFg,
                    )
                }
            }
        }
    }
}
