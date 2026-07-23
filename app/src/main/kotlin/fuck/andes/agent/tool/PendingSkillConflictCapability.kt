package fuck.andes.agent.tool

import fuck.andes.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 上一轮由 Skill 安装工具产生的、可供本轮精确确认的冲突能力。
 *
 * 这里保留提交 SHA 和唯一候选路径，避免确认时把授权扩展到仓库的其他版本或 Skill。
 */
internal data class PendingSkillConflictCapability(
    val repository: String,
    val commitSha: String,
    val selectedPath: String,
    val expectedReplacementId: String,
    val expectedReplacementName: String,
)

internal object PendingSkillConflictCapabilityParser {
    private const val INSTALL_TOOL_NAME = "skills_install_from_github"
    private val commitShaPattern = Regex("^[0-9a-fA-F]{40,64}$")

    /**
     * 只信任最近一个历史用户消息之后，且能回溯到对应 assistant tool call 的工具结果。
     */
    fun parse(
        history: List<AgentModelClient.ConversationMessage>,
    ): PendingSkillConflictCapability? {
        val latestUserIndex = history.indexOfLast { message -> message.role == "user" }
        if (latestUserIndex < 0 || latestUserIndex == history.lastIndex) return null

        val toolNamesByCallId = mutableMapOf<String, String>()
        val ambiguousCallIds = mutableSetOf<String>()
        var latestInstallCallId: String? = null
        var latestInstallResult: AgentModelClient.ConversationMessage? = null

        history.subList(latestUserIndex + 1, history.size).forEach { message ->
            when (message.role) {
                "assistant" -> parseToolCalls(message.toolCallsJson).forEach { call ->
                    if (call.name == INSTALL_TOOL_NAME) {
                        // 新调用尚无结果时也不能复活更早的冲突。
                        latestInstallCallId = call.id
                        latestInstallResult = null
                    }
                    if (call.id in ambiguousCallIds) return@forEach
                    if (toolNamesByCallId.putIfAbsent(call.id, call.name) != null) {
                        toolNamesByCallId.remove(call.id)
                        ambiguousCallIds += call.id
                        if (latestInstallResult?.toolCallId == call.id) {
                            latestInstallResult = null
                        }
                        if (latestInstallCallId == call.id) {
                            latestInstallCallId = null
                        }
                    }
                }

                "tool" -> {
                    val callId = message.toolCallId
                    if (
                        callId.isNotBlank() &&
                        callId !in ambiguousCallIds &&
                        toolNamesByCallId[callId] == INSTALL_TOOL_NAME &&
                        callId == latestInstallCallId
                    ) {
                        latestInstallResult = message
                    }
                }
            }
        }

        return latestInstallResult?.let(::parseConflictResult)
    }

    private fun parseToolCalls(raw: String): List<ToolCallIdentity> {
        val calls = parseStrictJson(raw) as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until calls.length()) {
                val call = calls.opt(index) as? JSONObject ?: continue
                if (call.opt("type") != "function") continue
                val id = (call.opt("id") as? String).orEmpty()
                val function = call.opt("function") as? JSONObject ?: continue
                val name = (function.opt("name") as? String).orEmpty()
                if (id.isNotBlank() && name.isNotBlank()) {
                    add(ToolCallIdentity(id = id, name = name))
                }
            }
        }
    }

    private fun parseConflictResult(
        message: AgentModelClient.ConversationMessage,
    ): PendingSkillConflictCapability? {
        if (message.contentJson.isNotBlank()) return null
        val result = parseStrictJson(message.content) as? JSONObject ?: return null
        if (result.opt("ok") != false || result.opt("code") != "SKILL_CONFLICT") return null

        val repository = (result.opt("repository") as? String)?.trim().orEmpty()
        val commitSha = (result.opt("commitSha") as? String)?.trim().orEmpty()
        if (
            repository.isEmpty() ||
            repository.length > 500 ||
            !commitShaPattern.matches(commitSha)
        ) return null

        val selectedPaths = result.opt("selectedPaths") as? JSONArray ?: return null
        if (selectedPaths.length() != 1) return null
        val selectedPath = (selectedPaths.opt(0) as? String)?.trim().orEmpty()
        if (selectedPath.isEmpty() || selectedPath.length > 1_000) return null

        val conflicts = result.opt("conflicts") as? JSONArray ?: return null
        if (conflicts.length() != 1) return null
        val conflict = conflicts.opt(0) as? JSONObject ?: return null
        if (conflict.opt("replaceAllowed") != true) return null
        val replacementId = (conflict.opt("id") as? String)?.trim().orEmpty()
        val replacementName = (conflict.opt("name") as? String)?.trim().orEmpty()
        if (
            replacementId.isEmpty() ||
            replacementId.length > 64 ||
            replacementName.isEmpty() ||
            replacementName.length > 64
        ) return null

        return PendingSkillConflictCapability(
            repository = repository,
            commitSha = commitSha,
            selectedPath = selectedPath,
            expectedReplacementId = replacementId,
            expectedReplacementName = replacementName,
        )
    }

    private fun parseStrictJson(raw: String): Any? {
        if (raw.isBlank()) return null
        return runCatching {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') return@runCatching null
            value
        }.getOrNull()
    }

    private data class ToolCallIdentity(
        val id: String,
        val name: String,
    )
}
