package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
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
    fun existingOfficialModelWithoutWindowIsResolvedForEveryRead() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val id = BuiltinProviders.DEEPSEEK_ID
        val stored = Model(id = "manual-flash", modelId = "deepseek-flash", displayName = "我的模型", isEnabled = false)
        ProviderRepository.replaceModels(id, listOf(stored))
        val expected = stored.copy(contextWindow = 1_048_576)
        assertEquals(expected, ProviderRepository.providerById(id)!!.models.single())
        assertEquals(expected, ProviderRepository.providerByModelId(stored.id)!!.models.single())
        assertEquals(expected, ProviderRepository.allProviders().first { it.id == id }.models.single())
        assertEquals(expected, ProviderRepository.providersFlow().first().first { it.id == id }.models.single())
        assertNull(EtaDatabase.get(context).providerDao().models(id).single().contextWindow)
    }

    @Test
    fun resolvedWindowReachesRuntimeAndChatWithTheSamePrecedence() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val id = BuiltinProviders.DEEPSEEK_ID
        val model = Model(id = "manual-flash", modelId = "deepseek-flash", displayName = "我的模型")
        val cases = listOf(
            model to 1_048_576,
            model.copy(contextWindow = 128_000) to 128_000,
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
    fun builtInProvidersRoundTripThroughRoomWithModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()

        val providers = ProviderRepository.allProviders().associateBy { it.id }

        assertTrue(providers.getValue(BuiltinProviders.ANTHROPIC_ID) is AnthropicProviderSetting)
        assertEquals(
            listOf(
                "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
                "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5",
            ),
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.modelId },
        )
        assertEquals(
            List(7) { ModelSource.CATALOG },
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
    fun codexNeverSeedsFallbackAndRemovesLegacyCatalogWithoutRestoringDeletedModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val codexId = BuiltinProviders.OPENAI_CODEX_ID
        ProviderRepository.resetBuiltIn(codexId)
        val initialModels = ProviderRepository.providerById(codexId)!!.models
        assertTrue(initialModels.map { "${it.modelId}:${it.source}" }.toString(), initialModels.isEmpty())

        ProviderRepository.replaceModels(codexId, listOf(
            Model(id = "legacy-codex-fallback", modelId = "legacy-model", displayName = "Legacy", source = ModelSource.CATALOG),
            Model(id = "live-codex-model", modelId = "live-model", displayName = "Live", source = ModelSource.REMOTE),
        ))
        ProviderRepository.ensureBuiltInsMerged()
        assertEquals(listOf("live-model"), ProviderRepository.providerById(codexId)!!.models.map { it.modelId })

        ModelRepository.deleteModel(codexId, "live-codex-model")
        ProviderRepository.ensureBuiltInsMerged()
        assertTrue(ProviderRepository.providerById(codexId)!!.models.isEmpty())
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
    fun catalogUpgradeAppendsOnlyNewIdsWithoutChangingExistingModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val retained = provider.models.first { it.modelId == "gpt-5.6-sol" }.copy(
            displayName = "用户命名",
            isEnabled = false,
            sortOrder = 20,
            customHeaders = listOf(CustomHeader("x-model", "retained")),
        )
        val selected = provider.models.first { it.modelId == "gpt-5.5" }.copy(sortOrder = 30)
        val sameIdManual = Model(
            id = "manual-gpt-6-sol",
            modelId = "GPT-6-SOL",
            displayName = "手动添加",
            sortOrder = 40,
        )
        ProviderRepository.replaceModels(provider.id, listOf(retained, selected, sameIdManual))
        SettingsDataStore.setSelection(provider.id, selected.id)
        SettingsDataStore.setOfficialModelCatalogRevision(0)

        ProviderRepository.ensureBuiltInsMerged()

        val merged = ProviderRepository.providerById(provider.id)!!.models
        assertEquals(retained, merged.first { it.id == retained.id })
        assertEquals(selected, merged.first { it.id == selected.id })
        assertEquals(sameIdManual.copy(contextWindow = 1_050_000), merged.first { it.id == sameIdManual.id })
        assertTrue(merged.none { it.modelId == "gpt-5.6-terra" })
        assertEquals(1, merged.count { it.modelId.equals("gpt-6-sol", ignoreCase = true) })
        assertEquals(41, merged.filter { it.id !in setOf(retained.id, selected.id, sameIdManual.id) }
            .minOf { it.sortOrder })
        assertEquals(selected.id, SettingsDataStore.settings().selectedModelId)
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())

        SettingsDataStore.setOfficialModelCatalogRevision(0)
        ProviderRepository.ensureBuiltInsMerged()
        assertEquals(merged, ProviderRepository.providerById(provider.id)!!.models)
    }

    @Test
    fun catalogUpgradeCorrectsOnlyLegacyBaseReasoningCapabilities() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val legacyOpenAi = ModelReasoningCapabilities(
            supportedEfforts = listOf(
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            defaultEffort = ReasoningEffort.MEDIUM,
            defaultEnabled = true,
            canDisable = true,
        )
        val legacyByProvider = mapOf(
            BuiltinProviders.OPENAI_ID to listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
            ).associateWith { legacyOpenAi },
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
