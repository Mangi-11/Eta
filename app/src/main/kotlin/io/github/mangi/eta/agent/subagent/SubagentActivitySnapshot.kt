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
 *
 * 所有跨进程或落盘传输必须经由 [encodeForWire] / [boundedForWire]，按序列化后的真实 UTF-16
 * 长度控制在 Wire 预算内，避免底层截断破坏 JSON 结构。
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
    /** 删减早期步骤前的累计工具调用数；旧快照缺省为 0，读取时回退到 [steps] 计数。 */
    val totalToolSteps: Int = 0,
    /** 因步骤上限或 Wire 预算折叠的早期步骤数。 */
    val omittedSteps: Int = 0,
) {
    val toolStepCount: Int
        get() = maxOf(totalToolSteps, steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL })

    fun encode(): String = json.encodeToString(this)

    fun encodeForWire(maxChars: Int = WIRE_BUDGET_CHARS): String =
        boundedForWire(maxChars).encode()

    /**
     * 按真实序列化长度将快照收敛到 [maxChars] 个 UTF-16 单元内，同时保持 JSON 合法。
     * 优先保留状态、用量、累计工具步数和最近步骤，逐级压缩长汇报、提示词和早期步骤详情。
     */
    fun boundedForWire(maxChars: Int = WIRE_BUDGET_CHARS): SubagentActivitySnapshot {
        val budget = maxChars.coerceAtLeast(MIN_WIRE_BUDGET_CHARS)
        val preservedToolSteps = toolStepCount
        val normalizedBase = if (totalToolSteps != preservedToolSteps) {
            copy(totalToolSteps = preservedToolSteps)
        } else {
            this
        }
        if (normalizedBase.encode().length <= budget) return normalizedBase

        var candidate = normalizedBase.copy(
            subagentId = normalizedBase.subagentId.takeUtf16Safe(MAX_ID_CHARS),
            subagentType = normalizedBase.subagentType.takeUtf16Safe(MAX_TYPE_CHARS),
            description = normalizedBase.description.truncateWithEllipsis(MAX_DESCRIPTION_CHARS),
            prompt = normalizedBase.prompt.truncateWithEllipsis(MAX_PROMPT_CHARS),
            model = normalizedBase.model.takeUtf16Safe(MAX_MODEL_CHARS),
            currentAction = normalizedBase.currentAction.truncateWithEllipsis(MAX_ACTION_CHARS),
            output = normalizedBase.output.truncateWithEllipsis(MAX_OUTPUT_CHARS),
            error = normalizedBase.error?.truncateWithEllipsis(MAX_ERROR_CHARS),
            totalToolSteps = preservedToolSteps,
        )
        if (candidate.encode().length <= budget) return candidate

        // 第一级：先压缩长汇报与提示词，保留完整步骤时间线。
        val textTiers = listOf(
            6_000 to 1_500,
            3_000 to 600,
            1_200 to 240,
            400 to 120,
        )
        for ((outputLimit, promptLimit) in textTiers) {
            candidate = candidate.copy(
                output = candidate.output.truncateWithEllipsis(outputLimit),
                prompt = candidate.prompt.truncateWithEllipsis(promptLimit),
            )
            if (candidate.encode().length <= budget) return candidate
        }

        // 第二级：优先保留最近步骤的详情，压缩早期步骤详情。
        if (candidate.steps.isNotEmpty()) {
            val detailTiers = listOf(
                Triple(240, 800, 160),
                Triple(0, 400, 120),
                Triple(0, 120, 96),
                Triple(0, 0, 80),
            )
            for ((olderDetailLimit, recentDetailLimit, titleLimit) in detailTiers) {
                val recentStart = (candidate.steps.size - RECENT_STEPS_PRIORITY_COUNT).coerceAtLeast(0)
                candidate = candidate.copy(
                    steps = candidate.steps.mapIndexed { index, step ->
                        val detailLimit = if (index >= recentStart) recentDetailLimit else olderDetailLimit
                        step.copy(
                            id = step.id.takeUtf16Safe(MAX_ID_CHARS),
                            title = step.title.truncateWithEllipsis(titleLimit),
                            detail = step.detail.truncateWithEllipsis(detailLimit),
                            toolName = step.toolName.takeUtf16Safe(MAX_TYPE_CHARS),
                        )
                    },
                )
                if (candidate.encode().length <= budget) return candidate
            }
        }

        // 第三级：丢弃最早步骤，累计 omittedSteps，保留最近步骤与总工具步数。
        if (candidate.steps.isNotEmpty()) {
            val allSteps = candidate.steps
            val baseOmitted = candidate.omittedSteps
            var dropCount = 1
            while (dropCount <= allSteps.size) {
                val remaining = allSteps.subList(dropCount, allSteps.size)
                val shrunk = candidate.copy(
                    steps = remaining,
                    omittedSteps = baseOmitted + dropCount,
                )
                if (shrunk.encode().length <= budget) return shrunk
                // 步骤较多时步进加速，最后确保能落到清空 steps。
                val step = ((allSteps.size - dropCount) / 3).coerceAtLeast(1)
                dropCount = if (dropCount == allSteps.size) {
                    allSteps.size + 1
                } else {
                    (dropCount + step).coerceAtMost(allSteps.size)
                }
            }
            candidate = candidate.copy(
                steps = emptyList(),
                omittedSteps = baseOmitted + allSteps.size,
            )
            if (candidate.encode().length <= budget) return candidate
        }

        // 第四级：极端转义元数据兜底，清空正文并逐步收紧各元数据长度，确保永不超限。
        candidate = candidate.copy(
            prompt = "",
            output = "",
            steps = emptyList(),
        )
        var fieldLimit = 120
        while (candidate.encode().length > budget && fieldLimit > 0) {
            candidate = candidate.copy(
                subagentId = candidate.subagentId.takeUtf16Safe(fieldLimit.coerceAtMost(MAX_ID_CHARS)),
                subagentType = candidate.subagentType.takeUtf16Safe(fieldLimit.coerceAtMost(MAX_TYPE_CHARS)),
                description = candidate.description.truncateWithEllipsis(fieldLimit),
                model = candidate.model.takeUtf16Safe(fieldLimit),
                currentAction = candidate.currentAction.truncateWithEllipsis(fieldLimit),
                error = candidate.error?.truncateWithEllipsis(fieldLimit),
            )
            if (candidate.encode().length <= budget) return candidate
            fieldLimit /= 2
        }
        if (candidate.encode().length > budget) {
            candidate = SubagentActivitySnapshot(
                subagentId = candidate.subagentId.takeUtf16Safe(24),
                subagentType = candidate.subagentType.takeUtf16Safe(24).ifBlank { SubagentProfile.DEFAULT_TYPE },
                status = candidate.status,
                usage = candidate.usage,
                totalToolSteps = preservedToolSteps,
                omittedSteps = candidate.omittedSteps,
            )
        }
        return candidate
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        /** 默认 Wire 序列化上限，留足余量避免触达 IPC 通用 16,000 字符裁剪线。 */
        const val WIRE_BUDGET_CHARS = 12_000

        private const val MIN_WIRE_BUDGET_CHARS = 512
        private const val RECENT_STEPS_PRIORITY_COUNT = 4
        private const val MAX_ID_CHARS = 64
        private const val MAX_TYPE_CHARS = 48
        private const val MAX_DESCRIPTION_CHARS = 120
        private const val MAX_PROMPT_CHARS = 4_000
        private const val MAX_MODEL_CHARS = 120
        private const val MAX_ACTION_CHARS = 120
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_ERROR_CHARS = 1_000

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

        /**
         * Wire 层兜底：当快照 JSON 超过 [maxChars] 时按结构化预算重编码，避免硬切破坏 JSON；
         * 如果原始字符串本就不是合法快照，返回 null 交由调用方走通用策略。
         */
        fun sanitizeForWire(raw: String?, maxChars: Int = WIRE_BUDGET_CHARS): String? {
            if (raw == null) return null
            if (raw.length <= maxChars) return raw
            return decode(raw)?.encodeForWire(maxChars)
        }

        fun initialFromArguments(argumentsJson: String): SubagentActivitySnapshot? = runCatching {
            val args = JSONObject(argumentsJson)
            val resumeId = args.optString("resume").trim().takeUtf16Safe(MAX_ID_CHARS)
            val rawType = args.optString("subagent_type").trim().lowercase()
            val type = when {
                rawType.isNotEmpty() -> rawType.takeUtf16Safe(MAX_TYPE_CHARS)
                resumeId.isNotEmpty() -> "subagent"
                else -> SubagentProfile.DEFAULT_TYPE
            }
            val description = args.optString("description").trim()
                .replace(Regex("\\s+"), " ")
                .truncateWithEllipsis(80)
            val prompt = args.optString("prompt").trim().truncateWithEllipsis(MAX_PROMPT_CHARS)
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

/** 按 UTF-16 单元安全截取前缀，不在高/低代理对中间切开。 */
internal fun String.takeUtf16Safe(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val end = if (Character.isHighSurrogate(this[maxChars - 1])) maxChars - 1 else maxChars
    return if (end <= 0) "" else substring(0, end)
}

/** 按 UTF-16 单元安全截取后缀，不在高/低代理对中间切开。 */
internal fun String.takeLastUtf16Safe(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val start = length - maxChars
    val safeStart = if (Character.isLowSurrogate(this[start])) start + 1 else start
    return if (safeStart >= length) "" else substring(safeStart)
}

internal fun String.truncateWithEllipsis(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    if (maxChars == 1) return takeUtf16Safe(1)
    return takeUtf16Safe(maxChars - 1) + "…"
}
