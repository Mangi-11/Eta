package io.github.mangi.eta.agent.scheduler

import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务模型工具执行器：处理常规对话中的 `scheduled_tasks` 管理调用，
 * 以及定时任务执行期的 `scheduled_task_update_memo` / `scheduled_task_report` 自作用域调用。
 */
internal class ScheduledTaskToolExecutor(
    context: Context,
    private val activeRunContext: ScheduledTaskRunContext? = null,
) {
    private val appContext = context.applicationContext

    fun executeManage(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (activeRunContext != null) {
            return errorJson("FORBIDDEN_IN_SCHEDULED_RUN", "定时任务执行期间不允许创建或修改其他定时任务")
        }
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse {
            return errorJson("INVALID_ARGUMENTS", "工具参数不是合法的 JSON 对象")
        }
        val action = args.optString("action").trim().lowercase()
        return runBlocking {
            when (action) {
                "list" -> handleList()
                "create" -> handleCreate(args)
                "update" -> handleUpdate(args)
                "delete" -> handleDelete(args)
                "run_now" -> handleRunNow(args)
                else -> errorJson("INVALID_ACTION", "不支持的 action：$action")
            }
        }
    }

    fun executeSelfRun(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val ctx = activeRunContext
            ?: return errorJson("NOT_IN_SCHEDULED_RUN", "当前不在定时任务执行上下文中")
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse {
            return errorJson("INVALID_ARGUMENTS", "工具参数不是合法的 JSON 对象")
        }
        return when (call.name) {
            ScheduledTaskToolCatalog.TOOL_UPDATE_MEMO -> {
                val content = args.optString("content", "")
                val saved = runBlocking {
                    ScheduledTaskRepository.updateMemo(
                        taskId = ctx.taskId,
                        memo = content,
                        context = appContext,
                    )
                }
                if (!saved) {
                    errorJson("TASK_NOT_FOUND", "当前定时任务已不存在")
                } else {
                    okJson(
                        JSONObject()
                            .put("saved_chars", content.trim().take(ScheduledTask.MAX_MEMO_CHARS).length)
                            .put("message", "任务小记事本已更新")
                    )
                }
            }
            ScheduledTaskToolCatalog.TOOL_REPORT -> {
                val outcomeRaw = args.optString("outcome").trim().lowercase()
                val summary = args.optString("summary").trim().take(300)
                val outcome = when (outcomeRaw) {
                    "deliver" -> ScheduledTaskReportedOutcome.Outcome.DELIVER
                    "no_change" -> ScheduledTaskReportedOutcome.Outcome.NO_CHANGE
                    else -> return errorJson("INVALID_OUTCOME", "outcome 仅支持 deliver 或 no_change")
                }
                ScheduledTaskRunReportRegistry.record(
                    runId = ctx.runId,
                    outcome = ScheduledTaskReportedOutcome(outcome = outcome, summary = summary),
                )
                okJson(
                    JSONObject()
                        .put("outcome", outcomeRaw)
                        .put("summary", summary)
                )
            }
            else -> errorJson("UNKNOWN_TOOL", "未知的定时任务工具：${call.name}")
        }
    }

    private suspend fun handleList(): AgentModelClient.ToolResult {
        val nowMs = System.currentTimeMillis()
        val tasks = ScheduledTaskRepository.allTasks(appContext)
        val health = ScheduledTaskHealthChecker.inspect(appContext)
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(taskToSummaryJson(task, nowMs))
        }
        return okJson(
            JSONObject()
                .put("count", tasks.size)
                .put("tasks", array)
                .put(
                    "background_health",
                    JSONObject()
                        .put("exact_alarm_granted", health.canScheduleExactAlarms)
                        .put("battery_unrestricted", health.ignoringBatteryOptimizations)
                        .put("notifications_enabled", health.notificationsEnabled),
                )
        )
    }

    private suspend fun handleCreate(args: JSONObject): AgentModelClient.ToolResult {
        val nowMs = System.currentTimeMillis()
        val name = args.optString("name").trim()
        val prompt = args.optString("prompt").trim()
        if (name.isEmpty()) return errorJson("MISSING_NAME", "请提供任务名称 name")
        if (prompt.isEmpty()) return errorJson("MISSING_PROMPT", "请提供任务指令 prompt")

        val schedule = parseScheduleFromArgs(args, base = ScheduledTaskSchedule(), nowMs = nowMs)
            ?: return errorJson("INVALID_SCHEDULE", "排期参数无效，请检查触发类型与时间字段")
        val validationError = ScheduledTaskScheduleCalculator.validateSchedule(schedule, nowMs)
        if (validationError != null) {
            return errorJson("INVALID_SCHEDULE", validationError)
        }

        val deliveryMode = if (args.has("delivery_mode")) {
            ScheduledTaskDeliveryMode.fromWireValue(args.optString("delivery_mode"))
        } else {
            ScheduledTaskDeliveryMode.ALWAYS
        }
        val sessionMode = if (args.has("session_mode")) {
            ScheduledTaskSessionMode.fromWireValue(args.optString("session_mode"))
        } else {
            ScheduledTaskSessionMode.ISOLATED
        }
        val allowGui = args.optBoolean("allow_gui", false)
        val showRunningNotification = args.optBoolean("show_running_notification", false)
        val enabled = args.optBoolean("enabled", true)
        val timeoutSeconds = args.optInt("timeout_seconds", ScheduledTask.DEFAULT_TIMEOUT_SECONDS)

        val saved = ScheduledTaskRepository.saveTask(
            task = ScheduledTask(
                id = UUID.randomUUID().toString(),
                name = name,
                prompt = prompt,
                enabled = enabled,
                schedule = schedule,
                deliveryMode = deliveryMode,
                showRunningNotification = showRunningNotification,
                sessionMode = sessionMode,
                capabilities = ScheduledTaskCapabilities(guiTools = allowGui),
                timeoutSeconds = timeoutSeconds,
                createdAt = nowMs,
                updatedAt = nowMs,
            ),
            nowMs = nowMs,
            rescheduleAlarm = true,
            context = appContext,
        )
        val health = ScheduledTaskHealthChecker.inspect(appContext)
        return okJson(
            JSONObject()
                .put("task", taskToSummaryJson(saved, nowMs))
                .put("exact_alarm_granted", health.canScheduleExactAlarms)
                .put("battery_unrestricted", health.ignoringBatteryOptimizations)
        )
    }

    private suspend fun handleUpdate(args: JSONObject): AgentModelClient.ToolResult {
        val taskId = args.optString("task_id").trim()
        if (taskId.isEmpty()) return errorJson("MISSING_TASK_ID", "请提供 task_id")
        val existing = ScheduledTaskRepository.getTask(taskId, appContext)
            ?: return errorJson("TASK_NOT_FOUND", "未找到 ID 为 $taskId 的定时任务")

        val nowMs = System.currentTimeMillis()
        val hasSchedulePatch = args.has("trigger_kind") || args.has("hour") || args.has("minute") ||
            args.has("days_of_week") || args.has("interval_minutes") || args.has("delay_minutes") ||
            args.has("trigger_at") || args.has("cron")
        val schedule = if (hasSchedulePatch) {
            val parsed = parseScheduleFromArgs(args, base = existing.schedule, nowMs = nowMs)
                ?: return errorJson("INVALID_SCHEDULE", "排期参数无效")
            val validationError = ScheduledTaskScheduleCalculator.validateSchedule(parsed, nowMs)
            if (validationError != null) return errorJson("INVALID_SCHEDULE", validationError)
            parsed
        } else {
            existing.schedule
        }

        val updated = existing.copy(
            name = args.optString("name", "").trim().ifEmpty { existing.name },
            prompt = args.optString("prompt", "").trim().ifEmpty { existing.prompt },
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.enabled,
            schedule = schedule,
            deliveryMode = if (args.has("delivery_mode")) {
                ScheduledTaskDeliveryMode.fromWireValue(args.optString("delivery_mode"))
            } else existing.deliveryMode,
            showRunningNotification = if (args.has("show_running_notification")) {
                args.optBoolean("show_running_notification")
            } else existing.showRunningNotification,
            sessionMode = if (args.has("session_mode")) {
                ScheduledTaskSessionMode.fromWireValue(args.optString("session_mode"))
            } else existing.sessionMode,
            capabilities = if (args.has("allow_gui")) {
                existing.capabilities.copy(guiTools = args.optBoolean("allow_gui"))
            } else existing.capabilities,
            timeoutSeconds = if (args.has("timeout_seconds")) {
                args.optInt("timeout_seconds", existing.timeoutSeconds)
            } else existing.timeoutSeconds,
        )
        val saved = ScheduledTaskRepository.saveTask(
            task = updated,
            nowMs = nowMs,
            rescheduleAlarm = true,
            context = appContext,
        )
        return okJson(JSONObject().put("task", taskToSummaryJson(saved, nowMs)))
    }

    private suspend fun handleDelete(args: JSONObject): AgentModelClient.ToolResult {
        val taskId = args.optString("task_id").trim()
        if (taskId.isEmpty()) return errorJson("MISSING_TASK_ID", "请提供 task_id")
        val existing = ScheduledTaskRepository.getTask(taskId, appContext)
            ?: return errorJson("TASK_NOT_FOUND", "未找到 ID 为 $taskId 的定时任务")
        ScheduledTaskRunner.cancelRunningTask(appContext, existing.id)
        ScheduledTaskRepository.deleteTask(existing.id, rescheduleAlarm = true, context = appContext)
        return okJson(
            JSONObject()
                .put("deleted_id", existing.id)
                .put("deleted_name", existing.name)
        )
    }

    private suspend fun handleRunNow(args: JSONObject): AgentModelClient.ToolResult {
        val taskId = args.optString("task_id").trim()
        if (taskId.isEmpty()) return errorJson("MISSING_TASK_ID", "请提供 task_id")
        return when (ScheduledTaskRunner.triggerManualRun(appContext, taskId)) {
            ScheduledTaskRunner.ManualTriggerResult.Started -> okJson(
                JSONObject()
                    .put("task_id", taskId)
                    .put("status", "started")
                    .put("message", "已开始后台试跑，完成后将发送结果通知并记入运行时间线")
            )
            ScheduledTaskRunner.ManualTriggerResult.AlreadyRunning -> errorJson(
                "ALREADY_RUNNING",
                "该定时任务正在运行中",
            )
            ScheduledTaskRunner.ManualTriggerResult.NotFound -> errorJson(
                "TASK_NOT_FOUND",
                "未找到 ID 为 $taskId 的定时任务",
            )
        }
    }

    private fun parseScheduleFromArgs(
        args: JSONObject,
        base: ScheduledTaskSchedule,
        nowMs: Long,
    ): ScheduledTaskSchedule? {
        val kind = if (args.has("trigger_kind")) {
            ScheduledTaskTriggerKind.fromWireValue(args.optString("trigger_kind"))
        } else {
            base.kind
        }
        val hour = if (args.has("hour")) args.optInt("hour", base.hour) else base.hour
        val minute = if (args.has("minute")) args.optInt("minute", base.minute) else base.minute
        val daysOfWeek = if (args.has("days_of_week")) {
            val arr = args.optJSONArray("days_of_week") ?: return null
            buildSet {
                for (i in 0 until arr.length()) {
                    val day = arr.optInt(i, -1)
                    if (day in 1..7) add(day)
                }
            }
        } else {
            base.daysOfWeek
        }
        val intervalMinutes = if (args.has("interval_minutes")) {
            args.optInt("interval_minutes", base.intervalMinutes)
        } else {
            base.intervalMinutes
        }
        val cronExpression = if (args.has("cron")) {
            args.optString("cron").trim()
        } else {
            base.cronExpression
        }
        val triggerAt = when {
            args.has("delay_minutes") -> {
                val delay = args.optInt("delay_minutes", -1)
                if (delay <= 0) return null
                nowMs + delay * 60_000L
            }
            args.has("trigger_at") -> {
                ScheduledTaskScheduleCalculator.parseIsoOrLocalDateTime(args.optString("trigger_at"))
                    ?: return null
            }
            else -> base.triggerAtEpochMs
        }
        return base.copy(
            kind = kind,
            triggerAtEpochMs = triggerAt,
            hour = hour,
            minute = minute,
            daysOfWeek = daysOfWeek,
            intervalMinutes = intervalMinutes,
            cronExpression = cronExpression,
        )
    }

    private fun taskToSummaryJson(task: ScheduledTask, nowMs: Long): JSONObject = JSONObject()
        .put("id", task.id)
        .put("name", task.name)
        .put("enabled", task.enabled)
        .put("running", ScheduledTaskRunner.isTaskRunning(task.id))
        .put("schedule_text", ScheduledTaskScheduleCalculator.describeSchedule(task.schedule))
        .put("next_run_text", ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, nowMs))
        .put("delivery_mode", task.deliveryMode.wireValue)
        .put("show_running_notification", task.showRunningNotification)
        .put("session_mode", task.sessionMode.wireValue)
        .put("allow_gui", task.capabilities.guiTools)
        .put("last_status", task.lastStatus?.wireValue ?: "")
        .put("last_summary", task.lastSummary)
        .put("auto_disabled_reason", task.autoDisabledReason ?: "")

    private fun okJson(payload: JSONObject): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(payload.put("ok", true).toString())

    private fun errorJson(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString()
        )
}
