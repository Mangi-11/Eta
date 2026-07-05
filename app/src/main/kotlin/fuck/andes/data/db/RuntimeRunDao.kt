package fuck.andes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface RuntimeRunDao {
    @Query("SELECT * FROM runtime_results ORDER BY created_at ASC")
    suspend fun runtimeResults(): List<RuntimeResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuntimeResult(result: RuntimeResultEntity)

    @Query("DELETE FROM runtime_results WHERE run_id = :runId")
    suspend fun deleteRuntimeResult(runId: String)

    @Query("DELETE FROM runtime_results")
    suspend fun deleteRuntimeResults()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuntimeResults(results: List<RuntimeResultEntity>)

    @Transaction
    suspend fun replaceRuntimeResults(results: List<RuntimeResultEntity>) {
        deleteRuntimeResults()
        if (results.isNotEmpty()) {
            insertRuntimeResults(results)
        }
    }

    @Transaction
    @Query("SELECT * FROM runtime_archive_runs ORDER BY created_at ASC")
    suspend fun archivedRuns(): List<RuntimeArchiveRunWithEvents>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArchivedRun(run: RuntimeArchiveRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchivedEvents(events: List<RuntimeArchiveEventEntity>)

    @Query("DELETE FROM runtime_archive_events WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedEvents(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedRunByArchiveId(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE run_id = :runId OR handoff_id = :runId")
    suspend fun deleteArchivedRun(runId: String)

    @Query("DELETE FROM runtime_archive_events")
    suspend fun deleteAllArchivedEvents()

    @Query("DELETE FROM runtime_archive_runs")
    suspend fun deleteAllArchivedRuns()

    @Transaction
    suspend fun replaceArchivedRun(
        run: RuntimeArchiveRunEntity,
        events: List<RuntimeArchiveEventEntity>,
    ) {
        deleteArchivedEvents(run.archiveRunId)
        upsertArchivedRun(run)
        if (events.isNotEmpty()) {
            insertArchivedEvents(events)
        }
    }

    @Transaction
    suspend fun replaceArchivedRuns(runs: List<RuntimeArchiveRunWithEventsSeed>) {
        deleteAllArchivedEvents()
        deleteAllArchivedRuns()
        runs.forEach { seed ->
            upsertArchivedRun(seed.run)
            if (seed.events.isNotEmpty()) {
                insertArchivedEvents(seed.events)
            }
        }
    }
}

internal data class RuntimeArchiveRunWithEventsSeed(
    val run: RuntimeArchiveRunEntity,
    val events: List<RuntimeArchiveEventEntity>,
)
