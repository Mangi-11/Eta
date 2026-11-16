package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface McpServerDao : ChunkedTextDao {
    @Query("SELECT * FROM mcp_servers ORDER BY sort_order ASC")
    fun serversFlowRows(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY sort_order ASC")
    suspend fun serverRows(): List<McpServerEntity>

    @Transaction
    suspend fun servers(): List<McpServerEntity> = serverRows().map { restoreServer(it) }

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun serverRowById(id: String): McpServerEntity?

    @Transaction
    suspend fun serverById(id: String): McpServerEntity? = serverRowById(id)?.let { restoreServer(it) }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRow(server: McpServerEntity)

    @Transaction
    suspend fun insert(server: McpServerEntity) {
        insertRow(storeServer(server))
    }

    @Update
    suspend fun updateRow(server: McpServerEntity): Int

    @Transaction
    suspend fun update(server: McpServerEntity): Int = updateRow(storeServer(server))

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String): Int

    suspend fun storeServer(row: McpServerEntity): McpServerEntity = row.copy(
        toolsJson = storeText("mcp_servers", row.id, "tools", row.toolsJson),
        enabledToolNamesJson = storeText("mcp_servers", row.id, "enabled_tools", row.enabledToolNamesJson),
    )

    suspend fun restoreServer(row: McpServerEntity): McpServerEntity = row.copy(
        toolsJson = restoreText("mcp_servers", row.id, "tools", row.toolsJson),
        enabledToolNamesJson = restoreText("mcp_servers", row.id, "enabled_tools", row.enabledToolNamesJson),
    )
}
