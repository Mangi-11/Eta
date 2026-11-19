package io.github.mangi.eta.agent.scheduler

import java.util.concurrent.ConcurrentHashMap

/**
 * 定时任务执行期间由 `scheduled_task_report` 工具上报的结构化交付意图。
 *
 * 与最终对话正文解耦：模型可以输出完整报告正文用于归档阅读，同时明确声明本次是
 * `deliver`（带精简通知摘要）还是 `no_change`（静默跳过通知）。
 */
internal data class ScheduledTaskReportedOutcome(
    val outcome: Outcome,
    val summary: String,
) {
    enum class Outcome { DELIVER, NO_CHANGE }
}

internal object ScheduledTaskRunReportRegistry {
    private val reports = ConcurrentHashMap<String, ScheduledTaskReportedOutcome>()

    fun record(runId: String, outcome: ScheduledTaskReportedOutcome) {
        if (runId.isBlank()) return
        reports[runId] = outcome
    }

    fun consume(runId: String): ScheduledTaskReportedOutcome? =
        if (runId.isBlank()) null else reports.remove(runId)

    fun clear(runId: String) {
        if (runId.isNotBlank()) reports.remove(runId)
    }
}
