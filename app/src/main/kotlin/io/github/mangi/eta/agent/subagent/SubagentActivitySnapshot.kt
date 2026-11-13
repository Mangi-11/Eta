package io.github.mangi.eta.agent.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
internal data class SubagentStepSnapshot(
    val id: String,
    val kind: String,
    val title: String = "",
    val detail: String = "",
    val status: String = STATUS_RUNNING,
    val toolName: String = "",
) {
    companion object {
        const val KIND_THINKING = "thinking"
        const val KIND_TOOL = "tool"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

@Serializable
internal data class SubagentUsageSnapshot(
    val inputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val llmMs: Long = 0L,
)

/**
 * Subagent 实时与持久化的结构化活动快照。
 *
 * 保存在父级 `subagent` 工具消息的 `command` 字段中，既支持运行中点击实时检视，
 * 也支持会话归档与重启后直接点开回看完整内部过程。
 */
@Serializable
internal data class SubagentActivitySnapshot(
    val subagentId: String = "",
    val subagentType: String = SubagentProfile.DEFAULT_TYPE,
    val description: String = "",
    val prompt: String = "",
    val model: String = "",
    val resumed: Boolean = false,
    val status: String = STATUS_RUNNING,
    val currentAction: String = "",
    val steps: List<SubagentStepSnapshot> = emptyList(),
    val output: String = "",
    val error: String? = null,
    val usage: SubagentUsageSnapshot? = null,
) {
    val toolStepCount: Int
        get() = steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }

    fun encode(): String = json.encodeToString(this)

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val SNAPSHOT_PREFIX = "{\"subagentId\":"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String?): SubagentActivitySnapshot? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            return runCatching { json.decodeFromString<SubagentActivitySnapshot>(raw) }
                .getOrNull()
                ?.takeIf { it.subagentType.isNotBlank() || it.description.isNotBlank() || it.prompt.isNotBlank() }
        }

        fun initialFromArguments(argumentsJson: String): SubagentActivitySnapshot? = runCatching {
            val args = JSONObject(argumentsJson)
            val resumeId = args.optString("resume").trim()
            val rawType = args.optString("subagent_type").trim().lowercase()
            val type = when {
                rawType.isNotEmpty() -> rawType
                resumeId.isNotEmpty() -> "subagent"
                else -> SubagentProfile.DEFAULT_TYPE
            }
            val description = args.optString("description").trim()
                .replace(Regex("\\s+"), " ")
                .take(80)
            val prompt = args.optString("prompt").trim().take(4_000)
            SubagentActivitySnapshot(
                subagentId = resumeId,
                subagentType = type,
                description = description,
                prompt = prompt,
                resumed = resumeId.isNotEmpty(),
                status = STATUS_RUNNING,
                currentAction = "正在启动…",
            )
        }.getOrNull()
    }
}
