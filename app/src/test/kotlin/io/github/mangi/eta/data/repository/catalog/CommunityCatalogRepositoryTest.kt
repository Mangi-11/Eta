package io.github.mangi.eta.data.repository.catalog

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.provider.CommunityCatalogModel
import io.github.mangi.eta.data.provider.CommunityCatalogParser
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.data.repository.ProviderRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunityCatalogRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "catalog/models-dev-api.json").delete()
    }

    @Test
    fun bundledSnapshotIsReadableWithoutNetworkOrCache() = runBlocking {
        val catalog = CommunityCatalogRepository.loadLocal(context)

        assertEquals(CommunityCatalogSource.SNAPSHOT, catalog.source)
        assertTrue(catalog.providers.size > 100)
        assertTrue(catalog.providers.any { it.models.isNotEmpty() })
        assertTrue(catalog.providers.any { it.id == "zai" })
        assertTrue(catalog.providers.any { it.id == "xai" })
        assertFalse(catalog.providers.any { it.id == "google" })
    }

    @Test
    fun malformedCacheFallsBackToBundledSnapshot() = runBlocking {
        val cache = File(context.filesDir, "catalog/models-dev-api.json")
        cache.parentFile?.mkdirs()
        cache.writeText("{invalid")

        val catalog = CommunityCatalogRepository.loadLocal(context)

        assertEquals(CommunityCatalogSource.SNAPSHOT, catalog.source)
        assertTrue(catalog.providers.isNotEmpty())
    }

    @Test
    fun validCacheTakesPriorityOverBundledSnapshot() = runBlocking {
        val cache = File(context.filesDir, "catalog/models-dev-api.json")
        cache.parentFile?.mkdirs()
        cache.writeText(
            """{"cached":{"name":"Cached Provider","npm":"@ai-sdk/openai-compatible","api":"https://cached.example.com/v1","models":{"chat":{"name":"Chat","tool_call":true,"limit":{"context":128000},"modalities":{"input":["text"],"output":["text"]}}}}}"""
        )

        val catalog = CommunityCatalogRepository.loadLocal(context)

        assertEquals(CommunityCatalogSource.CACHE, catalog.source)
        assertEquals(listOf("cached"), catalog.providers.map { it.id })
    }

    @Test
    fun newProviderImportsOnlySelectedModelsAndStartsDisabledWithoutCredentials() {
        val selection = CommunityCatalogParser.parse(
            raw = """
                {
                  "sample": {
                    "name": "Sample",
                    "npm": "@ai-sdk/openai-compatible",
                    "api": "https://models.example.com/v1",
                    "models": {
                      "text-only": {
                        "name": "Text Only",
                        "attachment": true,
                        "tool_call": true,
                        "modalities": {"input": ["text"], "output": ["text"]},
                        "limit": {"context": 128000}
                      },
                      "vision": {
                        "name": "Vision",
                        "attachment": true,
                        "tool_call": true,
                        "modalities": {"input": ["text", "image"], "output": ["text"]},
                        "limit": {"context": 128000}
                      }
                    }
                  }
                }
            """.trimIndent(),
            source = CommunityCatalogSource.SNAPSHOT,
        ).providers.single()
        val provider = CommunityCatalogRepository.newProvider(
            selection = selection,
            modelIds = setOf("text-only"),
        )

        assertTrue(provider is CustomProviderSetting)
        assertFalse(provider.isEnabled)
        assertEquals("", provider.apiKey)
        assertEquals(listOf("text-only"), provider.models.map { it.modelId })
        assertEquals(ModelSource.CATALOG, provider.models.single().source)
        assertFalse(provider.models.single().supportsVision)
        assertNull(provider.models.single().attachment)
    }

    @Test
    fun newProviderRejectsEmptyOrStaleModelSelection() {
        val provider = CommunityCatalogProvider(
            id = "sample",
            name = "Sample",
            baseUrl = "https://models.example.com/v1",
            models = listOf(candidate("available", listOf("text"))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CommunityCatalogRepository.newProvider(provider, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommunityCatalogRepository.newProvider(provider, setOf("removed"))
        }
    }

    @Test
    fun importedProviderDoesNotReplaceCurrentSelection() = runBlocking {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        SettingsDataStore.setSelection(null, null)
        SettingsDataStore.setOfficialModelCatalogRevision(0)
        ProviderRepository.ensureBuiltInsMerged()
        val before = SettingsDataStore.settings()
        val candidate = CommunityCatalogProvider(
            id = "sample",
            name = "Sample",
            baseUrl = "https://models.example.com/v1",
            models = listOf(candidate("chat", listOf("text"))),
        )

        val added = ProviderRepository.addProvider(
            CommunityCatalogRepository.newProvider(candidate, setOf("chat"))
        )

        assertFalse(added.isEnabled)
        assertEquals(listOf("chat"), ProviderRepository.providerById(added.id)?.models?.map { it.modelId })
        assertEquals(before.selectedProviderId, SettingsDataStore.settings().selectedProviderId)
        assertEquals(before.selectedModelId, SettingsDataStore.settings().selectedModelId)
    }

    private fun candidate(id: String, input: List<String>) = CommunityCatalogModel(
        modelId = id,
        displayName = id,
        contextWindow = 128_000,
        inputModalities = input,
        releaseDate = "2026-09-01",
        reasoning = true,
        structuredOutput = true,
        supportsTemperature = false,
    )
}
