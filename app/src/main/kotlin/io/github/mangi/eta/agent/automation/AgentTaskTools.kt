package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class AgentTaskTools(context: Context) {
    private val context = context.applicationContext
    private val dao get() = EtaDatabase.get(context).agentTaskDao()

    fun execute(name: String, args: JSONObject): String = runBlocking {
        try {
            val now = System.currentTimeMillis()
            val result = when (name) {
                "tasks_triggers" -> AgentTaskScheduler.capabilities(context)
                "tasks_list" -> JSONObject().put("items", JSONArray(dao.tasks().map(::describe)))
                "tasks_create" -> {
                    require(dao.tasks().size < 100) { "最多保存 100 个任务" }
                    require(
                        args.keys().asSequence()
                            .all { it in CREATE_FIELDS }) { "任务包含不支持的字段" }
                    val rule = AgentTaskRules.validate(args.getJSONObject("trigger"))
                    val task = AgentTaskEntity(
                        UUID.randomUUID().toString(),
                        text(args, "name", 100),
                        text(args, "prompt", 8000),
                        rule.toString(),
                        boolean(args, "enabled", true),
                        AgentTaskRules.nextTime(rule, now, true),
                        AgentTaskRules.integer(args, "cooldownSeconds", 60, 86400, 900),
                        AgentTaskRules.integer(
                            args,
                            "maxRuns",
                            1,
                            10000,
                            if (rule.getString("type") == "once") 1 else 1000
                        ).toInt(),
                        createdAt = now,
                        updatedAt = now
                    )
                    if (task.enabled) AgentTaskScheduler.requireTriggerAvailable(context, rule)
                    require(task.nextRunAt == null || task.nextRunAt > now) { "首次时间需要在未来" }
                    dao.insertTask(task)
                    AgentTaskScheduler.refresh(context)
                    describe(task)
                }

                "tasks_update" -> {
                    require(
                        args.keys().asSequence().all {
                            it in CREATE_FIELDS + setOf(
                                "taskId",
                                "expectedUpdatedAt"
                            )
                        }) { "任务包含不支持的字段" }
                    val old = requiredTask(args)
                    require(
                        AgentTaskRules.integer(
                            args,
                            "expectedUpdatedAt",
                            0,
                            Long.MAX_VALUE
                        ) == old.updatedAt
                    ) { "任务已变化，请 tasks_list 后重试" }
                    val rule =
                        if (args.has("trigger")) AgentTaskRules.validate(args.getJSONObject("trigger")) else JSONObject(
                            old.triggerJson
                        )
                    val changed = old.copy(
                        name = if (args.has("name")) text(args, "name", 100) else old.name,
                        prompt = if (args.has("prompt")) text(args, "prompt", 8000) else old.prompt,
                        enabled = boolean(args, "enabled", old.enabled),
                        triggerJson = rule.toString(),
                        nextRunAt = if (args.has("trigger") || (!old.enabled && args.optBoolean("enabled"))) AgentTaskRules.nextTime(
                            rule,
                            now,
                            true
                        ) else old.nextRunAt,
                        cooldownSeconds = AgentTaskRules.integer(
                            args,
                            "cooldownSeconds",
                            60,
                            86400,
                            old.cooldownSeconds
                        ),
                        maxRuns = AgentTaskRules.integer(
                            args,
                            "maxRuns",
                            1,
                            10000,
                            old.maxRuns.toLong()
                        ).toInt(),
                        updatedAt = maxOf(now, old.updatedAt + 1)
                    )
                    if (changed.enabled) AgentTaskScheduler.requireTriggerAvailable(context, rule)
                    if (changed.enabled && (args.has("trigger") || !old.enabled)) {
                        require(changed.nextRunAt == null || changed.nextRunAt > now) { "首次时间需要在未来" }
                    }
                    require(dao.revise(changed, old.updatedAt)) { "任务已变化，请重新读取后更新" }
                    if (!changed.enabled) {
                        dao.cancelQueued(old.id, now); AgentTaskScheduler.cancelRunning(
                            context,
                            old.id
                        )
                    }
                    AgentTaskScheduler.refresh(context)
                    describe(dao.task(old.id) ?: changed)
                }

                "tasks_delete" -> {
                    val task = requiredTask(args)
                    AgentTaskScheduler.cancelRunning(context, task.id)
                    dao.remove(task.id)
                    AgentTaskScheduler.refresh(context)
                    JSONObject().put("taskId", task.id)
                }

                "tasks_run" -> {
                    val task = requiredTask(args)
                    require(
                        AgentTaskScheduler.enqueueManual(
                            context,
                            task
                        )
                    ) { "任务已有运行，或已达到次数上限" }
                    JSONObject().put("taskId", task.id).put("status", "queued")
                }

                "tasks_cancel" -> {
                    val task = requiredTask(args)
                    dao.cancelQueued(task.id, now)
                    AgentTaskScheduler.cancelRunning(context, task.id)
                    JSONObject().put("taskId", task.id).put("status", "cancel_requested")
                }

                "tasks_history" -> {
                    val task = requiredTask(args)
                    JSONObject().put("taskId", task.id).put(
                        "items",
                        JSONArray(
                            dao.runs(
                                task.id,
                                AgentTaskRules.integer(args, "limit", 1, 50, 20).toInt()
                            ).map { run ->
                                JSONObject().put("runId", run.id).put("status", run.status)
                                    .put("queuedAt", run.queuedAt)
                                    .put("startedAt", run.startedAt ?: JSONObject.NULL)
                                    .put("finishedAt", run.finishedAt ?: JSONObject.NULL)
                                    .put("resultPreview", run.resultPreview)
                            })
                    )
                }

                else -> error("未知任务工具")
            }
            result.put("ok", true).put("timing", "Android 后台调度，可能延迟；不是准点闹钟")
                .toString()
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("code", "TASK_REQUEST_REJECTED").put(
                "message",
                if (error is IllegalArgumentException) error.message else "任务参数、权限或存储不可用"
            ).toString()
        }
    }

    private suspend fun requiredTask(args: JSONObject) =
        dao.task(text(args, "taskId", 64)) ?: throw IllegalArgumentException("任务不存在")

    private fun text(args: JSONObject, field: String, max: Int): String {
        val value = args.get(field)
        require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "$field 需要 1–$max 字符" }
        return value.trim()
    }

    private fun boolean(args: JSONObject, name: String, default: Boolean): Boolean {
        if (!args.has(name)) return default
        require(args.get(name) is Boolean) { "$name 需要 boolean" }
        return args.getBoolean(name)
    }

    companion object {
        val names = setOf(
            "tasks_triggers",
            "tasks_list",
            "tasks_create",
            "tasks_update",
            "tasks_delete",
            "tasks_run",
            "tasks_cancel",
            "tasks_history"
        )
        private val CREATE_FIELDS =
            setOf("name", "prompt", "trigger", "enabled", "cooldownSeconds", "maxRuns")

        fun describe(task: AgentTaskEntity) =
            JSONObject().put("taskId", task.id).put("name", task.name).put("prompt", task.prompt)
                .put("trigger", JSONObject(task.triggerJson)).put("enabled", task.enabled)
                .put("nextRunAt", task.nextRunAt ?: JSONObject.NULL)
                .put("cooldownSeconds", task.cooldownSeconds).put("maxRuns", task.maxRuns)
                .put("runCount", task.runCount)
                .put("lastStatus", task.lastStatus).put("updatedAt", task.updatedAt)
    }
}
