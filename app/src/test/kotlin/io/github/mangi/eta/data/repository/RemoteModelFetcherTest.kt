package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.OfficialModelCatalog
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
        assertTrue(models.all { it.source == ModelSource.REMOTE })
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
                """{"data":[{"id":"qwen3.7-plus"},{"id":"kimi-k2.7-code"},{"id":"kimi-k2.6"}]}"""
            )
        )

        val byId = models.associateBy { it.modelId }
        assertTrue(byId.getValue("qwen3.7-plus").supportsVision)
        assertTrue(byId.getValue("kimi-k2.7-code").supportsVision)
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
    fun keepsExplicitRemoteCapabilitiesAheadOfOfficialCatalog() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Bailian",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
        )

        val models = OfficialModelCatalog.enrich(
            provider = provider,
            models = RemoteModelFetcher.parseOpenAiModels(
                """
                {
                  "data":[
                    {
                      "id":"qwen3.7-plus",
                      "input_modalities":["text"],
                      "vision":false,
                      "tool_call":false,
                      "reasoning":false
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val model = models.single()
        assertEquals(listOf("text"), model.inputModalities)
        assertFalse(model.supportsVision)
        assertFalse(model.supportsTools)
        assertFalse(model.supportsReasoning)
    }

    @Test
    fun leavesMissingStandardModelMetadataAvailableForCatalogEnrichment() {
        val parsed = RemoteModelFetcher.parseOpenAiModels(
            """{"data":[{"id":"kimi-k2.7-code","owned_by":"moonshot"}]}"""
        ).single()

        assertTrue(parsed.inputModalities.isEmpty())
        assertTrue(parsed.outputModalities.isEmpty())

        val enriched = OfficialModelCatalog.enrich(
            catalogId = ProviderSourceTypes.BAILIAN,
            models = listOf(parsed),
        ).single()
        assertEquals(listOf("text", "image", "video"), enriched.inputModalities)
        assertEquals(listOf("text"), enriched.outputModalities)
        assertTrue(enriched.supportsVision)
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
        assertEquals(256000, model.effectiveContextWindow)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsReasoning)
        assertEquals(listOf("text", "image", "video"), model.inputModalities)
    }

    @Test
    fun parsesCurrentOpenRouterModelSchema() {
        val models = RemoteModelFetcher.parseOpenAiModels(
            """
            {
              "data":[
                {
                  "id":"google/gemini-3.6-flash",
                  "name":"Google: Gemini 3.6 Flash",
                  "context_length":1048576,
                  "architecture":{
                    "input_modalities":["text","image","video"],
                    "output_modalities":["text"]
                  },
                  "reasoning":{
                    "mandatory":true,
                    "default_enabled":true,
                    "supported_efforts":["high","medium","low","minimal"]
                  },
                  "supported_parameters":[
                    "reasoning",
                    "structured_outputs",
                    "temperature",
                    "tool_choice",
                    "tools"
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val model = models.single()
        assertEquals("google/gemini-3.6-flash", model.modelId)
        assertEquals("Google: Gemini 3.6 Flash", model.displayName)
        assertEquals(1_048_576, model.contextWindow)
        assertEquals(listOf("text", "image", "video"), model.inputModalities)
        assertEquals(listOf("text"), model.outputModalities)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsReasoning)
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
            ),
            model.reasoningCapabilities?.selectableEfforts,
        )
        assertTrue(model.reasoningCapabilities?.mandatory == true)
        assertEquals(true, model.structuredOutput)
        assertEquals(true, model.supportsTemperature)
    }

    @Test
    fun parsesThinkingBudgetAndToggleMetadata() {
        val model = RemoteModelFetcher.parseOpenAiModels(
            """
            {
              "data":[
                {
                  "id":"vendor/reasoning-model",
                  "supported_parameters":["enable_thinking","thinking_budget"]
                }
              ]
            }
            """.trimIndent()
        ).single()

        assertTrue(model.supportsReasoning)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            model.reasoningCapabilities?.selectableEfforts,
        )
        assertTrue(model.reasoningCapabilities?.supportsBudget == true)
    }

    @Test
    fun parsesAnthropicContextAndCapabilityMetadata() {
        val model = RemoteModelFetcher.parseAnthropicModels(
            """
            {
              "data":[
                {
                  "id":"claude-opus-4-8",
                  "display_name":"Claude Opus 4.8",
                  "max_input_tokens":1000000,
                  "capabilities":{
                    "image_input":{"supported":true},
                    "structured_outputs":{"supported":true},
                    "effort":{
                      "supported":true,
                      "low":{"supported":true},
                      "medium":{"supported":true},
                      "high":{"supported":true},
                      "xhigh":{"supported":true},
                      "max":{"supported":true}
                    },
                    "thinking":{"supported":true}
                  }
                }
              ]
            }
            """.trimIndent()
        ).single()

        assertEquals(1_000_000, model.contextWindow)
        assertEquals(1_000_000, model.effectiveContextWindow)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsReasoning)
        assertEquals(true, model.structuredOutput)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            model.reasoningCapabilities?.selectableEfforts,
        )
    }

    @Test
    fun ignoresNonPositiveContextMetadata() {
        val models = RemoteModelFetcher.parseOpenAiModels(
            """{"data":[{"id":"zero","context_length":0},{"id":"negative","context_window":-1}]}"""
        )
        val anthropic = RemoteModelFetcher.parseAnthropicModels(
            """{"data":[{"id":"claude-test","max_input_tokens":0}]}"""
        ).single()

        assertTrue(models.all { it.contextWindow == null })
        assertTrue(models.all { it.effectiveContextWindow == null })
        assertEquals(null, anthropic.contextWindow)
    }

    @Test
    fun ignoresUnexpectedMetadataTypesWithoutFailingWholeModelList() {
        val models = RemoteModelFetcher.parseOpenAiModels(
            """
            {
              "data":[
                {
                  "id":"example/chat-model",
                  "display_name":{"localized":"Example"},
                  "owned_by":{"name":"example"},
                  "context_length":{"tokens":128000},
                  "reasoning":{"default_enabled":false},
                  "input_modalities":["text",{"type":"image"}],
                  "supported_parameters":["tools",{"name":"temperature"}]
                }
              ]
            }
            """.trimIndent()
        )

        val model = models.single()
        assertEquals("example/chat-model", model.displayName)
        assertEquals(null, model.ownedBy)
        assertEquals(null, model.contextWindow)
        assertEquals(listOf("text"), model.inputModalities)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsReasoning)
    }

    @Test
    fun keepsConversationalModelsFromRemoteCatalog() {
        val chatIds = listOf(
            "qwen3.7-plus",
            "qwen3-vl-plus",
            "kimi-k2.7-code",
            "glm-5.2",
            "gpt-5.5",
            "step-3.7-flash",
        )
        chatIds.forEach { id ->
            assertTrue(id, RemoteModelFetcher.isChatCapableModel(modelWithId(id)))
        }
    }

    @Test
    fun filtersNonConversationalModelsFromRemoteCatalog() {
        val nonChatIds = listOf(
            "fun-asr-flash-2026-06-15",
            "paraformer-realtime-v2",
            "qwen-tts-2026-05-20",
            "cosyvoice-v3-plus",
            "qwen-image-2.0-pro-2026-06-22",
            "wanx2.1-t2v-turbo",
            "text-embedding-v4",
            "gte-rerank-v2",
            "qwen-ocr",
        )
        nonChatIds.forEach { id ->
            assertFalse(id, RemoteModelFetcher.isChatCapableModel(modelWithId(id)))
        }
    }

    @Test
    fun modelWithoutTextOutputIsNotChatCapable() {
        val imageOnly = modelWithId("custom-model").copy(outputModalities = listOf("image"))
        assertFalse(RemoteModelFetcher.isChatCapableModel(imageOnly))
    }

    private fun modelWithId(modelId: String): Model =
        Model(id = modelId, modelId = modelId, displayName = modelId)
}
