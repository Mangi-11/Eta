package io.github.mangi.eta.data.repository

import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayHistoryProjection
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.openTextStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 调用方将创建操作纳入会话保存队列，数据库事务保证前缀和上下文一起提交。 */
internal object ConversationBranchRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PAGE_SIZE = 128

    suspend fun create(
        database: EtaDatabase,
        sourceId: String,
        targetMessageId: String,
        branchId: String,
        title: String,
        now: Long,
    ) = database.withTransaction {
        val dao = database.conversationDao()
        val source = dao.conversation(sourceId) ?: throw ConversationBranchUnavailable()
        check(dao.conversationMetadataRow(branchId) == null) { "conversation_branch_id_exists" }
        val outline = dao.branchMessages(sourceId)
        val target = outline.firstOrNull { it.id == targetMessageId } ?: throw ConversationBranchUnavailable()
        val roleplay = source.revisionsJson.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString<RoleplayMessageState>(it) } ?: RoleplayMessageState()
        val journal = ConversationBranchBoundary.resolve(outline, loadJournal(dao, sourceId), roleplay, targetMessageId)
        val retainedIds = outline.asSequence().filter { it.sortIndex <= target.sortIndex }.map { it.id }.toSet()
        val transcriptIds = journal.mapTo(mutableSetOf()) { it.messageId }
        val links = roleplay.links.filterKeys { it in retainedIds }
        if (links.values.any { it.transcriptMessageId !in transcriptIds }) throw ConversationBranchUnavailable()
        val revisions = roleplay.revisions.filterKeys { it in links }
        val retainedRoleplay = RoleplayMessageState(links = links, revisions = revisions)
        val history = if (source.roleplayJson.isBlank()) journal else {
            val changedTranscripts = revisions.keys.mapNotNullTo(mutableSetOf()) { links[it]?.transcriptMessageId }
            val needed = links.filterValues { it.transcriptMessageId in changedTranscripts }.keys
            val contents = buildMap {
                needed.toList().chunked(PAGE_SIZE).forEach { ids ->
                    dao.messageContents(sourceId, ids).forEach { row ->
                        put(row.id, dao.restoreText("conversation_messages", row.id, "content", row.content))
                    }
                }
            }
            RoleplayHistoryProjection.project(journal, retainedRoleplay) { id -> revisions[id]?.content ?: contents[id] }
                ?: throw ConversationBranchUnavailable()
        }
        fun remap(id: String) = ConversationBranchBoundary.remapId(branchId, id)
        val remappedRoleplay = retainedRoleplay.copy(
            links = links.entries.associate { (id, link) -> remap(id) to link.copy(transcriptMessageId = remap(link.transcriptMessageId)) },
            revisions = revisions.entries.associate { (id, revision) -> remap(id) to revision },
        )
        dao.insertConversations(listOf(ConversationEntity(
            id = branchId, title = title, thinkingEnabled = source.thinkingEnabled,
            reasoningEffort = source.reasoningEffort, modelId = source.modelId,
            roleplayJson = source.roleplayJson,
            revisionsJson = if (source.roleplayJson.isBlank()) "" else json.encodeToString(remappedRoleplay),
            createdAt = now, updatedAt = now,
        )))
        dao.updateContextCheckpoint(
            branchId,
            AgentConversationCodec.encodeConversationCheckpoint(history.map { it.copy(messageId = remap(it.messageId)) }),
            AgentConversationCodec.encodeTranscriptForStorage(journal.map { it.copy(messageId = remap(it.messageId)) }),
        )
        var after = -1
        var index = 0
        while (true) {
            val page = dao.branchMessageRows(sourceId, after, target.sortIndex, PAGE_SIZE)
            if (page.isEmpty()) break
            val copies = page.map { stored ->
                val row = dao.restoreMessage(stored)
                row.copy(id = remap(row.id), conversationId = branchId, sortIndex = index++)
            }
            // restore/store 会为新 owner 重建长文本块，不能复制指向源消息的分块引用。
            dao.insertMessages(copies)
            after = page.last().sortIndex
        }
    }

    private suspend fun loadJournal(dao: ConversationDao, id: String): List<ConversationMessage> {
        val stored = dao.contextCheckpointRow(id)?.journalJson?.takeIf { it.isNotBlank() }
            ?: throw ConversationBranchUnavailable()
        return dao.openTextStream("conversation_context_checkpoints", id, "journal", stored).use {
            AgentConversationCodec.decodeTranscript(it)
        }
    }
}
