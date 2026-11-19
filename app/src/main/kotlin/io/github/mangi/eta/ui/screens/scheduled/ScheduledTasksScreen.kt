package io.github.mangi.eta.ui.screens.scheduled

import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.input.CouiHapticFeedback
import com.itos.coui.input.performCouiHapticFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.scheduler.ScheduledTaskHealthChecker
import io.github.mangi.eta.agent.scheduler.ScheduledTaskHealthSnapshot
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskPreset
import io.github.mangi.eta.data.model.ScheduledTaskPresets
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixTopBarActionButton
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
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
    val scope = rememberCoroutineScope()
    val tasks by remember(context) { ScheduledTaskRepository.observeTasks(context) }
        .collectAsState(initial = emptyList())
    val activeTaskIds by ScheduledTaskRunner.activeTaskIds.collectAsState()
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
                Toast.makeText(context, "更新定时任务开关失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val onFixHealth: () -> Unit = {
        openPrimaryHealthFix(context, health)
    }

    val title = stringResource(R.string.route_scheduled_tasks)
    val createLabel = stringResource(R.string.route_scheduled_task_new)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiLazyScaffoldPage(
                title = title,
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = CouiIcons.Outlined.Add,
                        contentDescription = createLabel,
                        onClick = { onNavigate(AppRoute.ScheduledTaskEditor()) },
                    )
                },
            ) {
                if (!health.allHealthy) {
                    item(key = "health_banner") {
                        CouiHealthBannerCard(
                            health = health,
                            onFix = onFixHealth,
                        )
                    }
                }
                if (tasks.isEmpty()) {
                    item(key = "presets_group") {
                        Group(title = "快速开始") {
                            ScheduledTaskPresets.all.forEachIndexed { index, preset ->
                                if (index > 0) Divider()
                                CouiPresetRow(
                                    preset = preset,
                                    onClick = { onNavigate(AppRoute.ScheduledTaskEditor(presetId = preset.id)) },
                                )
                            }
                        }
                    }
                    item(key = "empty_hint") {
                        Footer(
                            text = "定时任务会在后台静默运行，只在有需要你关注的结果时通知；你也可以直接在聊天中对 Eta 说「每天晚上十点帮我总结今天的重要通知」。",
                        )
                    }
                } else {
                    item(key = "tasks_header") {
                        val enabledCount = tasks.count { it.enabled }
                        CouiSectionTitle("全部任务 · $enabledCount/${tasks.size} 已开启")
                    }
                    itemsIndexed(
                        items = tasks,
                        key = { _, item -> item.id },
                    ) { _, task ->
                        CouiScheduledTaskCard(
                            task = task,
                            isRunning = task.id in activeTaskIds,
                            nowMs = nowMs,
                            onClick = { onNavigate(AppRoute.ScheduledTaskDetail(task.id)) },
                            onToggle = { checked -> onToggleEnabled(task, checked) },
                        )
                    }
                }
                item(key = "bottom_spacer") {
                    CouiPageBottomSpacer()
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = title,
                onBack = onBack,
                actions = {
                    MiuixTopBarActionButton(
                        icon = MiuixIcons.Glass.Add,
                        contentDescription = createLabel,
                        onClick = { onNavigate(AppRoute.ScheduledTaskEditor()) },
                    )
                },
            ) {
                if (!health.allHealthy) {
                    item(key = "health_banner") {
                        MiuixHealthBannerCard(
                            health = health,
                            onFix = onFixHealth,
                        )
                    }
                }
                if (tasks.isEmpty()) {
                    item(key = "empty_intro") {
                        SmallTitle(text = "快速开始")
                    }
                    item(key = "empty_hint") {
                        Text(
                            text = "定时任务会在后台静默运行，只在有需要你关注的结果时通知；也可以直接在聊天里对 Eta 说「每天晚上十点帮我总结今天的重要通知」。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                    item(key = "presets_card") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ScheduledTaskPresets.all.forEach { preset ->
                                ArrowPreference(
                                    title = preset.title,
                                    summary = "${ScheduledTaskScheduleCalculator.describeSchedule(preset.schedule)} · ${preset.subtitle}",
                                    onClick = { onNavigate(AppRoute.ScheduledTaskEditor(presetId = preset.id)) },
                                )
                            }
                        }
                    }
                } else {
                    item(key = "tasks_header") {
                        val enabledCount = tasks.count { it.enabled }
                        SmallTitle(text = "全部任务 · $enabledCount/${tasks.size} 已开启")
                    }
                    itemsIndexed(
                        items = tasks,
                        key = { _, item -> item.id },
                    ) { _, task ->
                        MiuixScheduledTaskCard(
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
    }
}

@Composable
private fun CouiHealthBannerCard(
    health: ScheduledTaskHealthSnapshot,
    onFix: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val (title, summary, actionText) = healthGuidance(health)
    Group {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CouiText(
                text = title,
                kind = CouiTextKind.ItemTitle,
                color = colors.title,
            )
            CouiText(
                text = summary,
                kind = CouiTextKind.ItemSummary,
                color = colors.summary,
            )
            CouiButton(
                text = actionText,
                role = CouiButtonRole.Secondary,
                onClick = onFix,
            )
        }
    }
}

@Composable
private fun MiuixHealthBannerCard(
    health: ScheduledTaskHealthSnapshot,
    onFix: () -> Unit,
) {
    val (title, summary, actionText) = healthGuidance(health)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            )
        }
    }
}

