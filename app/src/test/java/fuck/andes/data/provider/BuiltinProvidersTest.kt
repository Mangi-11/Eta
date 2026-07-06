package fuck.andes.data.provider

import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinProvidersTest {
    @Test
    fun builtInsKeepStablePresetMetadataWithoutInliningModels() {
        val providers = BuiltinProviders.PROVIDERS.associateBy { it.id }

        assertTrue(providers[BuiltinProviders.OPENAI_ID] is OpenAiCompatibleProviderSetting)
        assertTrue(providers[BuiltinProviders.ANTHROPIC_ID] is AnthropicProviderSetting)
        assertEquals(0, providers.getValue(BuiltinProviders.OPENAI_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.BAILIAN_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.MIMO_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.MINIMAX_ID).models.size)
        assertEquals(0, providers.getValue(BuiltinProviders.STEPFUN_ID).models.size)
        assertEquals("https://api.moonshot.cn/v1", providers.getValue(BuiltinProviders.KIMI_ID).baseUrl)
        assertEquals(ProviderSourceTypes.BAILIAN, providers.getValue(BuiltinProviders.BAILIAN_ID).sourceType)
        assertEquals(ProviderSourceTypes.MOONSHOT, providers.getValue(BuiltinProviders.KIMI_ID).sourceType)
        assertEquals(ProviderSourceTypes.MIMO, providers.getValue(BuiltinProviders.MIMO_ID).sourceType)
        assertEquals(ProviderSourceTypes.MINIMAX, providers.getValue(BuiltinProviders.MINIMAX_ID).sourceType)
        assertEquals(ProviderSourceTypes.STEPFUN, providers.getValue(BuiltinProviders.STEPFUN_ID).sourceType)
    }
}
