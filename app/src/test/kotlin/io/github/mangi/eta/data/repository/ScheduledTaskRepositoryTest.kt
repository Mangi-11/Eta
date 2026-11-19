package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScheduledTaskRepositoryTest {
    private lateinit var context: Context
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context = RuntimeEnvironment.getApplication()
        ScheduledTaskRepository.init(context)
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun saveUpdateMemoAndAutoDisableAfterConsecutiveFailures() = runBlocking {
        val nowMs = ZonedDateTime.of(2026, 11, 19, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val created = ScheduledTaskRepository.saveTask(
            task = ScheduledTask(
                id = "task-watch",
                name = "网页雷达",
                prompt = "检查价格变化",
                schedule = ScheduledTaskSchedule(kind = ScheduledTaskTriggerKind.INTERVAL, intervalMinutes = 60),
                deliveryMode = ScheduledTaskDeliveryMode.ON_CHANGE,
                sessionMode = ScheduledTaskSessionMode.ISOLATED,
            ),
            nowMs = nowMs,
            defaultZone = zone,
            rescheduleAlarm = false,
            context = context,
        )
        assertTrue(created.enabled)
        assertFalse(created.showRunningNotification)
        assertNotNull(created.nextRunAt)

        val withProgress = ScheduledTaskRepository.saveTask(
            task = created.copy(showRunningNotification = true),
            nowMs = nowMs + 500,
            defaultZone = zone,
            rescheduleAlarm = false,
            context = context,
        )
        assertTrue(withProgress.showRunningNotification)
        assertTrue(ScheduledTaskRepository.getTask(created.id, context)?.showRunningNotification == true)

        assertTrue(ScheduledTaskRepository.updateMemo(created.id, "last_price=299", nowMs + 1000, context))
        assertEquals("last_price=299", ScheduledTaskRepository.getTask(created.id, context)?.memo)

        var lastCommit: ScheduledTaskRepository.FinishedRunCommit? = null
        repeat(ScheduledTask.MAX_CONSECUTIVE_FAILURES) { index ->
            val t = nowMs + (index + 1) * 60_000L
            lastCommit = ScheduledTaskRepository.recordRunFinished(
                run = ScheduledTaskRun(
                    id = "run-$index",
                    taskId = created.id,
                    triggerSource = ScheduledTaskTriggerSource.SCHEDULE,
                    scheduledForAt = t,
                    startedAt = t,
                    finishedAt = t + 2_000L,
                    status = ScheduledTaskRunStatus.FAILED,
                    summary = "HTTP 500",
                ),
                advanceSchedule = true,
                nowMs = t + 2_000L,
                defaultZone = zone,
                rescheduleAlarm = false,
                context = context,
            )
        }
        val commit = requireNotNull(lastCommit)
        assertTrue(commit.autoDisabledNow)
        assertFalse(commit.task.enabled)
        assertNull(commit.task.nextRunAt)
        assertNotNull(commit.task.autoDisabledReason)

        // 手动重新开启后应清零失败计数并恢复排期。
        val reenabled = ScheduledTaskRepository.setEnabled(
            taskId = created.id,
            enabled = true,
            nowMs = nowMs + 10 * 60_000L,
            defaultZone = zone,
            rescheduleAlarm = false,
            context = context,
        )
        assertNotNull(reenabled)
        assertTrue(reenabled!!.enabled)
        assertEquals(0, reenabled.consecutiveFailures)
        assertNull(reenabled.autoDisabledReason)
        assertNotNull(reenabled.nextRunAt)
    }

    @Test
    fun reconcileMarksDanglingRunningRunsAsFailed() = runBlocking {
        val nowMs = ZonedDateTime.of(2026, 11, 19, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val task = ScheduledTaskRepository.saveTask(
            task = ScheduledTask(
                id = "task-reconcile",
                name = "测试任务",
                prompt = "执行检查",
                schedule = ScheduledTaskSchedule(kind = ScheduledTaskTriggerKind.DAILY, hour = 21, minute = 0),
            ),
            nowMs = nowMs,
            defaultZone = zone,
            rescheduleAlarm = false,
            context = context,
        )
        ScheduledTaskRepository.recordRunStarted(
            run = ScheduledTaskRun(
                id = "run-dangling",
                taskId = task.id,
                triggerSource = ScheduledTaskTriggerSource.MANUAL,
                scheduledForAt = null,
                startedAt = nowMs + 1000L,
                status = ScheduledTaskRunStatus.RUNNING,
            ),
            context = context,
        )
        ScheduledTaskRepository.reconcileAfterRestartOrClockChange(
            nowMs = nowMs + 5000L,
            defaultZone = zone,
            recomputeAllSchedules = true,
            rescheduleAlarm = false,
            context = context,
        )
        val run = ScheduledTaskRepository.getRun("run-dangling", context)
        assertEquals(ScheduledTaskRunStatus.FAILED, run?.status)
        val reloaded = ScheduledTaskRepository.getTask(task.id, context)
        assertEquals(ScheduledTaskRunStatus.FAILED, reloaded?.lastStatus)
        assertNotNull(reloaded?.nextRunAt)
    }
}
