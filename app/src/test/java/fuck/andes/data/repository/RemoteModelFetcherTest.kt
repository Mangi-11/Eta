package fuck.andes.data.repository

import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.provider.OfficialModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelFetcherTest {
    @Test
    fun parsesOpenAiCompatibleModelsFromExplicitMetadata() {
        val models = RemoteModelFetcher.parseOpenAiModels(
            """
            {
              "object":"list",
              "data":[
                {
                  "id":"gpt-5.5",
                  "owned_by":"openai",
                  "input_modalities":["text","image"],
                  "tool_call":true,
                  "reasoning":true,
                  "context_window":400000
                },
                {
                  "id":"qwen3.7-plus",
                  "display_name":"Qwen 3.7 Plus",
                  "vision":true
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-5.5", "qwen3.7-plus"), models.map { it.modelId })
        assertTrue(models.first().supportsVision)
        assertTrue(models.first().supportsTools)
        assertTrue(models.first().supportsReasoning)
        assertEquals(400000, models.first().contextWindow)
        assertEquals("Qwen 3.7 Plus", models.last().displayName)
    }

    @Test
    fun enrichesKnownModelsFromOfficialCatalog() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Bailian",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
        )

        val models = OfficialModelCatalog.enrich(
            provider = provider,
            models = RemoteModelFetcher.parseOpenAiModels(
                """{"data":[{"id":"qwen3.7-plus"},{"id":"kimi-k2.6"}]}"""
            )
        )

        val byId = models.associateBy { it.modelId }
        assertTrue(byId.getValue("qwen3.7-plus").supportsVision)
        assertTrue(byId.getValue("kimi-k2.6").supportsVision)
        assertTrue(byId.getValue("kimi-k2.6").supportsTools)
        assertEquals("Kimi K2.6", byId.getValue("kimi-k2.6").displayName)
    }

    @Test
    fun enrichesMimoMiniMaxAndStepfunFromOfficialCatalog() {
        val mimoModels = OfficialModelCatalog.enrich(
            provider = OpenAiCompatibleProviderSetting(
                id = "mimo",
                name = "MiMo",
                baseUrl = "https://api.xiaomimimo.com/v1",
                sourceType = ProviderSourceTypes.MIMO,
            ),
            models = RemoteModelFetcher.parseOpenAiModels("""{"data":[{"id":"mimo-v2.5"},{"id":"mimo-v2.5-pro"}]}""")
        ).associateBy { it.modelId }
        assertTrue(mimoModels.getValue("mimo-v2.5").supportsVision)
        assertEquals(1_000_000, mimoModels.getValue("mimo-v2.5-pro").contextWindow)

        val minimaxModels = OfficialModelCatalog.enrich(
            provider = OpenAiCompatibleProviderSetting(
                id = "minimax",
                name = "MiniMax",
                baseUrl = "https://api.minimaxi.com/v1",
                sourceType = ProviderSourceTypes.MINIMAX,
            ),
            models = RemoteModelFetcher.parseOpenAiModels("""{"data":[{"id":"MiniMax-M3"}]}""")
        ).associateBy { it.modelId }
        assertTrue(minimaxModels.getValue("MiniMax-M3").supportsVision)
        assertEquals(1_000_000, minimaxModels.getValue("MiniMax-M3").contextWindow)

        val stepfunModels = OfficialModelCatalog.enrich(
            provider = OpenAiCompatibleProviderSetting(
                id = "stepfun",
                name = "StepFun",
                baseUrl = "https://api.stepfun.com/v1",
                sourceType = ProviderSourceTypes.STEPFUN,
            ),
            models = RemoteModelFetcher.parseOpenAiModels("""{"data":[{"id":"step-3.7-flash"}]}""")
        ).associateBy { it.modelId }
        assertTrue(stepfunModels.getValue("step-3.7-flash").supportsVision)
        assertEquals(1, stepfunModels.size)
    }

    @Test
    fun parsesKimiListModelsMetadata() {
        val models = RemoteModelFetcher.parseOpenAiModels(
            """
            {
              "object":"list",
              "data":[
                {
                  "id":"kimi-k2.6",
                  "owned_by":"moonshot",
                  "context_length":256000,
                  "supports_image_in":true,
                  "supports_video_in":true,
                  "supports_reasoning":true
                }
              ]
            }
            """.trimIndent()
        )

        val model = models.single()
        assertEquals("kimi-k2.6", model.modelId)
        assertEquals(256000, model.contextWindow)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsReasoning)
        assertEquals(listOf("text", "image", "video"), model.inputModalities)
    }
}
