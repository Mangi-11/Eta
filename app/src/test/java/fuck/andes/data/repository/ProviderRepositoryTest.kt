package fuck.andes.data.repository

import android.content.Context
import fuck.andes.data.datastore.SettingsDataStore
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.CustomHeader
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.provider.BuiltinProviders
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
class ProviderRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("fuck_andes.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(providerId = null, modelId = null)
        }
    }

    @Test
    fun builtInProvidersRoundTripThroughRoomWithModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()

        val providers = ProviderRepository.allProviders().associateBy { it.id }

        assertTrue(providers.getValue(BuiltinProviders.ANTHROPIC_ID) is AnthropicProviderSetting)
        assertEquals(
            listOf("gpt-5.5"),
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.modelId },
        )
        assertEquals(
            listOf("claude-fable-5", "claude-opus-4-8", "claude-sonnet-5"),
            providers.getValue(BuiltinProviders.ANTHROPIC_ID).models.map { it.modelId },
        )
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
}

private fun ProviderSetting.copyForTest(
    customHeaders: List<CustomHeader>,
): OpenAiCompatibleProviderSetting =
    (this as OpenAiCompatibleProviderSetting).copy(customHeaders = customHeaders)
