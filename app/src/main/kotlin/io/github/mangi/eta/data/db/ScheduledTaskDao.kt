package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY enabled DESC, COALESCE(next_run_at, 9223372036854775807) ASC, updated_at DESC")
    fun observeTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId LIMIT 1")
    fun observeTask(taskId: String): Flow<ScheduledTaskEntity?>

    @Query("SELECT * FROM scheduled_tasks ORDER BY enabled DESC, COALESCE(next_run_at, 9223372036854775807) ASC, updated_at DESC")
    suspend fun allTasks(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTask(taskId: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at IS NOT NULL ORDER BY next_run_at ASC LIMIT 1")
    suspend fun nextEnabledTask(): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= :nowMs ORDER BY next_run_at ASC")
    suspend fun dueTasks(nowMs: Long): List<ScheduledTaskEntity>

    @Upsert
    suspend fun upsertTask(entity: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET memo = :memo, memo_updated_at = :updatedAt, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateMemo(taskId: String, memo: String, updatedAt: Long)

    @Query("DELETE FROM scheduled_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("SELECT * FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
    fun observeRuns(taskId: String, limit: Int = 50): Flow<List<ScheduledTaskRunEntity>>

    @Query("SELECT * FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
    suspend fun listRuns(taskId: String, limit: Int = 50): List<ScheduledTaskRunEntity>

    @Query("SELECT * FROM scheduled_task_runs WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: String): ScheduledTaskRunEntity?

    @Query("SELECT * FROM scheduled_task_runs WHERE status = 'running'")
    suspend fun runningRuns(): List<ScheduledTaskRunEntity>

    @Upsert
    suspend fun upsertRun(entity: ScheduledTaskRunEntity)

    @Query(
        "DELETE FROM scheduled_task_runs WHERE task_id = :taskId AND id NOT IN (" +
            "SELECT id FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :keepCount" +
            ")"
    )
    suspend fun trimRuns(taskId: String, keepCount: Int = 100)
}
