package fuck.andes.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelFetcherTest {
    @Test
    fun parsesOpenAiCompatibleModels() {
        val models = RemoteModelFetcher.parseOpenAiModels(
            """{"object":"list","data":[{"id":"gpt-5.5","owned_by":"openai"},{"id":"qwen3.7-plus"}]}"""
        )

        assertEquals(listOf("gpt-5.5", "qwen3.7-plus"), models.map { it.modelId })
        assertTrue(models.first().supportsTools)
        assertTrue(models.first().supportsReasoning)
    }

    @Test
    fun parsesAnthropicModelsWithDisplayNames() {
        val models = RemoteModelFetcher.parseAnthropicModels(
            """{"data":[{"id":"claude-sonnet-5","display_name":"Claude Sonnet 5"}]}"""
        )

        assertEquals("claude-sonnet-5", models.single().modelId)
        assertEquals("Claude Sonnet 5", models.single().displayName)
        assertTrue(models.single().supportsVision)
    }
}
