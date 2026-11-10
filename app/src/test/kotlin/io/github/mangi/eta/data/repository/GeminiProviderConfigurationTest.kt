package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.ui.pages.providers.ProviderConfigDraft
import io.github.mangi.eta.ui.pages.providers.providerAccountAuthMode
import io.github.mangi.eta.ui.pages.providers.toProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiProviderConfigurationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(3)
        }
    }

    @Test
    fun customNativeProxyRoundTripsCopiesAndProjectsWithoutAnOpenAiEndpoint() = runBlocking {
        val source = GeminiProviderSetting(
            id = "native-gemini", name = "原生代理", baseUrl = "https://proxy.example/v1beta", apiKey = "fixture-key",
            hostedWebSearchEnabled = true,
            customHeaders = listOf(CustomHeader("x-fixture", "value")),
            customBody = listOf(CustomBody("generation_config.temperature", JsonPrimitive(0.4))),
            models = listOf(Model(id = "native-model", modelId = "gemini-3.8-flash", displayName = "Flash")),
        )
        ProviderRepository.addProvider(source)
        val stored = requireNotNull(ProviderRepository.providerById(source.id))
        assertEquals(source, stored)
        val copied = requireNotNull(ProviderRepository.copyProvider(source.id))
        assertTrue(copied is GeminiProviderSetting)
        assertEquals(ProviderSourceTypes.GEMINI, copied.sourceType)
        assertTrue(copied.id != source.id && copied.models.single().id != source.models.single().id)
        assertTrue(copied.hostedWebSearchEnabled)
        val config = RuntimeConfigRepository.buildRuntimeConfig(stored, stored.models.single().copy(reasoningOverride = false))
        assertEquals(ProviderTypes.GEMINI, config.providerType)
        assertEquals(ProviderSourceTypes.GEMINI, config.providerSourceType)
        assertEquals("", config.openAiEndpointMode)
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(listOf(ReasoningEffort.DEFAULT), requireNotNull(config.reasoningCapabilities).selectableEfforts)
        val raw = Json.encodeToString<ProviderSetting>(stored)
        assertEquals(stored, Json.decodeFromString<ProviderSetting>(raw))
    }

    @Test
    fun unknownManualModelUsesDefaultWithoutInventingThinkingCapabilities() {
        val config = RuntimeConfigRepository.buildRuntimeConfig(
            GeminiProviderSetting(id = "manual-gemini", name = "Gemini"),
            Model(id = "manual-model", modelId = "gemini-future", displayName = "手动模型"),
        )
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(ReasoningEffort.DEFAULT, config.effectiveReasoningEffort)
        val capabilities = requireNotNull(config.reasoningCapabilities)
        assertEquals(listOf(ReasoningEffort.DEFAULT), capabilities.selectableEfforts)
        assertNull(capabilities.defaultEnabled)
        assertFalse(capabilities.mandatory)
    }

    @Test
    fun unknownModelManualEffortsSurvivePersistenceAndRuntimeProjection() = runBlocking {
        val manual = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            defaultEnabled = true, mandatory = true)
        val provider = GeminiProviderSetting(id = "manual-config", name = "Gemini", models = listOf(
            Model(id = "future", modelId = "gemini-future", displayName = "未来模型",
                reasoningOverride = true, reasoningCapabilitiesOverride = manual),
        ))
        ProviderRepository.addProvider(provider)
        val stored = requireNotNull(ProviderRepository.providerById(provider.id))
        val model = stored.models.single()
        assertEquals(manual, model.reasoningCapabilitiesOverride)
        assertEquals(manual, RuntimeConfigRepository.buildRuntimeConfig(stored, model).reasoningCapabilities)
        assertEquals(listOf(ReasoningEffort.DEFAULT), requireNotNull(
            RuntimeConfigRepository.buildRuntimeConfig(stored, model.copy(reasoningOverride = null,
                reasoningCapabilitiesOverride = null)).reasoningCapabilities).selectableEfforts)
    }

    @Test
    fun carbonUpgradePreservesExistingModelAndDoesNotRestoreDeletedPresets() = runBlocking {
        val carbon = Model(id = "existing-carbon", modelId = "gemini-4-carbon", displayName = "我的 Carbon",
            contextWindowOverride = 200_000)
        val provider = requireNotNull(BuiltinProviders.providerById(BuiltinProviders.GEMINI_ID))
            .withModels(listOf(carbon))
        ProviderRepository.addProvider(provider)
        SettingsDataStore.setOfficialModelCatalogRevision(4)
        ProviderRepository.ensureBuiltInsMerged()
        assertEquals(listOf(carbon), requireNotNull(ProviderRepository.providerById(provider.id)).models)
        assertEquals(5, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun builtinMergeAppendsGeminiAndPreservesTheSelectedCompatibleConnection() = runBlocking {
        val source = CustomProviderSetting(
            id = "existing-gemini-compatible", name = "原有兼容连接",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", apiKey = "fixture-key",
            models = listOf(Model(id = "existing-model", modelId = "gemini-3.8-flash", displayName = "原模型")),
        )
        ProviderRepository.addProvider(source)
        SettingsDataStore.setSelection(source.id, source.models.single().id)
        ProviderRepository.ensureBuiltInsMerged()
        val providers = ProviderRepository.allProviders()
        assertEquals(source, providers.first())
        assertTrue(providers.last() is GeminiProviderSetting)
        assertEquals(BuiltinProviders.GEMINI_ID, providers.last().id)
        assertEquals(source.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(source.models.single().id, SettingsDataStore.settings().selectedModelId)
        assertEquals(4, providers.last().models.size)
        assertTrue(providers.last().models.any { it.modelId == "gemini-4-carbon" })
    }

    @Test
    fun configurationDraftRetainsNativeProtocolAndSearchToggle() {
        val source = GeminiProviderSetting(id = "native", name = "Gemini")
        val draft = ProviderConfigDraft.from(source).copy(
            name = "修改后的名称", baseUrl = "https://proxy.example/v1beta", apiKey = "fixture-key",
            hostedWebSearchEnabled = true,
        )
        val updated = draft.toProvider(source)
        assertTrue(updated is GeminiProviderSetting)
        assertEquals(draft, ProviderConfigDraft.from(updated))
        assertEquals("", draft.endpointMode)
        assertNull(providerAccountAuthMode(updated))
    }

    @Test
    fun backupUsesNewVersionAndRestoresTheNativeType() = runBlocking {
        val source = GeminiProviderSetting(id = "backup-gemini", name = "备份 Gemini", hostedWebSearchEnabled = true)
        ProviderRepository.addProvider(source)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val raw = output.toString(Charsets.UTF_8.name())
        val document = Json.decodeFromString<EtaBackupDocument>(raw)
        assertEquals(4, document.schemaVersion)
        assertTrue(document.schemaVersion > 3)
        assertEquals(ProviderTypes.GEMINI, document.providers.single { it.provider.id == source.id }.provider.type)
        ProviderRepository.deleteProvider(source.id)
        assertNull(ProviderRepository.providerById(source.id))
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        val restored = ProviderRepository.providerById(source.id)
        assertTrue(restored is GeminiProviderSetting)
        assertTrue(requireNotNull(restored).hostedWebSearchEnabled)
        assertFalse(restored.isBuiltIn)
    }
}
