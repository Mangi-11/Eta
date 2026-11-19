package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.scheduler.ScheduledTaskAlarmScheduler
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ScheduledTaskDao
import io.github.mangi.eta.data.db.ScheduledTaskEntity
import io.github.mangi.eta.data.db.ScheduledTaskRunEntity
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskMisfirePolicy
import io.github.mangi.eta.data.model.ScheduledTaskQuietHours
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object ScheduledTaskRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dao(context: Context? = null): ScheduledTaskDao {
        val ctx = context?.applicationContext ?: appContext
            ?: error("ScheduledTaskRepository 未初始化")
        return EtaDatabase.get(ctx).scheduledTaskDao()
    }

    fun observeTasks(context: Context? = null): Flow<List<ScheduledTask>> =
        dao(context).observeTasks().map { list -> list.map { it.toDomain() } }

    fun observeTask(taskId: String, context: Context? = null): Flow<ScheduledTask?> =
        dao(context).observeTask(taskId).map { it?.toDomain() }

    fun observeRuns(taskId: String, limit: Int = 50, context: Context? = null): Flow<List<ScheduledTaskRun>> =
        dao(context).observeRuns(taskId, limit).map { list -> list.map { it.toDomain() } }

    suspend fun allTasks(context: Context? = null): List<ScheduledTask> =
        dao(context).allTasks().map { it.toDomain() }

    suspend fun getTask(taskId: String, context: Context? = null): ScheduledTask? =
        dao(context).getTask(taskId)?.toDomain()

    suspend fun nextEnabledTask(context: Context? = null): ScheduledTask? =
        dao(context).nextEnabledTask()?.toDomain()

    suspend fun dueTasks(nowMs: Long = System.currentTimeMillis(), context: Context? = null): List<ScheduledTask> =
        dao(context).dueTasks(nowMs).map { it.toDomain() }

    suspend fun listRuns(taskId: String, limit: Int = 50, context: Context? = null): List<ScheduledTaskRun> =
        dao(context).listRuns(taskId, limit).map { it.toDomain() }

    suspend fun getRun(runId: String, context: Context? = null): ScheduledTaskRun? =
        dao(context).getRun(runId)?.toDomain()

    /**
     * 新建或更新定时任务，并自动重算下一次触发时间与系统闹钟。
     *
     * 手动启用或修改排期时会清除旧的自动熔断原因和连续失败计数。
     */
    suspend fun saveTask(
        task: ScheduledTask,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ): ScheduledTask {
        val normalizedId = task.id.ifBlank { UUID.randomUUID().toString() }
        val existing = dao(context).getTask(normalizedId)?.toDomain()
        val dedicatedKey = if (task.sessionMode == ScheduledTaskSessionMode.DEDICATED) {
            task.dedicatedConversationKey?.takeIf { it.isNotBlank() }
                ?: existing?.dedicatedConversationKey?.takeIf { it.isNotBlank() }
                ?: "task-$normalizedId"
        } else {
            task.dedicatedConversationKey
        }
        val nextRunAt = if (task.enabled) {
            ScheduledTaskScheduleCalculator.computeNextRunAt(
                schedule = task.schedule,
                afterMs = nowMs,
                quietHours = task.quietHours,
                defaultZone = defaultZone,
            )
        } else {
            null
        }
        val effectiveEnabled = if (task.schedule.kind == ScheduledTaskTriggerKind.ONCE && task.enabled && nextRunAt == null) {
            false
        } else {
            task.enabled
        }
        val normalized = task.copy(
            id = normalizedId,
            name = task.name.trim(),
            prompt = task.prompt.trim(),
            enabled = effectiveEnabled,
            dedicatedConversationKey = dedicatedKey,
            timeoutSeconds = task.timeoutSeconds.coerceIn(
                ScheduledTask.MIN_TIMEOUT_SECONDS,
                ScheduledTask.MAX_TIMEOUT_SECONDS,
            ),
            misfireWindowMinutes = task.misfireWindowMinutes.coerceIn(5, 24 * 60),
            memo = task.memo.take(ScheduledTask.MAX_MEMO_CHARS),
            nextRunAt = if (effectiveEnabled) nextRunAt else null,
            consecutiveFailures = if (effectiveEnabled) 0 else task.consecutiveFailures,
            autoDisabledReason = if (effectiveEnabled) null else task.autoDisabledReason,
            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: task.createdAt.takeIf { it > 0L } ?: nowMs,
            updatedAt = nowMs,
        )
        dao(context).upsertTask(normalized.toEntity())
        if (rescheduleAlarm) {
            resolveContext(context)?.let { ScheduledTaskAlarmScheduler.syncNextAlarm(it) }
        }
        return normalized
    }

    suspend fun setEnabled(
        taskId: String,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ): ScheduledTask? {
        val current = getTask(taskId, context) ?: return null
        val updated = current.copy(
            enabled = enabled,
            consecutiveFailures = if (enabled) 0 else current.consecutiveFailures,
            autoDisabledReason = if (enabled) null else current.autoDisabledReason,
        )
        return saveTask(
            task = updated,
            nowMs = nowMs,
            defaultZone = defaultZone,
            rescheduleAlarm = rescheduleAlarm,
            context = context,
        )
    }

    suspend fun updateMemo(
        taskId: String,
        memo: String,
        nowMs: Long = System.currentTimeMillis(),
        context: Context? = null,
    ): Boolean {
        val current = getTask(taskId, context) ?: return false
        val trimmed = memo.trim().take(ScheduledTask.MAX_MEMO_CHARS)
        dao(context).updateMemo(current.id, trimmed, nowMs)
        return true
    }

    suspend fun deleteTask(
        taskId: String,
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ) {
        dao(context).deleteTask(taskId)
        if (rescheduleAlarm) {
            resolveContext(context)?.let { ScheduledTaskAlarmScheduler.syncNextAlarm(it) }
        }
    }

    suspend fun recordRunStarted(
        run: ScheduledTaskRun,
        context: Context? = null,
    ) {
        dao(context).upsertRun(run.toEntity())
        val current = getTask(run.taskId, context) ?: return
        dao(context).upsertTask(
            current.copy(
                lastRunAt = run.startedAt,
                lastStatus = ScheduledTaskRunStatus.RUNNING,
                updatedAt = run.startedAt,
            ).toEntity()
        )
    }

    /**
     * 提交单次执行结果，更新任务状态、推进下一次触发时间，并在连续失败达阈值时自动熔断暂停。
     *
     * 返回更新后的任务快照（含是否触发了自动熔断）。
     */
    suspend fun recordRunFinished(
        run: ScheduledTaskRun,
        advanceSchedule: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ): FinishedRunCommit? {
        val d = dao(context)
        d.upsertRun(run.toEntity())
        d.trimRuns(run.taskId, keepCount = 100)
        val current = getTask(run.taskId, context) ?: return null

        val nextFailures = when {
            run.status.isSuccess -> 0
            run.status.countsAsFailure -> current.consecutiveFailures + 1
            else -> current.consecutiveFailures
        }
        val autoDisabled = run.status.countsAsFailure &&
            current.enabled &&
            nextFailures >= ScheduledTask.MAX_CONSECUTIVE_FAILURES
        val autoDisableReason = if (autoDisabled) {
            AppStrings.quantity(
                R.plurals.scheduled_auto_disabled_consecutive_failures,
                nextFailures,
                "连续失败 %1\$d 次，已自动暂停以避免反复耗电与重试",
                "连续失败 %1\$d 次，已自动暂停以避免反复耗电与重试",
                nextFailures,
            )
        } else if (run.status.isSuccess) {
            null
        } else {
            current.autoDisabledReason
        }

        val isOneShotFinished = advanceSchedule &&
            current.schedule.kind == ScheduledTaskTriggerKind.ONCE &&
            run.status != ScheduledTaskRunStatus.RUNNING
        val nextEnabled = when {
            autoDisabled -> false
            isOneShotFinished -> false
            else -> current.enabled
        }
        val nextRunAt = when {
            !nextEnabled -> null
            advanceSchedule -> ScheduledTaskScheduleCalculator.computeNextRunAt(
                schedule = current.schedule,
                afterMs = maxOf(nowMs, run.scheduledForAt ?: nowMs),
                quietHours = current.quietHours,
                defaultZone = defaultZone,
            )
            else -> current.nextRunAt
        }
        val updatedTask = current.copy(
            enabled = nextEnabled,
            nextRunAt = nextRunAt,
            lastRunAt = run.finishedAt ?: nowMs,
            lastStatus = run.status,
            lastSummary = run.summary.take(300),
            consecutiveFailures = nextFailures,
            autoDisabledReason = autoDisableReason,
            updatedAt = nowMs,
        )
        d.upsertTask(updatedTask.toEntity())
        if (rescheduleAlarm) {
            resolveContext(context)?.let { ScheduledTaskAlarmScheduler.syncNextAlarm(it) }
        }
        return FinishedRunCommit(task = updatedTask, autoDisabledNow = autoDisabled)
    }

    /** 进程重启或系统时间/时区变化时，清理上次进程残留的 running 状态并重算所有已启用任务的下次触发点。 */
    suspend fun reconcileAfterRestartOrClockChange(
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        recomputeAllSchedules: Boolean = false,
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ) {
        val d = dao(context)
        val interruptedSummary = AppStrings.get(
            R.string.scheduled_run_interrupted_process_exit,
            "执行期间应用进程退出，任务已中断",
        )
        val dangling = d.runningRuns()
        dangling.forEach { entity ->
            d.upsertRun(
                entity.copy(
                    status = ScheduledTaskRunStatus.FAILED.wireValue,
                    finishedAt = nowMs,
                    summary = entity.summary.ifBlank { interruptedSummary },
                )
            )
        }
        val tasks = d.allTasks().map { it.toDomain() }
        tasks.forEach { task ->
            val wasRunning = task.lastStatus == ScheduledTaskRunStatus.RUNNING
            var updated = if (wasRunning) {
                task.copy(
                    lastStatus = ScheduledTaskRunStatus.FAILED,
                    lastSummary = task.lastSummary.ifBlank { interruptedSummary },
                    updatedAt = nowMs,
                )
            } else {
                task
            }
            if (updated.enabled) {
                val shouldRecompute = recomputeAllSchedules || updated.nextRunAt == null
                if (shouldRecompute) {
                    val recomputed = ScheduledTaskScheduleCalculator.computeNextRunAt(
                        schedule = updated.schedule,
                        afterMs = nowMs,
                        quietHours = updated.quietHours,
                        defaultZone = defaultZone,
                    )
                    updated = if (recomputed == null && updated.schedule.kind == ScheduledTaskTriggerKind.ONCE) {
                        updated.copy(enabled = false, nextRunAt = null, updatedAt = nowMs)
                    } else {
                        updated.copy(nextRunAt = recomputed, updatedAt = nowMs)
                    }
                }
            }
            if (updated != task) {
                d.upsertTask(updated.toEntity())
            }
        }
        if (rescheduleAlarm) {
            resolveContext(context)?.let { ScheduledTaskAlarmScheduler.syncNextAlarm(it) }
        }
    }

    data class FinishedRunCommit(
        val task: ScheduledTask,
        val autoDisabledNow: Boolean,
    )

    private fun resolveContext(context: Context?): Context? =
        context?.applicationContext ?: appContext

    private fun ScheduledTaskEntity.toDomain(): ScheduledTask {
        val kind = ScheduledTaskTriggerKind.fromWireValue(triggerKind)
        val parsedSchedule = runCatching {
            json.decodeFromString<ScheduledTaskSchedule>(scheduleJson)
        }.getOrElse { ScheduledTaskSchedule(kind = kind) }.copy(kind = kind)
        val parsedQuietHours = runCatching {
            json.decodeFromString<ScheduledTaskQuietHours>(quietHoursJson)
        }.getOrElse { ScheduledTaskQuietHours() }
        val parsedCaps = runCatching {
            json.decodeFromString<ScheduledTaskCapabilities>(capabilitiesJson)
        }.getOrElse { ScheduledTaskCapabilities() }
        return ScheduledTask(
            id = id,
            name = name,
            prompt = prompt,
            enabled = enabled,
            schedule = parsedSchedule,
            misfirePolicy = ScheduledTaskMisfirePolicy.fromWireValue(misfirePolicy),
            misfireWindowMinutes = misfireWindowMinutes,
            quietHours = parsedQuietHours,
            deliveryMode = ScheduledTaskDeliveryMode.fromWireValue(deliveryMode),
            showRunningNotification = showRunningNotification,
            sessionMode = ScheduledTaskSessionMode.fromWireValue(sessionMode),
            dedicatedConversationKey = dedicatedConversationKey,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
            capabilities = parsedCaps,
            timeoutSeconds = timeoutSeconds,
            memo = memo,
            memoUpdatedAt = memoUpdatedAt,
            nextRunAt = nextRunAt,
            lastRunAt = lastRunAt,
            lastStatus = lastStatus?.let(ScheduledTaskRunStatus::fromWireValue),
            lastSummary = lastSummary,
            consecutiveFailures = consecutiveFailures,
            autoDisabledReason = autoDisabledReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ScheduledTask.toEntity(): ScheduledTaskEntity =
        ScheduledTaskEntity(
            id = id,
            name = name,
            prompt = prompt,
            enabled = enabled,
            triggerKind = schedule.kind.wireValue,
            scheduleJson = json.encodeToString(schedule),
            misfirePolicy = misfirePolicy.wireValue,
            misfireWindowMinutes = misfireWindowMinutes,
            quietHoursJson = json.encodeToString(quietHours),
            deliveryMode = deliveryMode.wireValue,
            showRunningNotification = showRunningNotification,
            sessionMode = sessionMode.wireValue,
            dedicatedConversationKey = dedicatedConversationKey,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
            capabilitiesJson = json.encodeToString(capabilities),
            timeoutSeconds = timeoutSeconds,
            memo = memo,
            memoUpdatedAt = memoUpdatedAt,
            nextRunAt = nextRunAt,
            lastRunAt = lastRunAt,
            lastStatus = lastStatus?.wireValue,
            lastSummary = lastSummary,
            consecutiveFailures = consecutiveFailures,
            autoDisabledReason = autoDisabledReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun ScheduledTaskRunEntity.toDomain(): ScheduledTaskRun =
        ScheduledTaskRun(
            id = id,
            taskId = taskId,
            triggerSource = ScheduledTaskTriggerSource.fromWireValue(triggerSource),
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = ScheduledTaskRunStatus.fromWireValue(status),
            summary = summary,
            notified = notified,
            archiveConversationKey = archiveConversationKey,
            modelLabel = modelLabel,
        )

    private fun ScheduledTaskRun.toEntity(): ScheduledTaskRunEntity =
        ScheduledTaskRunEntity(
            id = id,
            taskId = taskId,
            triggerSource = triggerSource.wireValue,
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status.wireValue,
            summary = summary,
            notified = notified,
            archiveConversationKey = archiveConversationKey,
            modelLabel = modelLabel,
        )
}
