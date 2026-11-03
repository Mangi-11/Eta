package io.github.mangi.eta.data.db

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface ConversationDao : ChunkedTextDao {
    @Query(
        "SELECT c.id, c.title, c.reasoning_effort, c.created_at, c.updated_at, c.model_id, c.is_pinned, " +
            "(c.roleplay_json != '') AS has_roleplay, " +
            "m.id AS message_id, m.type AS message_type, m.tool_name, " +
            "COALESCE((SELECT substr(content, 1, 256) FROM agent_text_chunks " +
            "WHERE owner_table = 'conversation_messages' AND owner_id = m.id AND field = 'content' " +
            "AND chunk_index = 0), substr(m.content, 1, 256)) AS message_preview " +
            "FROM conversations c LEFT JOIN conversation_messages m ON m.conversation_id = c.id " +
            "AND m.sort_index = (SELECT MAX(sort_index) FROM conversation_messages WHERE conversation_id = c.id) " +
            "ORDER BY c.updated_at DESC, c.id ASC"
    )
    suspend fun conversationIndex(): List<ConversationIndexRow>

    @Query("SELECT id, title, created_at, updated_at, is_pinned FROM conversations")
    suspend fun conversationIdentities(): List<ConversationIdentity>

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt, is_pinned = :isPinned WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String, updatedAt: Long, isPinned: Boolean = false)

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id, is_pinned " +
            "FROM conversations WHERE id = :conversationId"
    )
    suspend fun conversationMetadataRow(conversationId: String): ConversationMetadata?

    @Transaction
    suspend fun conversation(conversationId: String): ConversationMetadata? =
        conversationMetadataRow(conversationId)?.let { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id, is_pinned " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversationMetadataRows(): List<ConversationMetadata>

    @Transaction
    suspend fun conversations(): List<ConversationMetadata> = conversationMetadataRows().map { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id, is_pinned " +
            "FROM conversations ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationMetadataPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Transaction
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata> =
        conversationMetadataPage(limit, offset).map { restoreMetadata(it) }

    suspend fun restoreMetadata(row: ConversationMetadata) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT roleplay_json FROM conversations WHERE id = :conversationId")
    suspend fun roleplayJsonRow(conversationId: String): String?

    @Transaction
    suspend fun roleplayJson(conversationId: String): String? = roleplayJsonRow(conversationId)?.let {
        restoreText("conversations", conversationId, "roleplay", it)
    }

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messageRows(): List<ConversationMessageEntity>

    @Transaction
    suspend fun messages(): List<ConversationMessageEntity> = messageRows().map { restoreMessage(it) }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntityRows(): List<ConversationEntity>

    @Transaction
    suspend fun conversationEntities(): List<ConversationEntity> = conversationEntityRows().map { row ->
        restoreConversation(row)
    }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun conversationEntityRowsPage(limit: Int, offset: Int): List<ConversationEntity>

    suspend fun restoreConversation(row: ConversationEntity) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun allMessageRowsPage(limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpointRows(): List<ConversationContextCheckpointEntity>

    @Transaction
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity> = contextCheckpointRows().map { restoreCheckpoint(it) }

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC LIMIT :limit OFFSET :offset")
    suspend fun contextCheckpointRowsPage(limit: Int, offset: Int): List<ConversationContextCheckpointEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND type IN ('user', 'assistant') " +
        "ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun legacyMessageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity> =
        messageRowsPage(conversationId, limit, offset).map { restoreMessage(it) }

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before " +
        "ORDER BY sort_index DESC LIMIT :limit")
    suspend fun messageRowsBefore(conversationId: String, before: Int, limit: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesBefore(conversationId: String, before: Int, limit: Int): List<ConversationMessageEntity> =
        messageRowsBefore(conversationId, before, limit).asReversed().map { restoreMessage(it) }

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before)")
    suspend fun hasMessagesBefore(conversationId: String, before: Int): Boolean

    @Query("SELECT COALESCE(MAX(sort_index) + 1, 0) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun nextMessageIndex(conversationId: String): Int

    @Query("SELECT DISTINCT conversation_id FROM conversation_messages WHERE type = 'system_notice' AND content IN (:codes)")
    suspend fun searchSystemNotices(codes: List<String>): List<String>

    @Query("SELECT id, conversation_id, type, content, tool_name, arguments_summary, result_summary, tools_json " +
        "FROM conversation_messages ORDER BY conversation_id, sort_index")
    fun messageSearchCursor(): Cursor

    @Query("SELECT id, content FROM conversation_messages WHERE conversation_id = :conversationId AND id IN (:messageIds)")
    suspend fun messageContents(conversationId: String, messageIds: List<String>): List<ConversationMessageContent>

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId)")
    suspend fun hasMessage(conversationId: String, messageId: String): Boolean

    @Query("UPDATE conversation_messages SET content = :content, is_edited = CASE WHEN type = 'user' THEN 1 ELSE is_edited END " +
        "WHERE conversation_id = :conversationId AND id = :messageId")
    suspend fun updateMessageContentRow(conversationId: String, messageId: String, content: String)

    @Transaction
    suspend fun updateMessageContent(conversationId: String, messageId: String, content: String): Boolean {
        if (!hasMessage(conversationId, messageId)) return false
        updateMessageContentRow(conversationId, messageId, storeText("conversation_messages", messageId, "content", content))
        return true
    }

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId AND type = 'assistant')")
    suspend fun hasAssistantMessage(conversationId: String, messageId: String): Boolean

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpointRow(conversationId: String): ConversationContextCheckpointEntity?

    @Transaction
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity? =
        contextCheckpointRow(conversationId)?.let { restoreCheckpoint(it) }

    suspend fun restoreCheckpoint(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Upsert
    suspend fun insertConversationRow(conversation: ConversationEntity)

    @Transaction
    suspend fun insertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { row ->
            insertConversationRow(row.copy(
                appliedRuntimeRunIdsJson = storeText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
                roleplayJson = storeText("conversations", row.id, "roleplay", row.roleplayJson),
                revisionsJson = storeText("conversations", row.id, "revisions", row.revisionsJson),
            ))
        }
    }

    @Upsert
    suspend fun insertMessageRow(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessages(messages: List<ConversationMessageEntity>) {
        messages.forEach { row ->
            insertMessageRow(row.copy(
                content = storeText("conversation_messages", row.id, "content", row.content),
                imagesJson = storeText("conversation_messages", row.id, "images", row.imagesJson),
            ))
        }
    }

    suspend fun restoreMessage(row: ConversationMessageEntity) = row.copy(
        content = restoreText("conversation_messages", row.id, "content", row.content),
        imagesJson = restoreText("conversation_messages", row.id, "images", row.imagesJson),
    )

    @Upsert
    suspend fun insertContextCheckpointRow(checkpoint: ConversationContextCheckpointEntity)

    @Transaction
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>) {
        checkpoints.forEach { row ->
            insertContextCheckpointRow(row.copy(
                historyJson = storeText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
                journalJson = storeText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
            ))
        }
    }

    @Transaction
    suspend fun updateContextCheckpoint(conversationId: String, historyJson: String?, journalJson: String?) {
        val previous = contextCheckpointRow(conversationId)
            ?: ConversationContextCheckpointEntity(conversationId, "[]")
        insertContextCheckpointRow(previous.copy(
            historyJson = historyJson?.let { storeText("conversation_context_checkpoints", conversationId, "history", it) }
                ?: previous.historyJson,
            journalJson = journalJson?.let { storeText("conversation_context_checkpoints", conversationId, "journal", it) }
                ?: previous.journalJson,
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index >= :fromIndex")
    suspend fun deleteMessagesFrom(conversationId: String, fromIndex: Int)

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}
