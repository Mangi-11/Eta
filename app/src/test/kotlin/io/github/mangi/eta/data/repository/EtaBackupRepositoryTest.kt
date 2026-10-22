package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @Test
    fun oauthBackupPreservesConnectionModeButCannotRestoreSecretThroughApiKey() = runBlocking {
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "oauth-backup", name = "订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        ))
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById("oauth-backup")).provider
        dao.updateProvider(stored.copy(apiKey = "fixture-secret-in-legacy-column"))
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val raw = output.toString(Charsets.UTF_8.name())
        assertFalse(raw.contains("fixture-secret-in-legacy-column"))
        val exported = Json.decodeFromString<EtaBackupDocument>(raw)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT,
            exported.providers.single { it.provider.id == "oauth-backup" }.provider.authMode)

        val imported = EtaBackupDocument(exportedAt = 0, providers = listOf(
            EtaBackupProvider(stored.copy(apiKey = "fixture-secret-from-backup")),
        ))
        EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(imported).toByteArray()))
        val restored = requireNotNull(dao.providerById("oauth-backup")).provider
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.authMode)
        assertEquals("", restored.apiKey)
    }

    @Test
    fun oauthRestoreActivatesConnectionAfterCleanupAndRemembersItsModelWhileRequiringLogin() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-restore", name = "原连接", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "oauth-restored-model", modelId = "fixture-model", displayName = "测试模型")),
        )
        ProviderRepository.addProvider(provider)
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = EtaBackupDocument(
            exportedAt = 0,
            providers = listOf(EtaBackupProvider(stored.provider.copy(name = "导入连接"), stored.models)),
            selectedProviderId = provider.id,
            selectedModelId = provider.models.single().id,
        )
        var committed = false
        val credentials = mutableMapOf(provider.id to "fixture-existing-credential")
        EtaBackupRepository.import(
            context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
            onCommitted = { committed = true },
            forgetCredentials = { id ->
                assertTrue(committed)
                val pending = runBlocking { requireNotNull(dao.providerById(provider.id)).provider }
                assertEquals("导入连接", pending.name)
                assertEquals(ProviderAuthModes.API_KEY, pending.authMode)
                assertFalse(pending.isEnabled)
                assertEquals("", pending.apiKey)
                credentials.remove(id)
            },
        )

        assertTrue(credentials.isEmpty())
        val restored = requireNotNull(dao.providerById(provider.id)).provider
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.authMode)
        assertTrue(restored.isEnabled)
        assertEquals(provider.models.single().id, SettingsDataStore.selectedModelIdForProvider(provider.id))
        assertEquals(provider.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(provider.models.single().id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun cleanupFailureReportsCommittedDataAndLeavesImportedOAuthConnectionsUnableToAuthenticate() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-cleanup-failure", name = "原连接", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        ProviderRepository.addProvider(provider)
        AgentMemoryRepository.replaceAll("旧记忆")
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = EtaBackupDocument(exportedAt = 0, memoryMd = "导入记忆", providers = listOf(
            EtaBackupProvider(stored.provider.copy(name = "导入连接")),
        ))
        var committed = false
        val failure = runCatching {
            EtaBackupRepository.import(
                context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                onCommitted = { committed = true },
                forgetCredentials = { throw java.io.IOException("fixture cleanup failure") },
            )
        }.exceptionOrNull()

        assertTrue(committed)
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("数据已导入"))
        assertTrue(failure?.message.orEmpty().contains("保持停用"))
        val restored = requireNotNull(dao.providerById(provider.id)).provider
        assertEquals("导入连接", restored.name)
        assertEquals(ProviderAuthModes.API_KEY, restored.authMode)
        assertFalse(restored.isEnabled)
        assertEquals("", restored.apiKey)
        assertEquals("导入记忆", AgentMemoryRepository.snapshot().content)
    }

    @Test
    fun cancellationAfterCommitStillFinishesCredentialCleanupAndConnectionActivation() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-commit-cancel", name = "订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        ProviderRepository.addProvider(provider)
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = EtaBackupDocument(exportedAt = 0, providers = listOf(EtaBackupProvider(stored.provider)))
        var committed = false
        val cleared = mutableListOf<String>()
        val operation = launch {
            val importJob = coroutineContext.job
            EtaBackupRepository.import(
                context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                onCommitted = { committed = true; importJob.cancel() },
                forgetCredentials = { cleared += it },
            )
        }
        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(committed)
        assertTrue(provider.id in cleared)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, requireNotNull(dao.providerById(provider.id)).provider.authMode)
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
        EtaBackupRepository.inspect(context,
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
        val summary = EtaBackupRepository.inspect(context, ByteArrayInputStream(
            """{"format":"eta-backup","schemaVersion":1,"exportedAt":0}""".toByteArray(),
        ))
        assertEquals(0, summary.characterCount)
    }

    @Test
    fun oldBackupReceivesOnlyModelsAddedAfterItsCatalogRevision() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val providers = EtaDatabase.get(context).providerDao().providers().map { row ->
            val models = if (row.provider.id == BuiltinProviders.OPENAI_ID) {
                row.models.filterNot { it.modelId == "gpt-6.1-sol" }
            } else {
                row.models
            }
            EtaBackupProvider(row.provider, models)
        }
        val oldBackup = EtaBackupDocument(exportedAt = 0, providers = providers)
        EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(oldBackup).toByteArray()))

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!.models
        assertTrue(restored.any { it.modelId == "gpt-6-astra" })
        assertTrue(restored.none { it.modelId == "gpt-5.6-terra" })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
        assertEquals(ProviderAuthModes.API_KEY, ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)?.authMode)
    }

    @Test
    fun currentBackupKeepsDeletionOfNewCatalogModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val removed = provider.models.first { it.modelId == "gpt-6.1-sol" }
        ModelRepository.deleteModel(provider.id, removed.id)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val document = Json.decodeFromString<EtaBackupDocument>(output.toString(Charsets.UTF_8.name()))
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, document.catalogRevision)

        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))

        assertTrue(ProviderRepository.providerById(provider.id)!!.models.none { it.modelId == removed.modelId })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun memoryWriteFailureRollsBackDatabaseAndEarlierMemoryFiles() = runBlocking {
        CharacterRepository.initialize(context)
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "existing-provider", name = "原订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        ))
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
        val credentials = mutableMapOf("existing-provider" to "fixture-existing-credential")
        var committed = false
        try {
            var failed = false
            try {
                EtaBackupRepository.import(
                    context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                    onCommitted = { committed = true },
                    forgetCredentials = { credentials.remove(it) },
                )
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)
            assertFalse(committed)
            assertEquals(mapOf("existing-provider" to "fixture-existing-credential"), credentials)
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
