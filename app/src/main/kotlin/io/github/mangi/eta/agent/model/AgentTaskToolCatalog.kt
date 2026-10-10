package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.automation.AgentTaskRules
import org.json.JSONArray
import org.json.JSONObject

internal object AgentTaskToolCatalog {
    fun appendTo(tools: JSONArray) {
        fun schema(
            name: String,
            description: String,
            fields: JSONObject = JSONObject(),
            required: List<String> = emptyList()
        ) {
            tools.put(
                AgentToolSchema.function(
                    name,
                    description,
                    JSONObject().put("type", "object").put("additionalProperties", false)
                        .put("properties", fields).put("required", JSONArray(required))
                )
            )
        }

        fun text(max: Int) =
            JSONObject().put("type", "string").put("maxLength", max).put("minLength", 1)

        val taskId = { JSONObject().put("taskId", text(64)) }
        val trigger = JSONObject().put("type", "object").put("additionalProperties", false)
            .put(
                "properties",
                JSONObject().put(
                    "type",
                    JSONObject().put("type", "string").put(
                        "enum",
                        JSONArray((AgentTaskRules.timeTypes + AgentTaskRules.eventTypes).toList())
                    )
                )
                    .put("at", text(64)).put("startAt", text(64)).put(
                        "seconds",
                        JSONObject().put("type", "integer").put("minimum", 900)
                            .put("maximum", 31_536_000)
                    )
                    .put("time", text(5)).put("timeZone", text(100)).put(
                        "weekdays",
                        JSONObject().put("type", "array").put(
                            "items",
                            JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7)
                        )
                    )
                    .put(
                        "filters",
                        JSONObject().put("type", "object").put("additionalProperties", false).put(
                            "properties", JSONObject()
                                .put("packageName", text(200)).put("titleContains", text(200))
                                .put("textContains", text(200)).put("taskId", text(64))
                                .put("transport", text(50))
                        )
                    )
                    .put(
                        "conditions",
                        JSONObject().put("type", "object").put("additionalProperties", false).put(
                            "properties", JSONObject()
                                .put("charging", JSONObject().put("type", "boolean"))
                                .put("unlocked", JSONObject().put("type", "boolean"))
                                .put("networkConnected", JSONObject().put("type", "boolean"))
                        )
                    )
            )
            .put("required", JSONArray().put("type"))
        val definition =
            JSONObject().put("name", text(100)).put("prompt", text(8000)).put("trigger", trigger)
                .put("enabled", JSONObject().put("type", "boolean"))
                .put(
                    "cooldownSeconds",
                    JSONObject().put("type", "integer").put("minimum", 60).put("maximum", 86400)
                )
                .put(
                    "maxRuns",
                    JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10000)
                )
        schema(
            "tasks_triggers",
            "Inspect implemented automation triggers, current permissions, event lifecycle and scheduling limits before creating a task. Unavailable future triggers cannot be enabled. Time triggers may be delayed by Android."
        )
        schema(
            "tasks_list",
            "List user-saved assistant tasks, structured triggers, enabled state, run counts and updatedAt revisions."
        )
        schema(
            "tasks_create",
            "Persist an assistant task only when the user asks for a future schedule or automation. First inspect tasks_triggers. Use once with offset ISO 8601 at; interval >=900 seconds; daily requires HH:mm and IANA timeZone, optional weekdays 1–7. Event filters depend on trigger type; task result triggers require an exact source taskId. Local matching uses no model calls; executing uses the currently selected model and permissions. No main-screen GUI operation is allowed during automatic runs. Prefer a specific goal and bounded maxRuns; enabled=false saves a draft when a source is unavailable.",
            definition,
            listOf("name", "prompt", "trigger")
        )
        val update = JSONObject(definition.toString()).put("taskId", text(64))
            .put("expectedUpdatedAt", JSONObject().put("type", "integer").put("minimum", 0))
        schema(
            "tasks_update",
            "Update only specified task fields using the exact expectedUpdatedAt from tasks_list. Disabling cancels queued and running work. Run counts and history remain intact.",
            update,
            listOf("taskId", "expectedUpdatedAt")
        )
        schema(
            "tasks_delete",
            "Delete an exact user-selected task, cancel its active work and remove its run records.",
            taskId(),
            listOf("taskId")
        )
        schema(
            "tasks_run",
            "Queue one immediate run of an existing task when requested. Still respects active-run exclusion and maxRuns; this does not reset its schedule.",
            taskId(),
            listOf("taskId")
        )
        schema(
            "tasks_cancel",
            "Cancel queued or running executions of a task without deleting its saved rule or changing its enabled state.",
            taskId(),
            listOf("taskId")
        )
        schema(
            "tasks_history",
            "Read bounded execution history and result previews for an exact task. Interrupted operations are recorded and never replayed automatically.",
            taskId().put(
                "limit",
                JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)
            ),
            listOf("taskId")
        )
    }
}
