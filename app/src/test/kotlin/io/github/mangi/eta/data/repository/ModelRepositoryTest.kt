package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelRepositoryTest {
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
        }
    }

    @Test
    fun saveModelCommitsValidatedManualDraftOnlyOnce() = runBlocking {
        addEmptyProvider()
        val draft = Model(id = "", modelId = "  custom-model  ", displayName = "  自定义模型  ")

        assertTrue(ModelRepository.modelsByProvider(PROVIDER_ID).isEmpty())
        val saved = ModelRepository.saveModel(PROVIDER_ID, draft)

        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).single()
        assertEquals(saved.id, restored.id)
        assertEquals("custom-model", restored.modelId)
        assertEquals("自定义模型", restored.displayName)
        assertEquals(ModelSource.MANUAL, restored.source)
    }

    @Test
    fun saveModelRejectsBlankAndDuplicateIdsWithoutChangingStorage() = runBlocking {
        addEmptyProvider()
        ModelRepository.saveModel(
            PROVIDER_ID,
            Model(id = "", modelId = "model-a", displayName = "Model A"),
        )

        val blankFailure = runCatching {
            ModelRepository.saveModel(
                PROVIDER_ID,
                Model(id = "", modelId = " ", displayName = "空模型"),
            )
        }
        val duplicateFailure = runCatching {
            ModelRepository.saveModel(
                PROVIDER_ID,
                Model(id = "", modelId = "MODEL-A", displayName = "重复模型"),
            )
        }

        assertTrue(blankFailure.isFailure)
        assertTrue(duplicateFailure.isFailure)
        assertEquals(listOf("model-a"), ModelRepository.modelsByProvider(PROVIDER_ID).map { it.modelId })
    }

    @Test
    fun remoteSyncPreservesManualAndCatalogModelsAndOnlyRemovesStaleRemoteModels() = runBlocking {
        ProviderRepository.addProvider(
            provider(
                models = listOf(
                    Model(
                        id = "manual-id",
                        modelId = "manual-model",
                        displayName = "Manual",
                        contextWindow = 128_000,
                        contextWindowOverride = 256_000,
                        reasoning = true,
                        reasoningCapabilities = ModelReasoningCapabilities(
                            supportedEfforts = listOf(ReasoningEffort.HIGH),
                        ),
                        reasoningOverride = true,
                        reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                            supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                            canDisable = true,
                        ),
                        source = ModelSource.MANUAL,
                    ),
                    Model(
                        id = "catalog-id",
                        modelId = "catalog-model",
                        displayName = "Catalog",
                        source = ModelSource.CATALOG,
                        isBuiltIn = true,
                    ),
                    Model(
                        id = "stale-id",
                        modelId = "remote-stale",
                        displayName = "Stale",
                        source = ModelSource.REMOTE,
                    ),
                )
            )
        )

        val result = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            listOf(
                Model(
                    id = "remote-manual-match",
                    modelId = "manual-model",
                    displayName = "Manual From Remote",
                    contextWindow = 1_000_000,
                    toolCall = true,
                    source = ModelSource.REMOTE,
                ),
                Model(
                    id = "remote-new",
                    modelId = "remote-new",
                    displayName = "Remote New",
                    source = ModelSource.REMOTE,
                ),
            ),
        )

        assertTrue(result.applied)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.removedCount)
        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).associateBy { it.modelId }
        assertEquals(setOf("manual-model", "catalog-model", "remote-new"), restored.keys)
        assertEquals(ModelSource.MANUAL, restored.getValue("manual-model").source)
        assertTrue(restored.getValue("manual-model").supportsTools)
        assertEquals(1_000_000, restored.getValue("manual-model").contextWindow)
        assertEquals(256_000, restored.getValue("manual-model").effectiveContextWindow)
        assertEquals(
            listOf(ReasoningEffort.OFF, ReasoningEffort.DEFAULT, ReasoningEffort.MINIMAL),
            restored.getValue("manual-model").effectiveReasoningCapabilities?.selectableEfforts,
        )
        assertEquals(ModelSource.CATALOG, restored.getValue("catalog-model").source)
        assertEquals(ModelSource.REMOTE, restored.getValue("remote-new").source)

        val emptyResult = ModelRepository.syncRemoteModels(PROVIDER_ID, emptyList())
        assertFalse(emptyResult.applied)
        assertEquals(restored.keys, ModelRepository.modelsByProvider(PROVIDER_ID).mapTo(mutableSetOf()) { it.modelId })
    }

    @Test
    fun successfulCodexCatalogReplacesMatchedFallbackAndRemovesUnlistedFallback() = runBlocking {
        ProviderRepository.addProvider(
            provider(
                models = listOf(
                    Model(
                        id = "fallback-match",
                        modelId = "gpt-6-astra",
                        displayName = "GPT-6 Astra (offline)",
                        isBuiltIn = true,
                        source = ModelSource.CATALOG,
                    ),
                    Model(
                        id = "fallback-unlisted",
                        modelId = "model-not-returned",
                        displayName = "Offline fallback",
                        isBuiltIn = true,
                        source = ModelSource.CATALOG,
                    ),
                ),
            ),
        )

        val result = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            listOf(
                Model(
                    id = "live-astra",
                    modelId = "gpt-6-astra",
                    displayName = "GPT-6 Astra",
                    source = ModelSource.REMOTE,
                    contextWindow = 272_000,
                ),
            ),
            preserveCatalogModels = false,
        )

        assertTrue(result.applied)
        val models = ModelRepository.modelsByProvider(PROVIDER_ID)
        assertEquals(listOf("gpt-6-astra"), models.map { it.modelId })
        assertEquals("fallback-match", models.single().id)
        assertEquals(ModelSource.REMOTE, models.single().source)
        assertFalse(models.single().isBuiltIn)
        assertEquals(272_000, models.single().contextWindow)
    }

    @Test
    fun deletedCodexRemoteModelStaysHiddenAfterRefreshIncludingAllHiddenCatalog() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val providerId = BuiltinProviders.OPENAI_CODEX_ID
        val fetched = listOf(
            Model(id = "online-one", modelId = "gpt-online-one", displayName = "One", source = ModelSource.REMOTE),
            Model(id = "online-two", modelId = "gpt-online-two", displayName = "Two", source = ModelSource.REMOTE),
        )
        assertTrue(ModelRepository.syncRemoteModels(providerId, fetched, preserveCatalogModels = false).applied)

        val one = ModelRepository.modelsByProvider(providerId).first { it.modelId == "gpt-online-one" }
        ModelRepository.deleteModel(providerId, one.id)
        ProviderRepository.ensureBuiltInsMerged()
        assertEquals(listOf("gpt-online-two"), ModelRepository.modelsByProvider(providerId).map { it.modelId })

        assertTrue(ModelRepository.syncRemoteModels(providerId, fetched, preserveCatalogModels = false).applied)
        assertEquals(listOf("gpt-online-two"), ModelRepository.modelsByProvider(providerId).map { it.modelId })

        val two = ModelRepository.modelsByProvider(providerId).single()
        ModelRepository.deleteModels(providerId, setOf(two.id))
        assertTrue(ModelRepository.syncRemoteModels(providerId, fetched, preserveCatalogModels = false).applied)
        assertTrue(ModelRepository.modelsByProvider(providerId).isEmpty())

        ProviderRepository.resetBuiltIn(providerId)
        assertTrue(ModelRepository.syncRemoteModels(providerId, fetched, preserveCatalogModels = false).applied)
        assertEquals(
            listOf("gpt-online-one", "gpt-online-two"),
            ModelRepository.modelsByProvider(providerId).map { it.modelId },
        )
    }

    @Test
    fun providerConfigSaveDoesNotOverwriteModelsAddedFromAnotherDraft() = runBlocking {
        addEmptyProvider()
        val staleProviderDraft = ProviderRepository.providerById(PROVIDER_ID)!!
        ModelRepository.saveModel(
            PROVIDER_ID,
            Model(id = "", modelId = "model-a", displayName = "Model A"),
        )

        ProviderRepository.updateProvider(
            (staleProviderDraft as CustomProviderSetting).copy(apiKey = "new-key")
        )

        val restored = ProviderRepository.providerById(PROVIDER_ID) as CustomProviderSetting
        assertEquals("new-key", restored.apiKey)
        assertEquals(listOf("model-a"), restored.models.map { it.modelId })
    }

    private suspend fun addEmptyProvider() {
        ProviderRepository.addProvider(provider())
    }

    private fun provider(models: List<Model> = emptyList()): CustomProviderSetting =
        CustomProviderSetting(
            id = PROVIDER_ID,
            name = "Test Provider",
            baseUrl = "https://example.com/v1",
            models = models,
        )

    private companion object {
        const val PROVIDER_ID = "provider-test"
    }
}
