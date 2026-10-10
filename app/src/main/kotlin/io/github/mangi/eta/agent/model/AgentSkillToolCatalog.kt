package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSkillToolCatalog {
    fun appendTo(
        tools: JSONArray,
        githubDiscovery: Boolean = false,
        githubInstall: Boolean = false,
        authoring: Boolean = true,
    ) {
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
            .put(
                AgentToolSchema.function(
                    name = "skills_read_resource",
                    description = "Read a bounded UTF-8 text resource inside an enabled Skill, such as references/guide.md. The path must be relative to that Skill root; this tool never executes scripts or reads outside the Skill.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "skillId",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Installed Skill id, name, or SKILL.md path."),
                                )
                                .put(
                                    "relativePath",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Safe path relative to the Skill root, for example references/guide.md."),
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 512)
                                        .put("maximum", 64_000)
                                        .put("description", "Maximum characters returned, default 16000."),
                                ),
                        )
                        .put(
                            "required",
                            JSONArray().put("skillId").put("relativePath"),
                        ),
                ),
            )
        if (authoring) tools.put(authoringSchema())
        if (githubDiscovery) appendGitHubDiscoveryTools(tools)
        if (githubInstall) appendGitHubInstallTool(tools)
    }

    private fun authoringSchema() = AgentToolSchema.function(
        name = "skills_manage",
        description = "Propose creating or updating an Android procedural skill from verified successful experience. The proposal goes to the notification center and only becomes active after user approval; pending content is not installed. Prefer improving an existing relevant user skill after skills_read. Store reusable steps, prerequisites, observed pitfalls and a completion check; exclude private content, secrets, temporary node IDs and unsupported guesses. Updates require the exact expectedRevision from skills_read and preserve existing resources. Built-in and disabled skills cannot be overwritten. Changes become available next run and never execute scripts.",
        parameters = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("create").put("update")))
                .put("skillId", JSONObject().put("type", "string").put("maxLength", 64).put("pattern", "^[a-z0-9]+(-[a-z0-9]+)*$"))
                .put("description", JSONObject().put("type", "string").put("maxLength", 1000))
                .put("bodyMarkdown", JSONObject().put("type", "string").put("maxLength", 64_000))
                .put("expectedRevision", JSONObject().put("type", "string").put("minLength", 64).put("maxLength", 64)))
            .put("required", JSONArray().put("action").put("skillId").put("description").put("bodyMarkdown")),
    )

    private fun appendGitHubDiscoveryTools(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "skills_list_curated",
                    description = "List installable Skills from the public openai/skills curated catalog. This is read-only. Use the returned commitSha as ref for a subsequent installation so the inspected content cannot change.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "skills_inspect_github",
                    description = "Inspect a public GitHub repository and list every directory containing SKILL.md. This never installs anything. Use the returned commitSha as ref for installation; if multiple candidates match, ask the user to choose exact paths.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "repository",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Public owner/repository or https://github.com/... repository/tree/blob URL."),
                                )
                                .put(
                                    "ref",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Optional branch, tag, or commit. Omit to use the repository default branch."),
                                )
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Optional repository-relative directory used to narrow discovery."),
                                ),
                        )
                        .put("required", JSONArray().put("repository")),
                ),
            )
    }

    private fun appendGitHubInstallTool(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "skills_install_from_github",
                description = "Install selected Skill directories from a public GitHub repository. Paths must come from skills_inspect_github. If one replaceable user Skill conflicts, retry that exact repository, commitSha, path, and id with replaceExisting=true; built-in Skills can never be overwritten. Installation does not run bundled scripts, and installed Skills become available next turn.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "repository",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "Public owner/repository or https://github.com/... repository/tree/blob URL."),
                            )
                            .put(
                                "ref",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 200)
                                    .put("description", "Optional branch, tag, or commit. Omit to use the repository default branch."),
                            )
                            .put(
                                "paths",
                                JSONObject()
                                    .put("type", "array")
                                    .put("description", "One or more exact repository-relative Skill root paths returned by inspection.")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", 1_000),
                                    )
                                    .put("minItems", 1)
                                    .put("maxItems", 20)
                                    .put("uniqueItems", true),
                            )
                            .put(
                                "replaceExisting",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "Set true only when precisely replaying one replaceable SKILL_CONFLICT. paths must contain one item and ref must use the conflict's commitSha."),
                            )
                            .put(
                                "expectedReplacementId",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "Required when replaceExisting is true. Copy the exact id from the single prior SKILL_CONFLICT result; never infer it."),
                            ),
                    )
                    .put("required", JSONArray().put("repository").put("paths")),
            ),
        )
    }
}