@Composable
private fun CouiPresetRow(
    preset: ScheduledTaskPreset,
    onClick: () -> Unit,
) {
    val scheduleText = ScheduledTaskScheduleCalculator.describeSchedule(preset.schedule)
    CouiPreference(
        title = preset.title,
        summary = "$scheduleText · ${preset.subtitle}",
        onClick = onClick,
    )
}

@Composable
private fun CouiScheduledTaskCard(
    task: ScheduledTask,
    isRunning: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val scheduleLine = buildScheduleMetaLine(task, nowMs)
    val statusLine = buildStatusMetaLine(task, isRunning, nowMs)
    val dotColor = ScheduledTaskUiFormatting.statusDotColor(
        status = task.lastStatus,
        runningNow = isRunning,
        autoDisabled = !task.autoDisabledReason.isNullOrBlank(),
    )

    Group {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .couiButtonFeedback(dark, interaction, enabled = true)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CouiText(
                    text = task.name.ifBlank { "未命名任务" },
                    kind = CouiTextKind.ItemTitle,
                    color = if (task.enabled) colors.title else colors.summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CouiText(
                    text = scheduleLine,
                    kind = CouiTextKind.ItemSummary,
                    color = colors.summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    CouiText(
                        text = statusLine,
                        kind = CouiTextKind.ItemSummary,
                        color = colors.summary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            CouiSwitch(
                checked = task.enabled,
                onCheckedChange = { next ->
                    view.performCouiHapticFeedback(CouiHapticFeedback.Switch)
                    onToggle(next)
                },
                colors = CouiSwitchDefaults.colors(accent = colors.accent, dark = dark),
            )
        }
    }
}

@Composable
private fun MiuixScheduledTaskCard(
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
            title = task.name.ifBlank { "未命名任务" },
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

private fun buildScheduleMetaLine(task: ScheduledTask, nowMs: Long): String {
    val scheduleText = ScheduledTaskScheduleCalculator.describeSchedule(task.schedule)
    val deliveryText = ScheduledTaskUiFormatting.deliveryBadge(task.deliveryMode)
    val nextText = if (task.enabled && task.nextRunAt != null) {
        " · 下次 ${ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs)}"
    } else ""
    return "$scheduleText · $deliveryText$nextText"
}

private fun buildStatusMetaLine(task: ScheduledTask, isRunning: Boolean, nowMs: Long): String {
    if (isRunning) return "正在后台运行…"
    if (!task.autoDisabledReason.isNullOrBlank()) return task.autoDisabledReason
    if (task.lastStatus == null) {
        return if (task.enabled) "等待首次触发" else "已暂停"
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

private fun healthGuidance(health: ScheduledTaskHealthSnapshot): Triple<String, String, String> = when {
    !health.ignoringBatteryOptimizations -> Triple(
        "建议允许后台无限制耗电",
        "Android 深度休眠时会对受限应用在 10 秒后切断后台网络，开启「电池无限制」可确保定时推理与网页巡检稳定完成。",
        "前往开启",
    )
    !health.canScheduleExactAlarms -> Triple(
        "精确闹钟权限未开启",
        "未开启精确闹钟时，定时任务可能延迟数分钟触发；开启后可准点唤醒执行。",
        "授权精确闹钟",
    )
    else -> Triple(
        "系统通知未开启",
        "定时任务仍会在后台执行，但无法推送结果摘要和失败告警通知。",
        "开启通知",
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
