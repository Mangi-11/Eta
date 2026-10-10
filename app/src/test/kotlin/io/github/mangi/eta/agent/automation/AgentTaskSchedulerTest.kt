package io.github.mangi.eta.agent.automation

import android.app.job.JobScheduler
import android.content.Context
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskSchedulerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dao get() = EtaDatabase.get(context).agentTaskDao()
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)

    @After
    fun tearDown(): Unit = runBlocking {
        AgentTaskScheduler.jobFinished()
        dao.remove("scheduler-test")
        AgentTaskScheduler.refresh(context)
    }

    @Test
    fun futureTimerHasPersistedWakeupBeforeAnyRunIsQueuedAndRemovalCancelsIt() = runBlocking {
        val due = System.currentTimeMillis() + 90_000
        dao.insertTask(task(due))
        assertNull(dao.nextQueued())
        AgentTaskScheduler.refresh(context)
        val job = scheduler.getPendingJob(1110)
        assertNotNull(job)
        assertEquals(
            "io.github.mangi.eta.agent.automation.AgentTaskJobService",
            job!!.service.className
        )
        assertFalse(job.isPeriodic)
        assertEquals(true, job.isPersisted)
        dao.remove("scheduler-test")
        AgentTaskScheduler.refresh(context)
        assertNull(scheduler.getPendingJob(1110))
    }

    @Test
    fun refreshingRulesDoesNotReplaceAnExecutingJob() = runBlocking {
        dao.insertTask(task(System.currentTimeMillis() + 90_000))
        AgentTaskScheduler.refresh(context)
        val original = scheduler.getPendingJob(1110)
        assertEquals(true, AgentTaskScheduler.jobStarted())
        dao.remove("scheduler-test")
        AgentTaskScheduler.refresh(context)
        assertEquals(original, scheduler.getPendingJob(1110))
        AgentTaskScheduler.requestJob(context, 0)
        assertEquals(original, scheduler.getPendingJob(1110))
        AgentTaskScheduler.jobFinished()
        AgentTaskScheduler.requestJob(context, 2_000)
        assertEquals(2_000L, scheduler.getPendingJob(1110)!!.minLatencyMillis)
    }

    private fun task(due: Long) = AgentTaskEntity(
        id = "scheduler-test",
        name = "Test timer",
        prompt = "Return the test result",
        triggerJson = JSONObject().put("type", "once")
            .put("at", Instant.ofEpochMilli(due).toString()).toString(),
        enabled = true,
        nextRunAt = due,
        cooldownSeconds = 900,
        maxRuns = 1,
        createdAt = 0,
        updatedAt = 0,
    )
}
