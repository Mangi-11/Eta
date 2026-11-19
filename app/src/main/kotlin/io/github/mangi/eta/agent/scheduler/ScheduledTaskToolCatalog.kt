package io.github.mangi.eta.agent.scheduler

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务相关的模型工具 Schema。
 *
 * - 常规对话中暴露 [TOOL_MANAGE]（`scheduled_tasks`），支持自然语言创建、查看、开关、修改、删除与立即试跑；
 * - 无人值守定时任务执行期间隐藏创建能力以防递归自繁殖，仅暴露自作用域的 [TOOL_UPDATE_MEMO] 与 [TOOL_REPORT]。
 */
internal object ScheduledTaskToolCatalog {
    const val TOOL_MANAGE = "scheduled_tasks"
    const val TOOL_UPDATE_MEMO = "scheduled_task_update_memo"
    const val TOOL_REPORT = "scheduled_task_report"

    val manageNames = setOf(TOOL_MANAGE)
    val selfRunNames = setOf(TOOL_UPDATE_MEMO, TOOL_REPORT)

    fun appendManageTool(tools: JSONArray) {
        tools.put(manageToolSchema())
    }

    fun appendSelfRunTools(tools: JSONArray) {
        tools.put(updateMemoToolSchema())
        tools.put(reportToolSchema())
    }

    private fun manageToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("list", "create", "update", "delete", "run_now")))
                    .put(
                        "description",
                        "操作类型：list 列出全部定时任务；create 创建新定时任务；update 修改已有任务或切换开关；delete 删除任务；run_now 立即试跑一次。",
                    ),
            )
            .put(
                "task_id",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 128)
                    .put("description", "目标任务 ID（update / delete / run_now 必填）。"),
            )
            .put(
                "name",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 80)
                    .put("description", "简短清晰的任务名称（如“每日晚间简报”、“商品补货监控”）。"),
            )
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 8000)
                    .put("description", "每次触发时交由后台 Eta 执行的完整自包含指令；尽量写清目标、数据源与交付格式。"),
            )
            .put(
                "trigger_kind",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("once", "daily", "weekdays", "weekly", "interval", "cron")))
                    .put("description", "触发频率：once 一次性、daily 每天、weekdays 工作日、weekly 按星期、interval 固定间隔、cron 自定义 5 段表达式。"),
            )
            .put(
                "hour",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 0)
                    .put("maximum", 23)
                    .put("description", "本地小时（0-23），用于 daily / weekdays / weekly。"),
            )
            .put(
                "minute",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 0)
                    .put("maximum", 59)
                    .put("description", "本地分钟（0-59），用于 daily / weekdays / weekly。"),
            )
            .put(
                "days_of_week",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7))
                    .put("description", "重复星期列表（1=周一 .. 7=周日），用于 weekly。"),
            )
            .put(
                "interval_minutes",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", ScheduledTaskScheduleCalculator.MIN_INTERVAL_MINUTES)
                    .put("maximum", ScheduledTaskScheduleCalculator.MAX_INTERVAL_MINUTES)
                    .put("description", "固定间隔分钟数（最少 5 分钟），用于 interval。"),
            )
            .put(
                "delay_minutes",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 43_200)
                    .put("description", "一次性任务的相对延迟分钟数（如 60 表示 1 小时后），用于 once。"),
            )
            .put(
                "trigger_at",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 64)
                    .put("description", "一次性任务的绝对触发时间（ISO-8601 或本地 yyyy-MM-dd HH:mm），用于 once。"),
            )
            .put(
                "cron",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 80)
                    .put("description", "标准 5 段 Cron 表达式（分 时 日 月 周，如 '0 9 * * 1-5'），用于 cron。"),
            )
            .put(
                "delivery_mode",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("always", "on_change", "silent")))
                    .put("description", "通知方式：always 每次完成后通知（早报/提醒）；on_change 仅发现变化时通知（监控/巡检）；silent 静默归档。"),
            )
            .put(
                "show_running_notification",
                JSONObject()
                    .put("type", "boolean")
                    .put("description", "任务运行期间是否显示前台进度通知与状态栏胶囊；默认 false（静默后台运行，仅在完成后按 delivery_mode 发送结果通知）。"),
            )
            .put(
                "session_mode",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("isolated", "dedicated")))
                    .put("description", "会话归属：isolated 每次独立归档不刷主聊天（默认）；dedicated 归入固定专属对话频道。"),
            )
            .put(
                "enabled",
                JSONObject()
                    .put("type", "boolean")
                    .put("description", "是否启用该定时任务（create 默认 true，update 可用于暂停或恢复）。"),
            )
            .put(
                "allow_gui",
                JSONObject()
                    .put("type", "boolean")
                    .put("description", "是否允许后台操作第三方应用界面（GUI），默认 false 以确保锁屏时静默安全执行。"),
            )
            .put(
                "timeout_seconds",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 30)
                    .put("maximum", 1800)
                    .put("description", "单次运行墙钟超时秒数（默认 300）。"),
            )

        return AgentToolSchema.function(
            name = TOOL_MANAGE,
            description = "管理本机的 Eta 定时任务：列出、创建、修改、开关、删除或立即试跑定时任务。" +
                "当用户要求定时/每天/每周/每隔一段时间/稍后几分钟或几点自动执行某项工作，或者要求把刚才完成的工作转为定时任务时使用本工具。" +
                "创建前若时间不明确再简短确认；创建或修改完成后，用通俗中文告知排期、通知方式和下一次执行时间。",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("action"))),
        )
    }

    private fun updateMemoToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "content",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 16_000)
                    .put("description", "要保存为本任务最新私有小记事本的完整文本（如上次检查时间戳、最新条目 ID 或价格基线；传空字符串清空）。"),
            )
        return AgentToolSchema.function(
            name = TOOL_UPDATE_MEMO,
            description = "更新当前定时任务的私有小记事本（Task Memo），用于持久化本次检查的游标、状态或已播报摘要，供下次定时运行对比避免重复提醒。严禁写入密钥或敏感凭据。",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("content"))),
        )
    }

    private fun reportToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "outcome",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("deliver", "no_change")))
                    .put("description", "deliver 表示有结果需要通知用户；no_change 表示巡检未发现值得关注的变化，系统将保持静默不发通知。"),
            )
            .put(
                "summary",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 300)
                    .put("description", "1 到 2 句精炼结论摘要，用于通知栏预览和运行时间线展示。"),
            )
        return AgentToolSchema.function(
            name = TOOL_REPORT,
            description = "向定时任务调度器上报本次执行的结构化交付结论：在「仅变动提醒」模式下无变化时务必传 outcome=\"no_change\" 以静默；需要通知用户时可传 outcome=\"deliver\" 并提供精炼通知摘要。",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("outcome", "summary"))),
        )
    }
}
