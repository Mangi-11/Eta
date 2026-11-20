package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** 工作过程卡片内的一个时间线节点：单条消息，或连续调用同一工具形成的分组。 */
@Immutable
internal sealed interface AgentWorkStep {
    val key: String

    data class Single(val message: AgentChatMessageUi) : AgentWorkStep {
        override val key: String = message.id
    }

    data class ToolGroup(
        val toolName: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "group-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failedCount: Int get() = calls.count { it.status == ToolActivityStatusUi.Failed }
    }
}

/**
 * Agent 常连续读取多个文件或多次观察屏幕；同名工具连续出现时合并为一个节点，
 * 时间线只保留“做了什么”的层级，细节按需展开。浏览器条目携带实时预览，保持独立。
 */
internal fun List<AgentChatMessageUi>.toWorkSteps(): List<AgentWorkStep> = buildList {
    val run = mutableListOf<ToolActivityMessageUi>()

    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> add(AgentWorkStep.Single(run.single()))
            else -> add(AgentWorkStep.ToolGroup(toolName = run.first().toolName, calls = run.toList()))
        }
        run.clear()
    }

    this@toWorkSteps.forEach { message ->
        if (message is ToolActivityMessageUi && message.toolName !in UNGROUPED_TOOLS) {
            if (run.isNotEmpty() && run.first().toolName != message.toolName) flushRun()
            run += message
        } else {
            flushRun()
            add(AgentWorkStep.Single(message))
        }
    }
    flushRun()
}

private val UNGROUPED_TOOLS = setOf("browser_use", "subagent")

/** 折叠后的工作过程摘要所需计数。 */
internal data class AgentWorkSummary(
    val thinkingCount: Int,
    val toolCount: Int,
    val failedCount: Int,
)

internal fun List<AgentChatMessageUi>.workSummary(): AgentWorkSummary = AgentWorkSummary(
    thinkingCount = count { it is ThinkingMessageUi },
    toolCount = count { it is ToolActivityMessageUi },
    failedCount = count { it is ToolActivityMessageUi && it.status == ToolActivityStatusUi.Failed },
)

/**
 * 折叠行下方的一行结果。成功的常态结果（如“完成”）不占行；终端输出取最后一行，
 * 因为命令的结论通常在末尾；失败去掉重复的“失败”前缀与日志用的 code= 尾巴。
 */
internal fun ToolActivityMessageUi.outcomeLine(): String? {
    val summary = resultSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = summary.lines().map(String::trim).filter(String::isNotEmpty)
    val head = lines.firstOrNull() ?: return null
    return when (status) {
        ToolActivityStatusUi.Running -> null
        ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
            val detail = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            stripAnyPrefix(head, failurePrefixes())
                .substringBefore(FAILURE_CODE_SEPARATOR)
                .takeIf { it.isNotBlank() && it !in failureLabels() }
                ?: detail
        }
        ToolActivityStatusUi.Success -> {
            val output = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            when {
                output != null -> output
                head in trivialSuccessLabels() -> null
                else -> stripAnyPrefix(head, successPrefixes()).takeIf { it.isNotBlank() }
            }
        }
    }
}

private fun stripAnyPrefix(text: String, prefixes: List<String>): String {
    for (prefix in prefixes) {
        if (text.startsWith(prefix)) return text.removePrefix(prefix)
    }
    return text
}

private fun failureLabels(): Set<String> = setOf(
    "失败",
    "失敗",
    "Failed",
    AppStrings.get(R.string.workstep_failure_label, "失败"),
)

private fun failurePrefixes(): List<String> = failureLabels().map { "$it · " }

private fun trivialSuccessLabels(): Set<String> = setOf(
    "完成",
    "执行完成",
    "執行完成",
    "Done",
    "Completed",
    AppStrings.get(R.string.workstep_success_label, "完成"),
    AppStrings.get(R.string.workstep_exec_done_label, "执行完成"),
)

private fun successPrefixes(): List<String> = listOf(
    "完成 · ",
    "Done · ",
    "Completed · ",
    "${AppStrings.get(R.string.workstep_success_label, "完成")} · ",
).distinct()

// 与 Runtime 侧结果摘要的固定格式对应，同时兼容历史多语言持久化记录。
private const val FAILURE_CODE_SEPARATOR = " · code="
private const val TRUNCATION_MARK = "…"
