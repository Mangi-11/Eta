package io.github.mangi.eta.ui.app

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.openTextStream
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ConversationSearchRepository
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
        val pinnedIds: Set<String> = emptySet(),
        /**
         * 加载后列表下标与库中 sort_index 不一致的会话。流式占位等未入库消息会在排序中留下空洞，
         * 重启后列表被压紧；增量保存按下标截断后缀，这些会话必须先整段重写一次。
         */
        val unalignedConversationIds: Set<String> = emptySet(),
    )

    private val saveMutex = Mutex()

    fun load(context: Context): Snapshot =
        runBlocking(Dispatchers.IO) {
            loadSnapshot(context.applicationContext)
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
        pinnedIds: Set<String> = emptySet(),
    ) = AgentConversationPersistence().save(
        context, Snapshot(selectedConversationId, conversationsById, titles, updatedAt, pinnedIds),
    )

    internal suspend fun saveChanges(
        context: Context,
        selectedConversationId: String?,
        current: Map<String, AgentConversationPersistence.Content>,
        previous: Map<String, AgentConversationPersistence.Content>,
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                var phase = "read_metadata"
                try {
                    val database = EtaDatabase.get(appContext)
                    val dao = database.conversationDao()
                    database.withTransaction {
                        val stored = dao.conversationIdentities().associateBy { it.id }
                        phase = "delete_conversations"
                        (stored.keys - current.keys).forEach { dao.deleteConversation(it) }
                        for ((id, state) in current) {
                            if (!state.isConversationLoaded) {
                                val row = stored[id] ?: continue
                                if (row.title != state.title || row.updatedAt != state.updatedAt || row.isPinned != state.isPinned) {
                                    dao.updateConversationTitle(id, state.title, state.updatedAt, state.isPinned)
                                }
                                continue
                            }
                            val old = previous[id].takeIf { id in stored && it?.isConversationLoaded == true }
                            if (old == null || !state.sameMetadata(old)) {
                                phase = "encode_metadata"
                                val row = ConversationEntity(
                                    id = id, title = state.title,
                                    thinkingEnabled = state.reasoningEffort.enablesReasoning,
                                    reasoningEffort = state.reasoningEffort.wireValue,
                                    appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
                                    roleplayJson = state.roleplay?.let { json.encodeToString(it) }.orEmpty(),
                                    revisionsJson = if (state.roleplay == null) "" else json.encodeToString(state.roleplayMessages),
                                    createdAt = stored[id]?.createdAt ?: state.updatedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                                    updatedAt = state.updatedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                                    modelId = state.modelId,
                                    isPinned = state.isPinned,
                                )
                                phase = "write_metadata"
                                dao.insertConversations(listOf(row))
                            }
                            if (old == null || state.history != old.history || state.journal != old.journal) {
                                val missing = dao.contextCheckpointRow(id) == null
                                phase = "encode_history"
                                val history = if (missing || old == null || state.history != old.history) {
                                    AgentConversationCodec.encodeConversationCheckpoint(state.history)
                                } else null
                                phase = "encode_journal"
                                val journal = if (missing || old == null || state.journal != old.journal) {
                                    AgentConversationCodec.encodeTranscriptForStorage(state.journal)
                                } else null
                                phase = "write_checkpoint"
                                dao.updateContextCheckpoint(id, history, journal)
                            }
                            val oldMessages = old?.messages.orEmpty()
                            var prefix = 0
                            val aligned = old != null && old.messageStartIndex == state.messageStartIndex &&
                                dao.nextMessageIndex(id) == old.messageStartIndex + oldMessages.size
                            while (aligned && prefix < minOf(oldMessages.size, state.messages.size)) {
                                val before = oldMessages[prefix]
                                val after = state.messages[prefix]
                                val sortIndex = state.messageStartIndex + prefix
                                if (before != after && before.toEntityOrNull(id, sortIndex) != after.toEntityOrNull(id, sortIndex)) break
                                prefix++
                            }
                            if (old == null || old.messages != state.messages || old.messageStartIndex != state.messageStartIndex) {
                                phase = "delete_message_suffix"
                                dao.deleteMessagesFrom(id, state.messageStartIndex + prefix)
                                for (index in prefix until state.messages.size) {
                                    phase = "encode_message"
                                    val row = state.messages[index].toEntityOrNull(id, state.messageStartIndex + index) ?: continue
                                    phase = "write_message"
                                    dao.insertMessages(listOf(row))
                                }
                            }
                            if (state.roleplay != null && state.roleplayMessages.revisions != old?.roleplayMessages?.revisions) {
                                val visibleIds = state.messages.mapTo(mutableSetOf()) { it.id }
                                for ((messageId, revision) in state.roleplayMessages.revisions) {
                                    if (messageId !in visibleIds && revision != old?.roleplayMessages?.revisions?.get(messageId)) {
                                        phase = "write_offscreen_revision"
                                        val updated = dao.updateMessageContent(id, messageId, revision.content)
                                        check(updated || old == null) { "Roleplay revision message missing" }
                                    }
                                }
                            }
                        }
                        phase = "write_selection"
                        val selected = selectedConversationId?.takeIf { it in current }
                            ?: current.maxByOrNull { it.value.updatedAt }?.key
                        if (selected == null) dao.deleteState()
                        else dao.insertState(ConversationStateEntity(selectedConversationId = selected))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: phase=$phase type=${failure.safeLogType()}\n${failure.safeStackTrace()}",
                    )
                    throw failure
                }
            }
        }
    }

    private suspend fun loadSnapshot(context: Context): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversationIndex()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()
        val pinnedIds = mutableSetOf<String>()
        conversations.forEach { conversation ->
            val effort = ReasoningEffort.fromWireValue(conversation.reasoningEffort) ?: ReasoningEffort.DEFAULT
            val character = if (conversation.hasRoleplay) loadCharacterSummary(dao, conversation.id) else null
            states[conversation.id] = AgentChatHomeUiState(
                messages = listOfNotNull(conversation.messageId?.let { messageId ->
                    ConversationMessageEntity(
                        id = messageId, conversationId = conversation.id, sortIndex = 0,
                        type = conversation.messageType.orEmpty(), content = conversation.messagePreview.orEmpty(),
                        toolName = conversation.toolName,
                    ).toMessageOrNull()
                }),
                input = "", isStreaming = false, thinkingEnabled = effort.enablesReasoning,
                reasoningEffort = effort, modelId = conversation.modelId, isConversationLoaded = false,
                conversationCharacterName = character?.first, conversationCharacterAvatarPath = character?.second,
            )
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
            if (conversation.isPinned) pinnedIds += conversation.id
        }

        val selected = dao.state()?.selectedConversationId
            ?.takeIf { it in states }
            ?: states.keys.first()
        val loaded = loadConversation(context, selected)
        if (loaded != null) states[selected] = loaded
        val unaligned = if (loaded != null &&
            dao.nextMessageIndex(selected) != loaded.messageStartIndex + loaded.messages.size
        ) setOf(selected) else emptySet()

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            pinnedIds = pinnedIds,
            unalignedConversationIds = unaligned,
        )
    }

    data class MessagePage(
        val messages: List<AgentChatMessageUi>,
        val startIndex: Int,
        val hasEarlierMessages: Boolean,
    )

    suspend fun loadConversation(context: Context, conversationId: String): AgentChatHomeUiState? = withContext(Dispatchers.IO) {
        val database = EtaDatabase.get(context.applicationContext)
        database.withTransaction {
            val dao = database.conversationDao()
            val conversation = dao.conversation(conversationId) ?: return@withTransaction null
            val page = loadMessagePage(dao, conversationId, Int.MAX_VALUE)
            val checkpoint = dao.contextCheckpointRow(conversationId)
            val journal = decodeCheckpoint(dao, conversationId, "journal", checkpoint?.journalJson)
            val history = if (checkpoint != null && checkpoint.historyJson == checkpoint.journalJson &&
                !checkpoint.historyJson.startsWith(io.github.mangi.eta.data.db.ChunkedTextDao.REFERENCE_PREFIX)
            ) journal else decodeCheckpoint(dao, conversationId, "history", checkpoint?.historyJson)
            val state = AgentChatHomeUiState(
                roleplay = conversation.roleplayJson.takeIf(String::isNotBlank)?.let { json.decodeFromString<RoleplayBinding>(it) },
                roleplayMessages = conversation.revisionsJson.takeIf(String::isNotBlank)?.let {
                    json.decodeFromString<RoleplayMessageState>(it)
                } ?: RoleplayMessageState(),
                journal = journal,
                messages = page.messages,
                history = history.ifEmpty { loadLegacyHistory(dao, conversationId) },
                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
                input = "", isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
                modelId = conversation.modelId,
                messageStartIndex = page.startIndex,
                hasEarlierMessages = page.hasEarlierMessages,
            )
            RoleplayConversationReducer.decorate(state.copy(roleplayOriginalContents =
                loadRoleplayMessageContents(dao, conversationId, RoleplayConversationReducer.requiredOriginalContentIds(state))))
        }
    }

    suspend fun loadEarlierMessages(context: Context, conversationId: String, before: Int): MessagePage =
        withContext(Dispatchers.IO) {
            val database = EtaDatabase.get(context.applicationContext)
            database.withTransaction { loadMessagePage(database.conversationDao(), conversationId, before) }
        }

    suspend fun loadRecentMessages(context: Context, conversationId: String): MessagePage =
        loadEarlierMessages(context, conversationId, Int.MAX_VALUE)

    fun shouldReleaseEarlierMessages(messageCount: Int): Boolean = messageCount > MESSAGE_LOAD_PAGE_SIZE * 3

    suspend fun loadRoleplayMessageContents(context: Context, conversationId: String, ids: Set<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val database = EtaDatabase.get(context.applicationContext)
            database.withTransaction { loadRoleplayMessageContents(database.conversationDao(), conversationId, ids) }
        }

    private suspend fun loadRoleplayMessageContents(dao: ConversationDao, conversationId: String, ids: Set<String>): Map<String, String> =
        buildMap {
            for (page in ids.chunked(MESSAGE_LOAD_PAGE_SIZE)) {
                dao.messageContents(conversationId, page).forEach { row ->
                    put(row.id, dao.restoreText("conversation_messages", row.id, "content", row.content))
                }
            }
        }

    private suspend fun loadMessagePage(dao: ConversationDao, conversationId: String, before: Int): MessagePage {
        val rows = dao.messagesBefore(conversationId, before, MESSAGE_LOAD_PAGE_SIZE)
        val startIndex = rows.firstOrNull()?.sortIndex ?: 0
        return MessagePage(rows.mapNotNull { it.toMessageOrNull() }, startIndex, dao.hasMessagesBefore(conversationId, startIndex))
    }

    suspend fun loadAllMessages(context: Context, conversationId: String): List<AgentChatMessageUi> =
        withContext(Dispatchers.IO) {
            val database = EtaDatabase.get(context.applicationContext)
            database.withTransaction {
                val dao = database.conversationDao()
                buildList {
                    var offset = 0
                    while (true) {
                        val page = dao.messagesPage(conversationId, MESSAGE_LOAD_PAGE_SIZE, offset)
                        page.mapNotNullTo(this) { it.toMessageOrNull() }
                        if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                        offset += page.size
                    }
                }
            }
        }

    suspend fun searchConversationIds(
        context: Context,
        query: String,
        matchingNoticeCodes: List<SystemNoticeCode> = emptyList(),
    ): Set<String> = ConversationSearchRepository.search(context, query, matchingNoticeCodes.map { it.wireValue })

    fun unloadConversation(state: AgentChatHomeUiState): AgentChatHomeUiState = state.copy(
        messages = listOfNotNull(state.messages.lastOrNull()?.let { message -> when (message) {
            is UserMessageUi -> message.copy(content = message.content.take(256), images = emptyList())
            is AgentMessageUi -> message.copy(content = message.content.take(256), searchSuggestionsHtml = emptyList())
            is ThinkingMessageUi -> message.copy(content = message.content.take(256))
            is ToolActivityMessageUi -> message.copy(
                argumentsSummary = message.argumentsSummary.take(256), command = message.command?.take(256),
                resultSummary = message.resultSummary?.take(256),
            )
            else -> message
        } }),
        history = emptyList(), journal = emptyList(), roleplay = null, roleplayMessages = RoleplayMessageState(),
        appliedRuntimeRunIds = emptyList(), isConversationLoaded = false, messageStartIndex = 0, hasEarlierMessages = false,
        conversationCharacterName = state.roleplay?.characterName ?: state.conversationCharacterName,
        conversationCharacterAvatarPath = state.roleplay?.avatarPath ?: state.conversationCharacterAvatarPath,
        roleplayOriginalContents = emptyMap(),
    )

    private suspend fun loadCharacterSummary(dao: ConversationDao, conversationId: String): Pair<String?, String?>? {
        val stored = dao.roleplayJsonRow(conversationId)?.takeIf { it.isNotBlank() } ?: return null
        return dao.openTextStream("conversations", conversationId, "roleplay", stored).use { stream ->
            JsonReader(stream.reader(Charsets.UTF_8)).use { reader ->
                var name: String? = null
                var avatarPath: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "characterName" -> name = reader.nextString()
                        "avatarPath" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else avatarPath = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                check(reader.peek() == JsonToken.END_DOCUMENT) { "Invalid roleplay payload trailing data" }
                name to avatarPath
            }
        }
    }

    private fun decodeCheckpoint(dao: ConversationDao, conversationId: String, field: String, stored: String?): List<AgentModelClient.ConversationMessage> {
        if (stored.isNullOrBlank()) return emptyList()
        return dao.openTextStream("conversation_context_checkpoints", conversationId, field, stored).use { stream ->
            AgentConversationCodec.decodeTranscript(stream)
        }
    }

    private suspend fun loadLegacyHistory(dao: ConversationDao, conversationId: String): List<AgentModelClient.ConversationMessage> =
        buildList {
            var offset = 0
            while (true) {
                val page = dao.legacyMessageRowsPage(conversationId, MESSAGE_LOAD_PAGE_SIZE, offset)
                    .map { dao.restoreMessage(it) }
                addAll(page.toLegacyHistory())
                if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                offset += page.size
            }
        }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    private fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = images.toJsonArrayString(),
                isEdited = isEdited,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                        stepCount = usage?.stepCount,
                        llmMs = usage?.llmMs,
                        toolMs = usage?.toolMs,
                        ttftMs = usage?.ttftMs,
                        ttftSteps = usage?.ttftSteps,
                        decodeMs = usage?.decodeMs,
                        decodeTokens = usage?.decodeTokens,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                contextTokens = contextTokens,
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
            )

            is ToolActivityMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL,
                content = command.orEmpty(),
                toolName = toolName,
                toolStatus = status.name,
                argumentsSummary = argumentsSummary,
                resultSummary = resultSummary,
                imageCount = imageCount,
            )

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            else -> null
        }

    private fun ConversationMessageEntity.toMessageOrNull(): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> UserMessageUi(
                id = id,
                content = content,
                images = imagesJson.toStringList(),
                isEdited = isEdited,
            )

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    stepCount = stepCount,
                    llmMs = llmMs,
                    toolMs = toolMs,
                    ttftMs = ttftMs,
                    ttftSteps = ttftSteps,
                    decodeMs = decodeMs,
                    decodeTokens = decodeTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                SystemNoticeMessageUi(
                    id = id,
                    code = code,
                    detail = resultSummary,
                    contextTokens = contextTokens,
                )
            }

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = toolStatus.orEmpty().toToolStatus(),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                imageCount = imageCount,
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            else -> null
        }

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.let { status ->
                if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
            }
            ?: ToolActivityStatusUi.Unknown

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }

    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
}
