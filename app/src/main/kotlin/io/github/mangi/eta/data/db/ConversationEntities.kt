package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String = "[]",
    @ColumnInfo(name = "roleplay_json", defaultValue = "''") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json", defaultValue = "''") val revisionsJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 会话最近使用的本地 Model.id；null 表示跟随默认模型。 */
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
)

internal data class ConversationMetadata(
    val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String,
    @ColumnInfo(name = "roleplay_json") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json") val revisionsJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
)

/** 会话侧栏只读取定长预览，不读取上下文、角色卡与修订正文。 */
internal data class ConversationIndexRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "message_type") val messageType: String?,
    @ColumnInfo(name = "message_preview") val messagePreview: String?,
    @ColumnInfo(name = "tool_name") val toolName: String?,
    @ColumnInfo(name = "has_roleplay") val hasRoleplay: Boolean,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
)

internal data class ConversationIdentity(
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
)

internal data class ConversationMessageContent(val id: String, val content: String)

@Serializable
@Entity(
    tableName = "conversation_context_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationContextCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "history_json") val historyJson: String,
    @ColumnInfo(name = "journal_json", defaultValue = "''") val journalJson: String = "",
)

@Serializable
@Entity(tableName = "conversation_state")
internal data class ConversationStateEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    @ColumnInfo(name = "selected_conversation_id") val selectedConversationId: String,
) {
    companion object {
        const val SINGLETON_ID = "main"
    }
}

@Serializable
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "sort_index"], unique = true),
    ],
)
internal data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    val type: String,
    val content: String,
    @ColumnInfo(name = "images_json") val imagesJson: String = "[]",
    @ColumnInfo(name = "is_edited", defaultValue = "0") val isEdited: Boolean = false,
    @ColumnInfo(name = "render_markdown") val renderMarkdown: Boolean? = null,
    @ColumnInfo(name = "context_tokens") val contextTokens: Int? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Int? = null,
    @ColumnInfo(name = "compaction_archive_id") val compactionArchiveId: String? = null,
    @ColumnInfo(name = "step_count") val stepCount: Int? = null,
    @ColumnInfo(name = "llm_ms") val llmMs: Long? = null,
    @ColumnInfo(name = "tool_ms") val toolMs: Long? = null,
    @ColumnInfo(name = "ttft_ms") val ttftMs: Long? = null,
    @ColumnInfo(name = "ttft_steps") val ttftSteps: Int? = null,
    @ColumnInfo(name = "decode_ms") val decodeMs: Long? = null,
    @ColumnInfo(name = "decode_tokens") val decodeTokens: Int? = null,
    @ColumnInfo(name = "elapsed_seconds") val elapsedSeconds: Int? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_status") val toolStatus: String? = null,
    @ColumnInfo(name = "arguments_summary") val argumentsSummary: String? = null,
    @ColumnInfo(name = "result_summary") val resultSummary: String? = null,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "tools_json") val toolsJson: String = "[]",
)
