package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "runtime_results")
internal data class RuntimeResultEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "handoff_id") val handoffId: String,
    @ColumnInfo(name = "handoff_source") val handoffSource: String,
    @ColumnInfo(name = "handoff_payload") val handoffPayload: String,
    @ColumnInfo(name = "dismiss_entry_surface") val dismissEntrySurface: Boolean,
    val ok: Boolean,
    val content: String,
    val error: String?,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String,
    @ColumnInfo(name = "transcript_json") val transcriptJson: String,
    @ColumnInfo(name = "context_snapshot_json", defaultValue = "''") val contextSnapshotJson: String = "",
    @ColumnInfo(name = "operation", defaultValue = "'chat'") val operation: String = "chat",
    @ColumnInfo(name = "rewrite_target_message_id") val rewriteTargetMessageId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** drain 只枚举归属与运行标识，不读取正文或分块。 */
internal data class RuntimeResultHeader(
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "handoff_id") val handoffId: String,
    @ColumnInfo(name = "handoff_source") val handoffSource: String,
    @ColumnInfo(name = "handoff_payload") val handoffPayload: String,
    @ColumnInfo(name = "dismiss_entry_surface") val dismissEntrySurface: Boolean,
    val ok: Boolean,
    val operation: String,
    @ColumnInfo(name = "rewrite_target_message_id") val rewriteTargetMessageId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "runtime_archive_runs")
internal data class RuntimeArchiveRunEntity(
    @PrimaryKey @ColumnInfo(name = "archive_run_id") val archiveRunId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "handoff_id") val handoffId: String,
    @ColumnInfo(name = "handoff_source") val handoffSource: String,
    @ColumnInfo(name = "handoff_payload") val handoffPayload: String,
    @ColumnInfo(name = "dismiss_entry_surface") val dismissEntrySurface: Boolean,
    val ok: Boolean,
    val content: String,
    val error: String?,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String,
    @ColumnInfo(name = "transcript_json") val transcriptJson: String,
    @ColumnInfo(name = "context_snapshot_json", defaultValue = "''") val contextSnapshotJson: String = "",
    @ColumnInfo(name = "operation", defaultValue = "'chat'") val operation: String = "chat",
    @ColumnInfo(name = "rewrite_target_message_id") val rewriteTargetMessageId: String? = null,
    @ColumnInfo(name = "user_image_previews_json") val userImagePreviewsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "runtime_archive_events",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeArchiveRunEntity::class,
            parentColumns = ["archive_run_id"],
            childColumns = ["archive_run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("archive_run_id"),
        Index(value = ["archive_run_id", "sort_index"], unique = true),
    ],
)
internal data class RuntimeArchiveEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "archive_run_id") val archiveRunId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "event_json") val eventJson: String,
)

internal data class RuntimeArchiveRunWithEvents(
    @Embedded val run: RuntimeArchiveRunEntity,
    @Relation(
        parentColumn = "archive_run_id",
        entityColumn = "archive_run_id",
    )
    val events: List<RuntimeArchiveEventEntity>,
)

@Entity(tableName = "runtime_inflight_runs")
internal data class RuntimeInFlightRunEntity(
    @ColumnInfo(name = "transcript_json", defaultValue = "'[]'") val transcriptJson: String = "[]",
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "owner_instance_id") val ownerInstanceId: String,
    @ColumnInfo(name = "context_snapshot_json", defaultValue = "''") val contextSnapshotJson: String = "",
    @ColumnInfo(name = "operation", defaultValue = "'chat'") val operation: String = "chat",
    @ColumnInfo(name = "rewrite_target_message_id") val rewriteTargetMessageId: String? = null,
    @ColumnInfo(name = "handoff_id") val handoffId: String,
    @ColumnInfo(name = "handoff_source") val handoffSource: String,
    @ColumnInfo(name = "handoff_payload") val handoffPayload: String,
    @ColumnInfo(name = "dismiss_entry_surface") val dismissEntrySurface: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "runtime_inflight_events",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeInFlightRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("run_id"),
        Index(value = ["run_id", "sort_index"], unique = true),
    ],
)
internal data class RuntimeInFlightEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "event_json") val eventJson: String,
)

internal data class RuntimeInFlightRunWithEvents(
    @Embedded val run: RuntimeInFlightRunEntity,
    @Relation(
        parentColumn = "run_id",
        entityColumn = "run_id",
    )
    val events: List<RuntimeInFlightEventEntity>,
)
