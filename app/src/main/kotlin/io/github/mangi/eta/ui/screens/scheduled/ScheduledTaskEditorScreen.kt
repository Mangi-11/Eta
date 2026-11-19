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
    val scope = rememberCoroutineScope()
    val isEditing = !taskId.isNullOrBlank()
    val preset = remember(presetId) { ScheduledTaskPresets.find(presetId) }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)

    var loadedExisting by remember(taskId) { mutableStateOf<ScheduledTask?>(null) }
    var initialized by remember(taskId, presetId) { mutableStateOf(false) }

    var name by remember { mutableStateOf(preset?.defaultName.orEmpty()) }
    var prompt by remember { mutableStateOf(preset?.prompt.orEmpty()) }
    var triggerKind by remember { mutableStateOf(preset?.schedule?.kind ?: ScheduledTaskTriggerKind.DAILY) }
    var hour by remember { mutableIntStateOf(preset?.schedule?.hour ?: 9) }
    var minute by remember { mutableIntStateOf(preset?.schedule?.minute ?: 0) }
    var daysOfWeek by remember { mutableStateOf(preset?.schedule?.daysOfWeek ?: setOf(1, 2, 3, 4, 5)) }
    var intervalMinutes by remember { mutableIntStateOf(preset?.schedule?.intervalMinutes ?: 120) }
    var onceDelayMinutes by remember { mutableIntStateOf(60) }
    var onceTriggerAtMs by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    var cronExpression by remember { mutableStateOf("0 9 * * 1-5") }
    var deliveryMode by remember { mutableStateOf(preset?.deliveryMode ?: ScheduledTaskDeliveryMode.ALWAYS) }
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
    val scheduleError = remember(currentSchedule, previewNowMs) {
        ScheduledTaskScheduleCalculator.validateSchedule(currentSchedule, previewNowMs)
    }
    val nextRunPreview = remember(currentSchedule, previewNowMs, previewQuietHours, scheduleError) {
        if (scheduleError != null) {
            scheduleError
        } else {
            val next = ScheduledTaskScheduleCalculator.computeNextRunAt(
                schedule = currentSchedule,
                afterMs = previewNowMs,
                quietHours = previewQuietHours,
            )
            "下次运行：${ScheduledTaskScheduleCalculator.formatNextRunLabel(next, previewNowMs)}"
        }
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedCustom = remember(providers, selectedModelId) {
        selectedModelId?.takeIf { it.isNotBlank() }?.let { providers.enabledModel(it) }
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val followTitle = "跟随全局默认模型"
    val followSubtitle = primaryModelName?.let { "当前默认：$it" } ?: "使用全局设置中的默认模型"
    val modelSummary = when {
        resolvedCustom != null -> "${resolvedCustom.provider.name} / ${resolvedCustom.model.displayName}"
        !primaryModelName.isNullOrBlank() -> "跟随默认（$primaryModelName）"
        else -> followTitle
    }
    val effectiveCapabilities = remember(providers, resolvedCustom, primaryModelId) {
        val target = resolvedCustom ?: providers.enabledModel(primaryModelId)
        target?.let { (provider, model) -> RuntimeConfigRepository.reasoningCapabilities(provider, model) }
    }
    val effortOptions = remember(effectiveCapabilities) {
        listOf<ReasoningEffort?>(null) + effectiveCapabilities?.selectableEfforts.orEmpty()
    }
    val effortLabels = remember(effortOptions) {
        effortOptions.map { effort ->
            when (effort) {
                null -> "跟随全局默认"
                ReasoningEffort.DEFAULT -> "模型默认"
                else -> effort.displayName
            }
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
                            failure.message ?: "保存定时任务失败",
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

    val triggerTabs = remember {
        listOf(
            ScheduledTaskTriggerKind.DAILY to "每天",
            ScheduledTaskTriggerKind.WEEKDAYS to "工作日",
            ScheduledTaskTriggerKind.WEEKLY to "每周",
            ScheduledTaskTriggerKind.INTERVAL to "间隔",
            ScheduledTaskTriggerKind.ONCE to "一次性",
            ScheduledTaskTriggerKind.CRON to "Cron",
        )
    }
    val deliveryOptions = remember {
        listOf(
            ScheduledTaskDeliveryMode.ALWAYS to "每次完成后通知",
            ScheduledTaskDeliveryMode.ON_CHANGE to "仅发现变化时通知",
            ScheduledTaskDeliveryMode.SILENT to "静默归档不通知",
        )
    }
    val sessionOptions = remember {
        listOf(
            ScheduledTaskSessionMode.ISOLATED to "每次独立归档（主聊天保持干净）",
            ScheduledTaskSessionMode.DEDICATED to "固定专属频道（按时间续写）",
        )
    }
    val misfireOptions = remember {
        listOf(
            ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW to "3 小时内最多补跑最近 1 次",
            ScheduledTaskMisfirePolicy.SKIP to "错过即跳过",
        )
    }
    val timeoutChoices = remember { listOf(120, 300, 600, 900) }
    val timeoutLabels = remember { listOf("2 分钟", "5 分钟（推荐）", "10 分钟", "15 分钟") }
    val intervalChoices = remember { listOf(15, 30, 60, 120, 240, 360, 720) }
    val onceDelayChoices = remember { listOf(15, 30, 60, 120, 360, 1440) }

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
                        title = "执行模型",
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
                Group(title = "任务内容") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CouiTextField(
                            value = name,
                            onValueChange = { name = it.take(80) },
                            label = "任务名称",
                            placeholder = "例如：每日晚间简报",
                            singleLine = true,
                        )
                        CouiTextField(
                            value = prompt,
                            onValueChange = { prompt = it.take(8000) },
                            label = "要 Eta 做什么",
                            placeholder = "用自然语言描述目标、数据来源和期望输出格式",
                            singleLine = false,
                            minLines = 4,
                            maxLines = 10,
                        )
                    }
                }

                CouiSectionTitle("运行时间")
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
                                title = "触发时间",
                                value = ScheduledTaskScheduleCalculator.formatHourMinute(hour, minute),
                                onClick = openTimePicker,
                            )
                        }
                        ScheduledTaskTriggerKind.WEEKLY -> {
                            CouiPreference(
                                title = "触发时间",
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
                                title = "间隔周期",
                                options = intervalChoices.map { it to ScheduledTaskScheduleCalculator.formatInterval(it) },
                                selected = intervalMinutes,
                                onSelect = { intervalMinutes = it },
                                isCoui = true,
                            )
                        }
                        ScheduledTaskTriggerKind.ONCE -> {
                            QuickOptionPillsRow(
                                title = "从现在起延迟",
                                options = onceDelayChoices.map { mins ->
                                    mins to when {
                                        mins >= 1440 -> "${mins / 1440} 天后"
                                        mins >= 60 -> "${mins / 60} 小时后"
                                        else -> "${mins} 分钟后"
                                    }
                                },
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
                                    label = "5 段 Cron 表达式（分 时 日 月 周）",
                                    placeholder = "0 9 * * 1-5",
                                    singleLine = true,
                                )
                            }
                        }
                    }
                    Divider()
                    CouiPreference(
                        title = "排期预览",
                        summary = nextRunPreview,
                        showChevron = false,
                    )
                }

                Group(title = "通知方式") {
                    CouiDropdownPreference(
                        title = "结果通知",
                        items = deliveryOptions.map { it.second },
                        selectedIndex = deliveryOptions.indexOfFirst { it.first == deliveryMode }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx -> deliveryMode = deliveryOptions[idx].first },
                    )
                }

                Spacer(modifier = Modifier.height(CouiPageDefaults.groupSpacing))
                Group {
                    CouiPreference(
                        title = if (showAdvanced) "收起更多设置" else "更多设置（模型、权限、会话与防打扰）",
                        summary = if (showAdvanced) null else "当前默认已针对锁屏后台静默执行优化",
                        onClick = { showAdvanced = !showAdvanced },
                    )
                }

                if (showAdvanced) {
                    Group(title = "模型与思考") {
                        CouiPreference(
                            title = "执行模型",
                            summary = modelSummary,
                            onClick = { showModelSheet = true },
                        )
                        if (effortOptions.size > 1) {
                            Divider()
                            CouiDropdownPreference(
                                title = "思考档位",
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                onSelectedIndexChange = { idx ->
                                    selectedReasoningEffort = effortOptions[idx]?.wireValue
                                },
                            )
                        }
                    }

                    Group(title = "单任务能力边界") {
                        CouiSwitchPreference(
                            title = "网页搜索与内置浏览器",
                            summary = "锁屏时也能用带登录态的共享 WebView 抓取网页",
                            checked = capabilities.browserTools,
                            onCheckedChange = { capabilities = capabilities.copy(browserTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "设备信息读取",
                            summary = "通知历史、日历、应用使用、系统状态与个人记录",
                            checked = capabilities.deviceReadTools,
                            onCheckedChange = { capabilities = capabilities.copy(deviceReadTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "设备状态控制",
                            summary = "允许调整系统设置、音量、闹钟等写操作",
                            checked = capabilities.deviceActionTools,
                            onCheckedChange = { capabilities = capabilities.copy(deviceActionTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "终端、文件与 Linux",
                            summary = "运行 Android/Linux 命令与工作区文件读写",
                            checked = capabilities.terminalTools,
                            onCheckedChange = { capabilities = capabilities.copy(terminalTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "MCP 远程工具",
                            summary = "调用已在 Eta 中启用的 MCP 服务器工具",
                            checked = capabilities.mcpTools,
                            onCheckedChange = { capabilities = capabilities.copy(mcpTools = it) },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "应用界面操作（GUI）",
                            summary = "默认关闭；支持后台虚拟屏的设备优先静默运行，否则锁屏时发提醒待解锁",
                            checked = capabilities.guiTools,
                            onCheckedChange = { capabilities = capabilities.copy(guiTools = it) },
                        )
                    }

                    Group(title = "会话与保护策略") {
                        CouiDropdownPreference(
                            title = "结果归属",
                            items = sessionOptions.map { it.second },
                            selectedIndex = sessionOptions.indexOfFirst { it.first == sessionMode }.coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> sessionMode = sessionOptions[idx].first },
                        )
                        Divider()
                        CouiDropdownPreference(
                            title = "错过触发时",
                            items = misfireOptions.map { it.second },
                            selectedIndex = misfireOptions.indexOfFirst { it.first == misfirePolicy }.coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> misfirePolicy = misfireOptions[idx].first },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "夜间免打扰（23:00 – 07:30）",
                            summary = "命中免打扰时段的周期触发将被自动跳过",
                            checked = quietHoursEnabled,
                            onCheckedChange = { quietHoursEnabled = it },
                        )
                        Divider()
                        CouiDropdownPreference(
                            title = "单次最长运行时间",
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
                        title = "执行模型",
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
                    SmallTitle(text = "任务内容")
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
                                label = "任务名称（如：每日晚间简报）",
                                singleLine = true,
                            )
                            TextField(
                                value = prompt,
                                onValueChange = { prompt = it.take(8000) },
                                label = "要 Eta 做什么",
                                minLines = 4,
                                maxLines = 10,
                            )
                        }
                    }
                }

                item(key = "schedule_section") {
                    SmallTitle(text = "运行时间")
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
                                    title = "触发时间",
                                    summary = ScheduledTaskScheduleCalculator.formatHourMinute(hour, minute),
                                    onClick = openTimePicker,
                                )
                            }
                            ScheduledTaskTriggerKind.WEEKLY -> {
                                ArrowPreference(
                                    title = "触发时间",
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
                                    title = "间隔周期",
                                    options = intervalChoices.map { it to ScheduledTaskScheduleCalculator.formatInterval(it) },
                                    selected = intervalMinutes,
                                    onSelect = { intervalMinutes = it },
                                    isCoui = false,
                                )
                            }
                            ScheduledTaskTriggerKind.ONCE -> {
                                QuickOptionPillsRow(
                                    title = "从现在起延迟",
                                    options = onceDelayChoices.map { mins ->
                                        mins to when {
                                            mins >= 1440 -> "${mins / 1440} 天后"
                                            mins >= 60 -> "${mins / 60} 小时后"
                                            else -> "${mins} 分钟后"
                                        }
                                    },
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
                                        label = "5 段 Cron 表达式（分 时 日 月 周）",
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
                    SmallTitle(text = "通知方式")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        GlassDropdownPreference(
                            title = "结果通知",
                            items = deliveryOptions.map { it.second },
                            selectedIndex = deliveryOptions.indexOfFirst { it.first == deliveryMode }.coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> deliveryMode = deliveryOptions[idx].first },
                            backdrop = currentGlassOverlayBackdrop(),
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
                            title = if (showAdvanced) "收起更多设置" else "更多设置（模型、权限、会话与防打扰）",
                            summary = if (showAdvanced) null else "当前默认已针对锁屏后台静默执行优化",
                            onClick = { showAdvanced = !showAdvanced },
                        )
                    }
                }

                if (showAdvanced) {
                    item(key = "advanced_model") {
                        SmallTitle(text = "模型与思考")
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            ArrowPreference(
                                title = "执行模型",
                                summary = modelSummary,
                                onClick = { showModelSheet = true },
                            )
                            if (effortOptions.size > 1) {
                                GlassDropdownPreference(
                                    title = "思考档位",
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
                        SmallTitle(text = "单任务能力边界")
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            SwitchPreference(
                                title = "网页搜索与内置浏览器",
                                summary = "锁屏时也能用共享 WebView 抓取网页",
                                checked = capabilities.browserTools,
                                onCheckedChange = { capabilities = capabilities.copy(browserTools = it) },
                            )
                            SwitchPreference(
                                title = "设备信息读取",
                                summary = "通知历史、日历、应用使用与系统状态",
                                checked = capabilities.deviceReadTools,
                                onCheckedChange = { capabilities = capabilities.copy(deviceReadTools = it) },
                            )
                            SwitchPreference(
                                title = "设备状态控制",
                                summary = "允许调整系统设置、音量、闹钟等写操作",
                                checked = capabilities.deviceActionTools,
                                onCheckedChange = { capabilities = capabilities.copy(deviceActionTools = it) },
                            )
                            SwitchPreference(
                                title = "终端、文件与 Linux",
                                summary = "运行 Android/Linux 命令与文件读写",
                                checked = capabilities.terminalTools,
                                onCheckedChange = { capabilities = capabilities.copy(terminalTools = it) },
                            )
                            SwitchPreference(
                                title = "MCP 远程工具",
                                summary = "调用已启用的 MCP 工具",
                                checked = capabilities.mcpTools,
                                onCheckedChange = { capabilities = capabilities.copy(mcpTools = it) },
                            )
                            SwitchPreference(
                                title = "应用界面操作（GUI）",
                                summary = "默认关闭；支持后台虚拟屏优先静默，否则锁屏发提醒待解锁",
                                checked = capabilities.guiTools,
                                onCheckedChange = { capabilities = capabilities.copy(guiTools = it) },
                            )
                        }
                    }

                    item(key = "advanced_session") {
                        SmallTitle(text = "会话与保护策略")
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            GlassDropdownPreference(
                                title = "结果归属",
                                items = sessionOptions.map { it.second },
                                selectedIndex = sessionOptions.indexOfFirst { it.first == sessionMode }.coerceAtLeast(0),
                                onSelectedIndexChange = { idx -> sessionMode = sessionOptions[idx].first },
                                backdrop = currentGlassOverlayBackdrop(),
                            )
                            GlassDropdownPreference(
                                title = "错过触发时",
                                items = misfireOptions.map { it.second },
                                selectedIndex = misfireOptions.indexOfFirst { it.first == misfirePolicy }.coerceAtLeast(0),
                                onSelectedIndexChange = { idx -> misfirePolicy = misfireOptions[idx].first },
                                backdrop = currentGlassOverlayBackdrop(),
                            )
                            SwitchPreference(
                                title = "夜间免打扰（23:00 – 07:30）",
                                summary = "命中免打扰时段的周期触发将被跳过",
                                checked = quietHoursEnabled,
                                onCheckedChange = { quietHoursEnabled = it },
                            )
                            GlassDropdownPreference(
                                title = "单次最长运行时间",
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
    val labels = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
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
