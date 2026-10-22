package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(providerId = null, modelId = null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
    }

    @Test
    fun oauthProviderRoundTripAndCopyKeepProfileWithoutLegacyKey() = runBlocking {
        val added = ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "oauth-source", name = "订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT, apiKey = "fixture-must-not-be-stored",
            models = listOf(Model(id = "oauth-model", modelId = "fixture-model", displayName = "测试")),
        ))

        val stored = requireNotNull(ProviderRepository.providerById(added.id))
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, stored.authMode)
        assertEquals("", stored.apiKey)
        assertEquals("", EtaDatabase.get(context).providerDao().providerById(added.id)?.provider?.apiKey)
        val copy = requireNotNull(ProviderRepository.copyProvider(added.id))
        assertTrue(copy.id != added.id)
        assertTrue(copy.models.single().id != stored.models.single().id)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, copy.authMode)
        assertEquals("", copy.apiKey)
    }

    @Test
    fun oauthRuntimeLookupUsesLocalConfigurationAndLeavesAuthenticationToTransport() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-local", name = "订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "local-model", modelId = "fixture-model", displayName = "测试")),
        )
        ProviderRepository.addProvider(provider)
        val config = requireNotNull(RuntimeConfigRepository.oauthRuntimeConfigFor(
            provider.id, provider.authMode, "fixture-model",
        ))
        assertEquals(provider.id, config.providerId)
        assertEquals("https://chatgpt.com/backend-api/codex", config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals(provider.models, ProviderRepository.providerById(provider.id)?.models)
        assertNull(RuntimeConfigRepository.oauthRuntimeConfigFor(provider.id, ProviderAuthModes.XAI_GROK, "fixture-model"))
        assertNull(RuntimeConfigRepository.oauthRuntimeConfigFor(provider.id, provider.authMode, "unknown-model"))
        ProviderRepository.updateProvider(provider.copy(isEnabled = false))
        assertNull(RuntimeConfigRepository.oauthRuntimeConfigFor(provider.id, provider.authMode, "fixture-model"))
    }

    @Test
    fun signedOutAccountKeepsItsSelectionAndConversationBinding() = runBlocking {
        val unconfigured = OpenAiCompatibleProviderSetting(
            id = "unconfigured", name = "未配置", baseUrl = "https://example.invalid/v1",
            models = listOf(Model(id = "unconfigured-model", modelId = "unconfigured-model", displayName = "未配置模型")),
        )
        val ready = unconfigured.copy(
            id = "ready", name = "可用", apiKey = "fixture-key",
            models = listOf(Model(id = "ready-model", modelId = "ready-model", displayName = "可用模型")),
        )
        val account = unconfigured.copy(
            id = "signed-out", name = "已退出", authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "account-model", modelId = "account-model", displayName = "账号模型")),
        )
        listOf(unconfigured, ready, account).forEach { ProviderRepository.addProvider(it) }
        SettingsDataStore.setSelection(account.id, account.models.single().id)
        EtaDatabase.get(context).conversationDao().insertConversations(listOf(
            ConversationEntity(id = "bound-chat", title = "原会话", thinkingEnabled = false,
                modelId = account.models.single().id, createdAt = 1, updatedAt = 1),
        ))

        val repaired = ProviderRepository.repairSelection()
        assertEquals(account.id, repaired.selectedProviderId)
        assertEquals(account.models.single().id, repaired.selectedModelId)
        assertEquals(account.models.single().id, SettingsDataStore.selectedModelIdForProvider(account.id))
        assertEquals(account.id, RuntimeConfigRepository.runtimeConfigFor(account.models.single().id)?.providerId)
        assertEquals(account.models.single().id,
            EtaDatabase.get(context).conversationDao().conversationEntities().single().modelId)

        SettingsDataStore.setSelection(unconfigured.id, unconfigured.models.single().id)
        assertEquals(unconfigured.id, ProviderRepository.repairSelection().selectedProviderId)
    }

    @Test
    fun explicitlySelectingASignedOutAccountModelUsesThatConnection() = runBlocking {
        val fallback = OpenAiCompatibleProviderSetting(
            id = "first-api", name = "第一个 API", baseUrl = "https://example.invalid/v1", apiKey = "fixture-first-key",
            models = listOf(Model(id = "first-api-model", modelId = "first", displayName = "第一个模型")),
        )
        val selected = fallback.copy(
            id = "selected-api", name = "已选 API", apiKey = "fixture-selected-key",
            models = listOf(Model(id = "selected-api-model", modelId = "selected", displayName = "已选模型")),
        )
        val account = fallback.copy(
            id = "unavailable-account", name = "未登录账号", authMode = ProviderAuthModes.OPENAI_CHATGPT, apiKey = "",
            models = listOf(Model(id = "account-selection-model", modelId = "account-model", displayName = "账号模型",
                source = ModelSource.CATALOG)),
        )
        listOf(fallback, selected, account).forEach { ProviderRepository.addProvider(it) }
        RuntimeConfigRepository.setSelectedModelId(selected.models.single().id)

        RuntimeConfigRepository.setSelectedModelId(account.models.single().id)
        assertEquals(account.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(account.models.single().id, SettingsDataStore.settings().selectedModelId)
        assertEquals(account.models.single().id, ProviderRepository.providerById(account.id)?.models?.single()?.id)

        RuntimeConfigRepository.setSelectedModelId("missing-model")
        assertEquals(fallback.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(fallback.models.single().id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun savingAnEmptyCodexConnectionDoesNotRestorePresets() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "empty-openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1",
        )
        ProviderRepository.addProvider(provider)
        ProviderRepository.updateProvider(provider.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT))
        ProviderRepository.ensureBuiltInsMerged()
        assertTrue(requireNotNull(ProviderRepository.providerById(provider.id)).models.isEmpty())
        ProviderRepository.updateProvider(provider.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT, name = "重命名"))
        assertTrue(requireNotNull(ProviderRepository.providerById(provider.id)).models.isEmpty())
    }

    @Test
    fun existingOfficialModelWithoutWindowRemainsUnsetForEveryRead() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val id = BuiltinProviders.DEEPSEEK_ID
        val stored = Model(id = "manual-flash", modelId = "deepseek-flash", displayName = "我的模型", isEnabled = false)
        ProviderRepository.replaceModels(id, listOf(stored))
        val expected = stored
        assertEquals(expected, ProviderRepository.providerById(id)!!.models.single())
        assertEquals(expected, ProviderRepository.providerByModelId(stored.id)!!.models.single())
        assertEquals(expected, ProviderRepository.allProviders().first { it.id == id }.models.single())
        assertEquals(expected, ProviderRepository.providersFlow().first().first { it.id == id }.models.single())
        assertNull(EtaDatabase.get(context).providerDao().models(id).single().contextWindow)
    }

    @Test
    fun onlyUserConfiguredWindowReachesRuntimeAndChat() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val id = BuiltinProviders.DEEPSEEK_ID
        val model = Model(id = "manual-flash", modelId = "deepseek-flash", displayName = "我的模型")
        val cases = listOf(
            model to null,
            model.copy(contextWindow = 128_000) to null,
            model.copy(contextWindow = 128_000, contextWindowOverride = 64_000) to 64_000,
        )
        for ((stored, expected) in cases) {
            ProviderRepository.replaceModels(id, listOf(stored))
            SettingsDataStore.setSelection(id, stored.id)
            val providers = ProviderRepository.providersFlow().first()
            val picker = AgentModelPickerProjector.project(providers, id, stored.id)
            assertEquals(expected, picker.selectedModel!!.contextWindow)
            assertEquals(expected, RuntimeConfigRepository.currentRuntimeConfig()!!.contextWindow)
        }
    }

    @Test
    fun localAutoCompactionSettingReachesCurrentRuntimeConfig() = runBlocking {
        Prefs.initLocal(context)
        val preferences = requireNotNull(Prefs.localAgentPreferences())
        ProviderRepository.ensureBuiltInsMerged()
        try {
            for (enabled in listOf(false, true)) {
                assertTrue(preferences.edit().putBoolean(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED, enabled).commit())
                assertEquals(enabled, RuntimeConfigRepository.currentRuntimeConfig()!!.autoCompactionEnabled)
            }
        } finally {
            preferences.edit().remove(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED).commit()
        }
    }

    @Test
    fun builtInProvidersRoundTripThroughRoomWithModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()

        val providers = ProviderRepository.allProviders().associateBy { it.id }

        assertTrue(providers.getValue(BuiltinProviders.ANTHROPIC_ID) is AnthropicProviderSetting)
        assertEquals(
            listOf(
                "gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra",
            ),
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.modelId },
        )
        assertEquals(
            List(3) { ModelSource.CATALOG },
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.source },
        )
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            providers.getValue(BuiltinProviders.OPENAI_ID)
                .models
                .first()
                .reasoningCapabilities
                ?.selectableEfforts,
        )
        assertEquals(
            listOf("claude-fable-5-1", "claude-opus-5-5", "claude-fable-5", "claude-opus-4-8", "claude-sonnet-5"),
            providers.getValue(BuiltinProviders.ANTHROPIC_ID).models.map { it.modelId },
        )
        assertEquals(
            listOf(
                "kimi-k3",
                "kimi-k2.7-code",
                "kimi-k2.7-code-highspeed",
                "kimi-k2.6",
            ),
            providers.getValue(BuiltinProviders.KIMI_ID).models.map { it.modelId },
        )
        assertTrue(providers.getValue(BuiltinProviders.BAILIAN_ID).models.any { it.modelId == "kimi-k3" })
    }

    @Test
    fun providerAndModelCustomHeadersSurviveRoomRoundTrip() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val updated = provider.copyForTest(
            customHeaders = listOf(CustomHeader("x-provider", "1")),
        ).let { openAi ->
            openAi.copy(
                models = openAi.models.mapIndexed { index, model ->
                    if (index == 0) {
                        model.copy(customHeaders = listOf(CustomHeader("x-model", "2")))
                    } else {
                        model
                    }
                }
            )
        }

        ProviderRepository.updateProvider(updated)
        ModelRepository.saveModel(
            provider.id,
            updated.models.first(),
        )

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        assertEquals(listOf("x-provider"), restored.customHeaders.map { it.name })
        assertEquals(listOf("x-model"), restored.models.first().customHeaders.map { it.name })
    }

    @Test
    fun selectedRuntimeConfigUsesUpdatedProviderApiKey() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = (ProviderRepository.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting)
            .copy(apiKey = "sk-test-key")

        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.setSelectedProviderId(provider.id)

        val config = RuntimeConfigRepository.currentRuntimeConfig()
        requireNotNull(config)
        assertEquals(provider.id, config.providerId)
        assertEquals("sk-test-key", config.apiKey)
    }

    @Test
    fun switchingProvidersRestoresEachProvidersSelectedModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val openAi = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val anthropic = ProviderRepository.providerById(BuiltinProviders.ANTHROPIC_ID)!!
        val openAiModel = openAi.models[1]
        val anthropicModel = anthropic.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(openAi.id)
        SettingsDataStore.clearSelectedModelIdForProvider(anthropic.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        RuntimeConfigRepository.setSelectedModelId(openAiModel.id)
        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        RuntimeConfigRepository.setSelectedModelId(anthropicModel.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        assertEquals(openAiModel.id, SettingsDataStore.settings().selectedModelId)

        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        assertEquals(anthropicModel.id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun repairSelectionMigratesLegacyActiveModelToProviderMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val model = provider.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(provider.id)
        SettingsDataStore.updateSettings {
            it.copy(selectedProviderId = provider.id, selectedModelId = model.id)
        }

        ProviderRepository.repairSelection()

        assertEquals(model.id, SettingsDataStore.selectedModelIdForProvider(provider.id))
    }

    @Test
    fun windowUpgradeFillsExistingPresetsAndReachesPickerAndRuntimeWithoutAddingModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val sol = provider.models.first { it.modelId == "gpt-6.1-sol" }.copy(contextWindow = null, contextWindowOverride = null)
        val custom = provider.models.first { it.modelId == "gpt-6-luna" }.copy(contextWindowOverride = 64_000)
        ProviderRepository.replaceModels(provider.id, listOf(sol, custom))
        SettingsDataStore.setSelection(provider.id, sol.id)
        SettingsDataStore.setOfficialModelCatalogRevision(2)
        ProviderRepository.ensureBuiltInsMerged()
        val restored = ProviderRepository.providerById(provider.id)!!
        assertEquals(listOf(sol.id, custom.id), restored.models.map { it.id })
        assertEquals(1_050_000, restored.models.first().effectiveContextWindow)
        assertEquals(custom, restored.models.last())
        assertEquals(1_050_000, RuntimeConfigRepository.currentRuntimeConfig()!!.contextWindow)
        val picker = AgentModelPickerProjector.project(ProviderRepository.allProviders(), provider.id, sol.id)
        assertEquals(1_050_000, picker.selectedModel!!.contextWindow)
        ProviderRepository.replaceModels(provider.id, restored.models.map { it.copy(contextWindowOverride = null) })
        ProviderRepository.ensureBuiltInsMerged()
        assertTrue(ProviderRepository.providerById(provider.id)!!.models.all { it.contextWindowOverride == null })
    }

    @Test
    fun windowUpgradeUsesRemoteMetadataAndDoesNotInventCodexLimitsOrModifyManualModels() = runBlocking {
        val remote = Model(id = "remote-window", modelId = "gpt-6.1-sol", displayName = "远端", source = ModelSource.REMOTE,
            contextWindow = 272_000)
        val unknown = Model(id = "unknown-window", modelId = "gpt-6-astra", displayName = "未标注", source = ModelSource.REMOTE)
        val manual = Model(id = "manual-window", modelId = "gpt-6-luna", displayName = "手动")
        val provider = OpenAiCompatibleProviderSetting(id = "codex-window", name = "Codex", baseUrl = "https://chatgpt.com/backend-api/codex",
            authMode = ProviderAuthModes.OPENAI_CHATGPT, models = listOf(remote, unknown, manual))
        ProviderRepository.addProvider(provider)
        SettingsDataStore.setOfficialModelCatalogRevision(2)
        ProviderRepository.ensureBuiltInsMerged()
        val restored = ProviderRepository.providerById(provider.id)!!.models
        assertEquals(remote.copy(contextWindowOverride = 272_000), restored[0])
        assertEquals(unknown, restored[1])
        assertEquals(manual, restored[2])
    }

    @Test
    fun catalogUpgradeRemovesRetiredPresetsWithoutRestoringPresetsOverRemoteModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val original = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting
        for (mode in listOf(ProviderAuthModes.API_KEY, ProviderAuthModes.OPENAI_CHATGPT)) {
            ProviderRepository.updateProvider(original.copy(authMode = mode))
            val remote = Model(id = "remote-sol", modelId = "gpt-6.1-sol", displayName = "远端 Sol", source = ModelSource.REMOTE)
            val manual = Model(id = "manual", modelId = "custom-model", displayName = "用户模型")
            val obsolete = Model(id = "old-preset", modelId = "gpt-5.5", displayName = "旧预设", source = ModelSource.CATALOG)
            ProviderRepository.replaceModels(original.id, listOf(remote, manual, obsolete))
            SettingsDataStore.setSelection(original.id, remote.id)
            SettingsDataStore.setOfficialModelCatalogRevision(1)

            ProviderRepository.ensureBuiltInsMerged()
            assertEquals(listOf(remote, manual), ProviderRepository.providerById(original.id)!!.models)
            assertEquals(remote.id, SettingsDataStore.settings().selectedModelId)
            ProviderRepository.ensureBuiltInsMerged()
            assertEquals(listOf(remote, manual), ProviderRepository.providerById(original.id)!!.models)
        }
    }

    @Test
    fun unsynchronizedConnectionReceivesOnlyTheNewPresetAndRetiredPresetsAreRemoved() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val luna = provider.models.first { it.modelId == "gpt-6-luna" }.copy(isEnabled = false)
        val obsolete = Model(id = "old-sol", modelId = "gpt-6-sol", displayName = "旧 Sol", source = ModelSource.CATALOG)
        val manual = Model(id = "manual-sol", modelId = "custom-model", displayName = "自定义")
        ProviderRepository.replaceModels(provider.id, listOf(luna, obsolete, manual))
        SettingsDataStore.setOfficialModelCatalogRevision(1)
        ProviderRepository.ensureBuiltInsMerged()
        val models = ProviderRepository.providerById(provider.id)!!.models
        assertEquals(luna, models.first { it.id == luna.id })
        assertEquals(manual, models.first { it.id == manual.id })
        assertEquals(setOf("gpt-6-luna", "gpt-6.1-sol", "custom-model"), models.mapTo(mutableSetOf()) { it.modelId })
        ModelRepository.deleteModel(provider.id, models.first { it.modelId == "gpt-6.1-sol" }.id)
        ProviderRepository.ensureBuiltInsMerged()
        assertTrue(ProviderRepository.providerById(provider.id)!!.models.none { it.modelId == "gpt-6.1-sol" })
    }

    @Test
    fun catalogUpgradeCorrectsOnlyLegacyBaseReasoningCapabilities() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val legacyByProvider = mapOf(
            BuiltinProviders.ANTHROPIC_ID to mapOf(
                "claude-fable-5" to ModelReasoningCapabilities(
                    supportedEfforts = listOf(
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH,
                        ReasoningEffort.MAX,
                    ),
                    defaultEffort = ReasoningEffort.HIGH,
                    defaultEnabled = true,
                    canDisable = true,
                ),
            ),
            BuiltinProviders.STEPFUN_ID to mapOf(
                "step-3.7-flash" to ModelReasoningCapabilities(defaultEnabled = true, mandatory = true),
            ),
        )
        val override = ModelReasoningCapabilities(defaultEnabled = true, mandatory = true)
        val original = legacyByProvider.map { (providerId, legacy) ->
            val provider = ProviderRepository.providerById(providerId)!!
            val changed = provider.models.map { model ->
                if (model.modelId !in legacy) model else model.copy(
                    displayName = "自定义 ${model.displayName}",
                    reasoningCapabilities = legacy.getValue(model.modelId),
                    reasoningCapabilitiesOverride = override,
                    sortOrder = model.sortOrder + 20,
                    customHeaders = listOf(CustomHeader("x-model", "retained")),
                )
            }
            ProviderRepository.replaceModels(providerId, changed)
            provider to changed
        }
        SettingsDataStore.setOfficialModelCatalogRevision(0)

        ProviderRepository.ensureBuiltInsMerged()

        original.forEach { (provider, changed) ->
            val officialById = OfficialModelCatalog.modelsForProvider(provider).associateBy { it.modelId }
            val restoredById = ProviderRepository.providerById(provider.id)!!.models.associateBy { it.id }
            changed.filter { it.modelId in legacyByProvider.getValue(provider.id) }.forEach { prior ->
                assertEquals(
                    prior.copy(reasoningCapabilities = officialById.getValue(prior.modelId).reasoningCapabilities),
                    restoredById.getValue(prior.id),
                )
            }
        }
    }
}

private fun ProviderSetting.copyForTest(
    customHeaders: List<CustomHeader>,
): OpenAiCompatibleProviderSetting =
    (this as OpenAiCompatibleProviderSetting).copy(customHeaders = customHeaders)
