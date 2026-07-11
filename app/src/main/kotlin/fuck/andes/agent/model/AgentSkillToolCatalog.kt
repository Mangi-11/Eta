package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSkillToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "skills_list",
                    description = "List installed skills with their id, name, description, and capabilities. Use when the user asks what skills are available or whether a certain type of skill is installed.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional keyword to filter by skill id, name, or description.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max results to return, 1-200, default 50.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "skills_read",
                    description = "Read the full SKILL.md body of an installed skill by id, name, or path. Use when you know a skill might be relevant but its full body was not injected this turn.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "skillId",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "The skill's id, name, or SKILL.md path. Use skills_list first if unsure.")
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max characters of body to return, 512-64000, default 16000.")
                                )
                        )
                        .put("required", JSONArray().put("skillId"))
                )
            )
    }

}
