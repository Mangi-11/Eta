package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    suspend fun tasks(): List<AgentTaskEntity>
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    fun observeTasks(): Flow<List<AgentTaskEntity>>
    @Query("SELECT * FROM agent_tasks WHERE id=:id")
    suspend fun task(id: String): AgentTaskEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: AgentTaskEntity)
    @Update
    suspend fun updateTask(task: AgentTaskEntity): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRun(run: AgentTaskRunEntity): Long
    @Query("SELECT * FROM agent_task_runs WHERE taskId=:taskId ORDER BY queuedAt DESC LIMIT :limit")
    suspend fun runs(taskId: String, limit: Int): List<AgentTaskRunEntity>
    @Query("SELECT * FROM agent_task_runs ORDER BY queuedAt DESC LIMIT 40")
    fun observeRuns(): Flow<List<AgentTaskRunEntity>>
    @Query("SELECT * FROM agent_task_runs WHERE status='queued' ORDER BY queuedAt ASC LIMIT 1")
    suspend fun nextQueued(): AgentTaskRunEntity?
    @Query("SELECT COUNT(*) FROM agent_task_runs WHERE taskId=:taskId AND status IN ('queued','running')")
    suspend fun activeCount(taskId: String): Int
    @Query("UPDATE agent_task_runs SET status='running', startedAt=:now WHERE id=:id AND status='queued'")
    suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE agent_task_runs SET status=:status, finishedAt=:now, resultPreview=:preview WHERE id=:id AND status IN ('queued','running')")
    suspend fun finish(id: String, status: String, now: Long, preview: String): Int
    @Query("UPDATE agent_task_runs SET status='queued', startedAt=NULL WHERE id=:id AND status='running'")
    suspend fun defer(id: String)
    @Query("SELECT * FROM agent_task_runs WHERE status='running'")
    suspend fun interruptedRuns(): List<AgentTaskRunEntity>
    @Query("DELETE FROM agent_task_runs WHERE taskId=:id")
    suspend fun deleteRuns(id: String)
    @Query("DELETE FROM agent_tasks WHERE id=:id")
    suspend fun deleteTask(id: String)
    @Query("UPDATE agent_task_runs SET status='cancelled', finishedAt=:now WHERE taskId=:id AND status='queued'")
    suspend fun cancelQueued(id: String, now: Long)
    @Query("DELETE FROM agent_task_runs WHERE status NOT IN ('queued','running') AND id NOT IN (SELECT id FROM agent_task_runs ORDER BY queuedAt DESC LIMIT 200)")
    suspend fun pruneRuns()

    @Transaction
    suspend fun enqueue(
        task: AgentTaskEntity,
        run: AgentTaskRunEntity,
        nextRunAt: Long?,
        manual: Boolean = false
    ): Boolean {
        val current = task(task.id) ?: return false
        if (current.updatedAt != task.updatedAt || (!manual && !current.enabled) || current.runCount >= current.maxRuns || activeCount(
                task.id
            ) > 0
        ) return false
        if (!manual && current.lastStartedAt?.let { run.queuedAt - it < current.cooldownSeconds * 1000 } == true) return false
        if (insertRun(run) == -1L) return false
        updateTask(
            current.copy(
                nextRunAt = nextRunAt,
                lastStartedAt = run.queuedAt,
                lastStatus = "queued"
            )
        )
        return true
    }

    @Transaction
    suspend fun complete(run: AgentTaskRunEntity, status: String, now: Long, preview: String) {
        if (finish(run.id, status, now, preview) == 0) return
        task(run.taskId)?.let { current ->
            val count = current.runCount + if (status == "configuration_required") 0 else 1
            updateTask(
                current.copy(
                    runCount = count,
                    lastStatus = status,
                    enabled = current.enabled && count < current.maxRuns
                )
            )
        }
        pruneRuns()
    }

    @Transaction
    suspend fun remove(id: String) {
        deleteRuns(id); deleteTask(id)
    }

    @Transaction
    suspend fun recoverInterrupted(now: Long) {
        interruptedRuns().forEach { complete(it, "interrupted", now, "") }
    }

    @Transaction
    suspend fun revise(changed: AgentTaskEntity, expectedUpdatedAt: Long): Boolean {
        val current = task(changed.id) ?: return false
        if (current.updatedAt != expectedUpdatedAt) return false
        updateTask(
            changed.copy(
                runCount = current.runCount,
                lastStartedAt = current.lastStartedAt,
                lastStatus = current.lastStatus,
                enabled = changed.enabled && current.runCount < changed.maxRuns
            )
        )
        return true
    }
}
