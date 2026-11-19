package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_tasks",
    indices = [
        Index(value = ["enabled", "next_run_at"]),
    ],
)
internal data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val enabled: Boolean,
    @ColumnInfo(name = "trigger_kind") val triggerKind: String,
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    @ColumnInfo(name = "misfire_policy") val misfirePolicy: String,
    @ColumnInfo(name = "misfire_window_minutes") val misfireWindowMinutes: Int,
    @ColumnInfo(name = "quiet_hours_json") val quietHoursJson: String,
    @ColumnInfo(name = "delivery_mode") val deliveryMode: String,
    @ColumnInfo(name = "show_running_notification", defaultValue = "0") val showRunningNotification: Boolean = false,
    @ColumnInfo(name = "session_mode") val sessionMode: String,
    @ColumnInfo(name = "dedicated_conversation_key") val dedicatedConversationKey: String?,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String?,
    @ColumnInfo(name = "capabilities_json") val capabilitiesJson: String,
    @ColumnInfo(name = "timeout_seconds") val timeoutSeconds: Int,
    val memo: String,
    @ColumnInfo(name = "memo_updated_at") val memoUpdatedAt: Long,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long?,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long?,
    @ColumnInfo(name = "last_status") val lastStatus: String?,
    @ColumnInfo(name = "last_summary") val lastSummary: String,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int,
    @ColumnInfo(name = "auto_disabled_reason") val autoDisabledReason: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "scheduled_task_runs",
    foreignKeys = [
        ForeignKey(
            entity = ScheduledTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["task_id", "started_at"]),
        Index(value = ["status"]),
    ],
)
internal data class ScheduledTaskRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "trigger_source") val triggerSource: String,
    @ColumnInfo(name = "scheduled_for_at") val scheduledForAt: Long?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    val status: String,
    val summary: String,
    val notified: Boolean,
    @ColumnInfo(name = "archive_conversation_key") val archiveConversationKey: String?,
    @ColumnInfo(name = "model_label") val modelLabel: String,
)
