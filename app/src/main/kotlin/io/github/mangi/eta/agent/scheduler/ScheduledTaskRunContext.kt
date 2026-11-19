package io.github.mangi.eta.agent.scheduler

import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * 定时任务传递给 Runtime 的受控运行上下文（嵌入在 `AgentExternalArchivePayload.adapterPayload` 中）。
 *
 * 保持 `AgentRuntimeWire` 协议完全向后兼容，不新增破坏性顶层字段。
 */
internal data class ScheduledTaskRunContext(
    val taskId: String,
    val taskName: String,
    val runId: String,
    val triggerSource: ScheduledTaskTriggerSource,
    val deliveryMode: ScheduledTaskDeliveryMode,
    val showRunningNotification: Boolean = false,
    val sessionMode: ScheduledTaskSessionMode,
    val capabilities: ScheduledTaskCapabilities,
    val memo: String,
    val scheduledForAt: Long?,
    val startedAt: Long,
) {
    fun toAdapterPayload(): JSONObject = JSONObject()
        .put(KEY_MARKER, true)
        .put(KEY_TASK_ID, taskId)
        .put(KEY_TASK_NAME, taskName)
        .put(KEY_RUN_ID, runId)
        .put(KEY_TRIGGER_SOURCE, triggerSource.wireValue)
        .put(KEY_DELIVERY_MODE, deliveryMode.wireValue)
        .put(KEY_SHOW_RUNNING_NOTIFICATION, showRunningNotification)
        .put(KEY_SESSION_MODE, sessionMode.wireValue)
        .put(KEY_CAP_BROWSER, capabilities.browserTools)
        .put(KEY_CAP_DEVICE_READ, capabilities.deviceReadTools)
        .put(KEY_CAP_DEVICE_ACTION, capabilities.deviceActionTools)
        .put(KEY_CAP_TERMINAL, capabilities.terminalTools)
        .put(KEY_CAP_MCP, capabilities.mcpTools)
        .put(KEY_CAP_GUI, capabilities.guiTools)
        .put(KEY_MEMO, memo.take(ScheduledTask.MAX_MEMO_CHARS))
        .put(KEY_STARTED_AT, startedAt)
        .also { json ->
            scheduledForAt?.let { json.put(KEY_SCHEDULED_FOR_AT, it) }
        }

    /** 构建注入模型系统提示的无人值守运行契约与任务小记事本。 */
    fun buildSystemGuidance(zone: ZoneId = ZoneId.systemDefault()): String {
        val nowText = Instant.ofEpochMilli(startedAt).atZone(zone).format(DATETIME_FORMATTER)
        val triggerDesc = when (triggerSource) {
            ScheduledTaskTriggerSource.SCHEDULE -> "按计划准点自动触发"
            ScheduledTaskTriggerSource.MANUAL -> "由用户手动点击「立即试跑」触发"
            ScheduledTaskTriggerSource.MISFIRE_CATCHUP -> {
                val scheduledText = scheduledForAt?.let {
                    Instant.ofEpochMilli(it).atZone(zone).format(DATETIME_FORMATTER)
                }.orEmpty()
                "因设备先前休眠/离线，当前为延迟补跑（原计划时间：$scheduledText）"
            }
        }
        val deliveryDesc = when (deliveryMode) {
            ScheduledTaskDeliveryMode.ALWAYS ->
                "本任务为「每次完成后通知」：请直接给出简洁清晰、重点前置的最终结果。"
            ScheduledTaskDeliveryMode.ON_CHANGE ->
                "本任务为「仅发现变化时通知」：若与任务小记事本中的上次记录相比没有任何值得用户关注的变化或新情况，" +
                    "请调用 scheduled_task_report(outcome=\"no_change\", summary=\"简短说明无变化\") 并结束回复，系统将保持静默不打扰用户；" +
                    "若发现实质变化或需要提醒的事项，请先按需更新任务小记事本，再调用 scheduled_task_report(outcome=\"deliver\", summary=\"1到2句通知摘要\") 并输出完整结论。"
            ScheduledTaskDeliveryMode.SILENT ->
                "本任务为「静默归档」：仅在后台完成工作与记录，系统不会发送常规完成通知。"
        }
        return buildString {
            append("【无人值守定时任务模式】\n")
            append("当前正在后台自动执行用户预设的定时任务「").append(taskName).append("」（触发方式：").append(triggerDesc).append("；当前本地时间：").append(nowText).append("）。\n")
            append("执行规则：\n")
            append("1. 这是无人值守运行，没有人在屏幕前回复追问。请直接交付最终结论，不要只给计划、不要说“好的我来查”、不要向用户反问澄清；确实缺少关键条件或权限时，直接简洁说明阻塞原因。\n")
            append("2. ").append(deliveryDesc).append('\n')
            append("3. 可使用 scheduled_task_update_memo 更新本任务的私有小记事本（用于保存上次检查游标、已播报条目或关键状态，供下次定时运行对比，严禁写入密钥或敏感凭据）。\n")
            if (!capabilities.guiTools) {
                append("4. 本任务未启用应用界面（GUI）操作，请仅使用当前公开的后台工具完成任务。\n")
            }
            if (memo.isNotBlank()) {
                append("\n<scheduled_task_memo>\n")
                append(memo.take(ScheduledTask.MAX_MEMO_CHARS))
                append("\n</scheduled_task_memo>")
            }
        }
    }

    companion object {
        private const val KEY_MARKER = "scheduled_task"
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_TRIGGER_SOURCE = "trigger_source"
        private const val KEY_DELIVERY_MODE = "delivery_mode"
        private const val KEY_SHOW_RUNNING_NOTIFICATION = "show_running_notification"
        private const val KEY_SESSION_MODE = "session_mode"
        private const val KEY_CAP_BROWSER = "cap_browser"
        private const val KEY_CAP_DEVICE_READ = "cap_device_read"
        private const val KEY_CAP_DEVICE_ACTION = "cap_device_action"
        private const val KEY_CAP_TERMINAL = "cap_terminal"
        private const val KEY_CAP_MCP = "cap_mcp"
        private const val KEY_CAP_GUI = "cap_gui"
        private const val KEY_MEMO = "memo"
        private const val KEY_SCHEDULED_FOR_AT = "scheduled_for_at"
        private const val KEY_STARTED_AT = "started_at"

        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)", Locale.SIMPLIFIED_CHINESE)

        fun fromAdapterPayload(json: JSONObject?): ScheduledTaskRunContext? {
            if (json == null || !json.optBoolean(KEY_MARKER, false)) return null
            val taskId = json.optString(KEY_TASK_ID).trim()
            val runId = json.optString(KEY_RUN_ID).trim()
            if (taskId.isEmpty() || runId.isEmpty()) return null
            return ScheduledTaskRunContext(
                taskId = taskId,
                taskName = json.optString(KEY_TASK_NAME),
                runId = runId,
                triggerSource = ScheduledTaskTriggerSource.fromWireValue(json.optString(KEY_TRIGGER_SOURCE)),
                deliveryMode = ScheduledTaskDeliveryMode.fromWireValue(json.optString(KEY_DELIVERY_MODE)),
                showRunningNotification = json.optBoolean(KEY_SHOW_RUNNING_NOTIFICATION, false),
                sessionMode = ScheduledTaskSessionMode.fromWireValue(json.optString(KEY_SESSION_MODE)),
                capabilities = ScheduledTaskCapabilities(
                    browserTools = json.optBoolean(KEY_CAP_BROWSER, true),
                    deviceReadTools = json.optBoolean(KEY_CAP_DEVICE_READ, true),
                    deviceActionTools = json.optBoolean(KEY_CAP_DEVICE_ACTION, false),
                    terminalTools = json.optBoolean(KEY_CAP_TERMINAL, true),
                    mcpTools = json.optBoolean(KEY_CAP_MCP, true),
                    guiTools = json.optBoolean(KEY_CAP_GUI, false),
                ),
                memo = json.optString(KEY_MEMO),
                scheduledForAt = if (json.has(KEY_SCHEDULED_FOR_AT) && !json.isNull(KEY_SCHEDULED_FOR_AT)) {
                    json.optLong(KEY_SCHEDULED_FOR_AT)
                } else null,
                startedAt = json.optLong(KEY_STARTED_AT, System.currentTimeMillis()),
            )
        }
    }
}
