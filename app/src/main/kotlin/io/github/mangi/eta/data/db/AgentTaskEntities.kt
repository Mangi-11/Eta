package io.github.mangi.eta.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent_tasks")
internal data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val triggerJson: String,
    val enabled: Boolean,
    val nextRunAt: Long?,
    val cooldownSeconds: Long,
    val maxRuns: Int,
    val runCount: Int = 0,
    val lastStartedAt: Long? = null,
    val lastStatus: String = "ready",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_task_runs",
    indices = [Index(value = ["taskId", "fireKey"], unique = true)]
)
internal data class AgentTaskRunEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val fireKey: String,
    val status: String = "queued",
    val eventJson: String = "{}",
    val queuedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val resultPreview: String = "",
)
