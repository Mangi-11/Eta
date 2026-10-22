package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.agent.auth.ProviderAuthException
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
    fun newRemoteModelsSkipsModelsAlreadyAddedIgnoringCase() = runBlocking {
        ProviderRepository.addProvider(
            provider(models = listOf(Model(id = "kept", modelId = "Qwen-Max", displayName = "Qwen Max"))),
        )

        val fresh = ModelRepository.newRemoteModels(
            PROVIDER_ID,
            listOf(
                Model(id = "", modelId = "qwen-max", displayName = "qwen-max", source = ModelSource.REMOTE),
                Model(id = "", modelId = "qwen-flash", displayName = "qwen-flash", source = ModelSource.REMOTE),
                Model(id = "", modelId = "QWEN-FLASH", displayName = "QWEN-FLASH", source = ModelSource.REMOTE),
                Model(id = "", modelId = " ", displayName = "blank", source = ModelSource.REMOTE),
            ),
        )

        assertEquals(listOf("qwen-flash"), fresh.map { it.modelId })
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
                    contextWindow = 262_144,
                    source = ModelSource.REMOTE,
                ),
                Model(
                    id = "remote-unpicked",
                    modelId = "remote-unpicked",
                    displayName = "Remote Unpicked",
                    source = ModelSource.REMOTE,
                ),
            ),
            adoptedModelIds = setOf("REMOTE-NEW"),
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
        assertEquals(262_144, restored.getValue("remote-new").effectiveContextWindow)
        assertEquals(null, restored.getValue("catalog-model").effectiveContextWindow)

        val emptyResult = ModelRepository.syncRemoteModels(PROVIDER_ID, emptyList(), emptySet())
        assertFalse(emptyResult.applied)
        assertEquals(restored.keys, ModelRepository.modelsByProvider(PROVIDER_ID).mapTo(mutableSetOf()) { it.modelId })
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

    @Test
    fun catalogCapturedBeforeAuthenticationModeChangeCannotModifyModels() = runBlocking {
        val original = provider(models = listOf(Model(id = "kept", modelId = "kept", displayName = "保留")))
        ProviderRepository.addProvider(original)
        val catalog = RemoteModelCatalog(
            listOf(Model(id = "incoming", modelId = "incoming", displayName = "旧请求结果")),
            requireNotNull(ProviderRepository.providerById(PROVIDER_ID)), null,
        )
        ProviderRepository.updateProvider(original.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT))

        val failure = runCatching { ModelRepository.syncRemoteCatalog(catalog, setOf("incoming")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("kept"), ModelRepository.modelsByProvider(PROVIDER_ID).map { it.id })
    }

    @Test
    fun codexCatalogWithoutAnAccountBindingIsRejectedBeforeDatabaseMutation() = runBlocking {
        val original = provider(models = listOf(Model(id = "kept", modelId = "kept", displayName = "保留")))
            .copy(authMode = ProviderAuthModes.OPENAI_CHATGPT)
        ProviderRepository.addProvider(original)
        val catalog = RemoteModelCatalog(emptyList(), requireNotNull(ProviderRepository.providerById(PROVIDER_ID)), null)

        val failure = runCatching { ModelRepository.syncRemoteCatalog(catalog, emptySet()) }.exceptionOrNull()
        assertTrue(failure is ProviderAuthException)
        assertEquals("oauth_session_changed", (failure as ProviderAuthException).code)
        assertEquals(listOf("kept"), ModelRepository.modelsByProvider(PROVIDER_ID).map { it.id })
    }

    @Test
    fun boundApiCatalogUsesTheCurrentConnectionAndRejectsAChangedKey() = runBlocking {
        val original = provider().copy(apiKey = "fixture-original-key")
        ProviderRepository.addProvider(original)
        val catalog = RemoteModelCatalog(
            listOf(Model(id = "remote-one", modelId = "remote-one", displayName = "远端")),
            requireNotNull(ProviderRepository.providerById(PROVIDER_ID)), null,
        )
        assertTrue(ModelRepository.syncRemoteCatalog(catalog, setOf("remote-one")).applied)
        ProviderRepository.updateProvider(original.copy(apiKey = "fixture-replacement-key"))

        val failure = runCatching { ModelRepository.syncRemoteCatalog(catalog, emptySet()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("remote-one"), ModelRepository.modelsByProvider(PROVIDER_ID).map { it.id })
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
