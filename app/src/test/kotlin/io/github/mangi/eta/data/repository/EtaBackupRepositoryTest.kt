package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.agent.roleplay.UserPersona
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        runBlocking { SettingsDataStore.setSelection(null, null) }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.allProviders().first().withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# 核心记忆\n喜欢 Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "备份会话",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "保留这条消息",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "保留这条消息",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    @Test(expected = EtaBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        EtaBackupRepository.inspect(
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }

    @Test
    fun roleplayBackupRestoresCardsPersonaAvatarMemoryAndLargeSessionMetadata() = runBlocking {
        CharacterRepository.initialize(context)
        val profile = CharacterRepository.create(CharacterCardCodec.create("角色").withEdits(description = "设定".repeat(40_000)))
        CharacterRepository.writeAvatar(profile.id, Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwdgn9DwADRgHM73L1cQAAAABJRU5ErkJggg==",
        )).let { CharacterRepository.save(profile.copy(avatarPath = it)) }
        CharacterRepository.savePersona(UserPersona("旅人", "来自远方"))
        CharacterMemoryRepository.replaceAll(context, profile.id, "# 剧情\n到过山中")
        val binding = CharacterRepository.binding(profile.id)
        val revisions = Json.encodeToString(RoleplayMessageState(
            links = mapOf("role-message" to RoleplayMessageLink("role-message")),
            revisions = mapOf("role-message" to RoleplayReplyRevision("原版", listOf("原版", "版本".repeat(40_000)), 1)),
        ))
        EtaDatabase.get(context).conversationDao().insertConversations(listOf(
            ConversationEntity("role-session", "剧情", false, roleplayJson = Json.encodeToString(binding),
                revisionsJson = revisions, createdAt = 1, updatedAt = 1),
        ))
        EtaDatabase.get(context).conversationDao().insertMessages(listOf(
            ConversationMessageEntity("role-message", "role-session", 0, "assistant", "版本".repeat(40_000)),
        ))
        val output = ByteArrayOutputStream()
        assertEquals(1, EtaBackupRepository.export(context, output).characterCount)
        CharacterRepository.save(profile.copy(card = profile.card.withEdits(description = "改动")))
        CharacterMemoryRepository.replaceAll(context, profile.id, "改动")
        CharacterRepository.savePersona(UserPersona("其他", ""))
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals("设定".repeat(40_000), CharacterRepository.get(profile.id)?.card?.description)
        assertEquals("旅人", CharacterRepository.persona().name)
        assertEquals("# 剧情\n到过山中", CharacterMemoryRepository.snapshot(context, profile.id).content)
        val row = EtaDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(revisions, row.revisionsJson)
        val restoredBinding = Json.decodeFromString<RoleplayBinding>(row.roleplayJson)
        assertTrue(java.io.File(restoredBinding.avatarPath!!).isFile)
        assertEquals(CharacterRepository.get(profile.id)?.avatarPath, restoredBinding.avatarPath)
    }

    @Test
    fun versionOneBackupRemainsReadable() = runBlocking {
        val summary = EtaBackupRepository.inspect(ByteArrayInputStream(
            """{"format":"eta-backup","schemaVersion":1,"exportedAt":0}""".toByteArray(),
        ))
        assertEquals(0, summary.characterCount)
    }

    @Test
    fun memoryWriteFailureRollsBackDatabaseAndEarlierMemoryFiles() = runBlocking {
        CharacterRepository.initialize(context)
        val first = CharacterRepository.create(CharacterCardCodec.create("原角色"))
        val blocked = CharacterRepository.create(CharacterCardCodec.create("阻塞角色"))
        CharacterMemoryRepository.replaceAll(context, first.id, "原剧情记忆")
        AgentMemoryRepository.replaceAll("原现实记忆")
        val database = EtaDatabase.get(context)
        val records = database.characterDao().characters().map { row ->
            if (row.id == first.id) row.copy(cardJson = CharacterCardCodec.encodeJson(first.card.withEdits(name = "备份角色"))) else row
        }
        val document = EtaBackupDocument(exportedAt = 0, memoryMd = "新的现实记忆",
            roleplay = CharacterBackupData(characters = records, assets = listOf(
                CharacterBackupAsset(first.id, memoryMd = "新的剧情记忆"),
                CharacterBackupAsset(blocked.id, memoryMd = "写入失败"),
            )),
        )
        val blocker = java.io.File(context.filesDir, "roleplay/${blocked.id}/memory")
        check(blocker.parentFile!!.mkdirs() || blocker.parentFile!!.isDirectory)
        blocker.writeText("阻止创建目录")
        try {
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals("原角色", CharacterRepository.get(first.id)?.card?.name)
            assertEquals("原剧情记忆", CharacterMemoryRepository.snapshot(context, first.id).content)
            assertEquals("原现实记忆", AgentMemoryRepository.snapshot().content)
        } finally {
            check(blocker.delete())
        }
    }

    @Test
    fun invalidRevisionBackupIsRejectedBeforeExistingConversationsAreChanged() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val original = ConversationEntity("original", "原会话", false, createdAt = 1, updatedAt = 1)
        dao.insertConversations(listOf(original))
        val invalidStates = listOf(
            "not-json",
            Json.encodeToString(RoleplayMessageState(
                links = mapOf("reply" to RoleplayMessageLink("reply")),
                revisions = mapOf("reply" to RoleplayReplyRevision("原版", listOf("原版"), 9)),
            )),
            Json.encodeToString(RoleplayMessageState(pendingRewrites = mapOf("run" to "outside-conversation"))),
        )
        invalidStates.forEach { revisions ->
            val document = EtaBackupDocument(exportedAt = 0,
                conversations = listOf(ConversationEntity("new", "导入会话", false, revisionsJson = revisions, createdAt = 1, updatedAt = 1)),
                messages = listOf(ConversationMessageEntity("reply", "new", 0, "assistant", "原版")),
            )
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: EtaBackupException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(listOf("original"), dao.conversationEntities().map { it.id })
        }
    }
}
