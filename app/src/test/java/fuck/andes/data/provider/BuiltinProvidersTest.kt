package fuck.andes.data.provider

import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinProvidersTest {
    @Test
    fun builtInsUseStableIdsAndCurrentDefaultModels() {
        val providers = BuiltinProviders.PROVIDERS.associateBy { it.id }

        assertTrue(providers[BuiltinProviders.OPENAI_ID] is OpenAiCompatibleProviderSetting)
        assertTrue(providers[BuiltinProviders.ANTHROPIC_ID] is AnthropicProviderSetting)
        assertEquals(
            listOf("gpt-5.5"),
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.modelId }
        )
        assertEquals(
            listOf("claude-fable-5", "claude-opus-4-8", "claude-sonnet-5"),
            providers.getValue(BuiltinProviders.ANTHROPIC_ID).models.map { it.modelId }
        )
        assertEquals(
            listOf("qwen3.7-max", "qwen3.7-plus"),
            providers.getValue(BuiltinProviders.DASHSCOPE_ID).models.map { it.modelId }
        )
        assertEquals(
            listOf("deepseek-v4-pro", "deepseek-v4-flash"),
            providers.getValue(BuiltinProviders.DEEPSEEK_ID).models.map { it.modelId }
        )
    }
}
