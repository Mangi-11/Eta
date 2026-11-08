package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ConversationBranchBoundary
import io.github.mangi.eta.data.repository.ConversationBranchUnavailable
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class AgentConversationBranchPersistenceTest {
    private lateinit var context: Context
    private val dao get() = EtaDatabase.get(context).conversationDao()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() = EtaDatabase.closeForTests()

    @Test
    fun branchCopiesUnloadedPrefixAndRebuildsHistoryWithoutFutureSummary() = runBlocking {
        val original = conversation(180).let { it.copy(history = listOf(ConversationMessage(
            "assistant", "含后续秘密的摘要", contextSummary = true, compactedUserTurns = 180, summaryThroughUserTurn = 180,
        ))) }
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(original))
        val source = AgentConversationStore.loadConversation(context, "source")!!
        assertTrue(source.hasEarlierMessages)
        assertFalse(source.messages.any { it.id == "assistant-30-0" })

        val child = persistence.branch(context, "source", "assistant-30-0", "child", "原对话 · 分支", 2L)
        val copied = AgentConversationStore.loadAllMessages(context, "child")
        assertEquals(62, copied.size)
        assertEquals("回答 30", (copied.last() as AgentMessageUi).content)
        assertEquals(original.journal.take(62).map { it.content }, child.history.map { it.content })
        assertEquals(child.history, child.journal)
        assertFalse(child.history.any { it.contextSummary || "秘密" in it.content })
        assertEquals(360, dao.messageCount("source"))
        assertEquals(original.history, AgentConversationStore.loadConversation(context, "source")!!.history)
        assertTrue(original.messages.map { it.id }.toSet().intersect(copied.map { it.id }.toSet()).isEmpty())
        assertTrue(child.appliedRuntimeRunIds.isEmpty())
        assertEquals("", child.input)
        assertTrue(child.pendingImages.isEmpty())
        assertTrue(child.queuedMessages.isEmpty())
        assertEquals("model-local", child.modelId)
        assertEquals(ReasoningEffort.HIGH, child.reasoningEffort)
        assertFalse(dao.conversation("child")!!.isPinned)
    }

    @Test
    fun queuedOldSnapshotsPreserveCommittedBranchUntilPublicationThenAllowDeletion() = runBlocking {
        val initial = snapshot(conversation(100))
        val persistence = AgentConversationPersistence()
        persistence.save(context, initial)
        val child = persistence.branch(context, "source", "assistant-99-0", "child", "分支", 2L)
        assertTrue(child.hasEarlierMessages)
        repeat(2) { persistence.save(context, initial) }
        assertEquals(200, dao.messageCount("child"))

        val published = initial.copy(conversationsById = initial.conversationsById + ("child" to child),
            titles = initial.titles + ("child" to "分支"), updatedAt = initial.updatedAt + ("child" to 2L))
        persistence.save(context, published)
        persistence.save(context, initial)
        assertNull(dao.conversation("child"))
        assertEquals(200, dao.messageCount("source"))
    }

    @Test
    fun longBodiesImagesAndCheckpointChunksSurviveDeletingSourceAndRestarting() = runBlocking {
        val longText = "长正文😀".repeat(30_000)
        val image = "data:image/png;base64," + "A".repeat(180_000)
        val original = conversation(1).copy(
            messages = listOf(UserMessageUi("user-0", "图片", images = listOf(image)), AgentMessageUi("assistant-0-0", longText)),
            history = listOf(ConversationMessage("user", "图片", messageId = "user-0"),
                ConversationMessage("assistant", longText, messageId = "assistant-0")),
            journal = emptyList(),
        )
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(original))
        persistence.branch(context, "source", "assistant-0-0", "child", "分支", 2L)
        dao.deleteConversation("source")
        EtaDatabase.closeForTests()

        val copied = AgentConversationStore.loadAllMessages(context, "child")
        assertEquals(image, (copied.first() as UserMessageUi).images.single())
        assertEquals(longText, (copied.last() as AgentMessageUi).content)
        assertEquals(longText, AgentConversationStore.loadConversation(context, "child")!!.journal.last().content)
        val rows = dao.messageRowsPage("child", 10, 0)
        assertTrue(rows.last().content.startsWith(ChunkedTextDao.REFERENCE_PREFIX))
        assertTrue(dao.textChunks("conversation_messages", copied.last().id, "content", 100, 0).isNotEmpty())
    }

    @Test
    fun roleplayCandidatesBlockLinksAndGreetingRemainIndependentAcrossRepeatedBranches() = runBlocking {
        val binding = RoleplayBinding(characterId = "character", characterName = "旅人", userName = "朋友", userDescription = "同行者",
            cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("旅人")))
        val journal = listOf(ConversationMessage("assistant", "开场白", messageId = "greeting-source"),
            ConversationMessage("user", "出发", messageId = "user-0"),
            ConversationMessage("assistant", "第一块\n\n第二块", messageId = "assistant-0"),
            ConversationMessage("user", "后续问题", messageId = "user-1"),
            ConversationMessage("assistant", "后续回答", messageId = "assistant-1"))
        var source = conversation(0).copy(roleplay = binding, history = journal, journal = journal,
            messages = listOf(AgentMessageUi("greeting-source", "开场白"), UserMessageUi("user-0", "出发"),
                AgentMessageUi("assistant-0-0", "第一块"), AgentMessageUi("assistant-0-1", "第二块"),
                UserMessageUi("user-1", "后续问题"), AgentMessageUi("assistant-1-0", "后续回答")),
            roleplayMessages = RoleplayMessageState(links = mapOf(
                "greeting-source" to RoleplayMessageLink("greeting-source"), "user-0" to RoleplayMessageLink("user-0"),
                "assistant-0-0" to RoleplayMessageLink("assistant-0", 0), "assistant-0-1" to RoleplayMessageLink("assistant-0", 1),
                "user-1" to RoleplayMessageLink("user-1"), "assistant-1-0" to RoleplayMessageLink("assistant-1"))))
        source = RoleplayConversationReducer.edit(source, "assistant-0-1", "改写第二块")!!
        source = RoleplayConversationReducer.edit(source, "assistant-1-0", "后续改写")!!
        val initial = snapshot(source)
        val persistence = AgentConversationPersistence()
        persistence.save(context, initial)
        val child = persistence.branch(context, "source", "assistant-0-1", "child", "分支", 2L)
        val target = ConversationBranchBoundary.remapId("child", "assistant-0-1")
        assertEquals(binding, child.roleplay)
        assertEquals("第一块\n\n改写第二块", child.history.last().content)
        assertEquals("第一块\n\n第二块", child.journal.last().content)
        assertEquals(listOf("第二块", "改写第二块"), child.roleplayMessages.revisions.getValue(target).candidates)
        assertEquals(setOf(target), child.roleplayMessages.revisions.keys)
        assertTrue(child.messages.first().id.startsWith("greeting-"))
        assertEquals(1, (child.messages.last() as AgentMessageUi).selectedCandidate)

        val grandchild = persistence.branch(context, "child", target, "grandchild", "再次分支", 3L)
        assertEquals(child.history.map { it.content }, grandchild.history.map { it.content })
        val changedChild = RoleplayConversationReducer.select(child, target, 0)!!
        persistence.save(context, initial.copy(conversationsById = initial.conversationsById + ("child" to changedChild),
            titles = initial.titles + ("child" to "分支"), updatedAt = initial.updatedAt + ("child" to 2L)))
        assertEquals("第一块\n\n第二块", AgentConversationStore.loadConversation(context, "child")!!.history.last().content)
        assertEquals("第一块\n\n改写第二块", AgentConversationStore.loadConversation(context, "grandchild")!!.history.last().content)
        assertEquals(source.history, AgentConversationStore.loadConversation(context, "source")!!.history)

        val greeting = persistence.branch(context, "child", child.messages.first().id, "greeting-child", "开场分支", 4L)
        assertEquals(1, greeting.messages.size)
        assertEquals("开场白", greeting.history.single().content)
    }

    @Test
    fun failedWriteRollsBackWholeBranchAndCanBeRetried() = runBlocking {
        val initial = snapshot(conversation(3))
        val persistence = AgentConversationPersistence()
        persistence.save(context, initial)
        val database = EtaDatabase.get(context)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_branch BEFORE INSERT ON conversation_messages " +
            "WHEN NEW.conversation_id = 'child' AND NEW.sort_index = 1 BEGIN SELECT RAISE(ABORT, 'branch_test_failure'); END")
        var failed = false
        try { persistence.branch(context, "source", "assistant-2-0", "child", "分支", 2L) } catch (_: android.database.SQLException) { failed = true }
        assertTrue(failed)
        assertNull(dao.conversation("child"))
        assertNull(dao.contextCheckpointRow("child"))
        assertEquals(0, dao.messageCount("child"))
        assertEquals(6, dao.messageCount("source"))
        persistence.save(context, initial)
        assertNull(dao.conversation("child"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_branch")
        persistence.branch(context, "source", "assistant-2-0", "child", "分支", 2L)
        assertEquals(6, dao.messageCount("child"))
    }

    @Test
    fun missingLegacyHistoryRefusesBranchWithoutChangingSource() = runBlocking {
        val source = conversation(3).copy(journal = listOf(ConversationMessage("assistant", "已丢失旧历史",
            contextSummary = true, compactedUserTurns = 3, summaryThroughUserTurn = 3)))
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(source))
        var refused = false
        try { persistence.branch(context, "source", "assistant-0-0", "child", "分支", 2L) }
        catch (_: ConversationBranchUnavailable) { refused = true }
        assertTrue(refused)
        assertEquals(1, dao.conversationIdentities().size)
        assertEquals(6, dao.messageCount("source"))
        assertEquals(source.journal, AgentConversationCodec.decodeTranscript(dao.contextCheckpoint("source")!!.journalJson))
    }

    private fun conversation(turns: Int): AgentChatHomeUiState {
        val history = (0 until turns).flatMap { listOf(ConversationMessage("user", "问题 $it", messageId = "user-$it"),
            ConversationMessage("assistant", "回答 $it", messageId = "assistant-$it")) }
        return AgentChatHomeUiState(
            messages = (0 until turns).flatMap { listOf(UserMessageUi("user-$it", "问题 $it"), AgentMessageUi("assistant-$it-0", "回答 $it")) },
            history = history, journal = history, input = "源草稿", isStreaming = false, thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH, modelId = "model-local", appliedRuntimeRunIds = listOf("old-run"),
            pendingImages = listOf(PendingImageUi("pending", "uri", "data", "image/png")),
            queuedMessages = listOf(QueuedMessageUi(id = "queued", content = "待发送")),
        )
    }

    private fun snapshot(source: AgentChatHomeUiState) = AgentConversationStore.Snapshot(
        "source", mapOf("source" to source), mapOf("source" to "原对话"), mapOf("source" to 1L), pinnedIds = setOf("source"),
    )
}
