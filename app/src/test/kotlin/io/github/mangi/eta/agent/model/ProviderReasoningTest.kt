package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderReasoningTest {
    @Test
    fun mandatoryModelRejectsOffInsteadOfSilentlyDisablingReasoning() {
        val config = config(
            source = ProviderSourceTypes.MOONSHOT,
            model = "kimi-k3",
            effort = ReasoningEffort.OFF,
        ).copy(
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.HIGH),
                mandatory = true,
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(JSONObject(), config)
        }
    }

    @Test
    fun defaultDoesNotOverrideAdvancedRequestBody() {
        val request = JSONObject()
            .put("reasoning_effort", "medium")
            .put("thinking_budget", 1234)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.DEFAULT),
        )

        assertEquals("medium", request.getString("reasoning_effort"))
        assertEquals(1234, request.getInt("thinking_budget"))
    }

    @Test
    fun openAiNamedEffortOverridesAdvancedValue() {
        val request = JSONObject().put("reasoning_effort", "low")

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.XHIGH),
        )

        assertEquals("xhigh", request.getString("reasoning_effort"))
    }

    @Test
    fun gpt55And56RejectUnsupportedMinimalEffort() {
        for (model in listOf("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            assertThrows(IllegalArgumentException::class.java) {
                ProviderReasoning.applyResponsesRequest(
                    JSONObject(),
                    config(
                        source = ProviderSourceTypes.OPENAI,
                        model = model,
                        effort = ReasoningEffort.MINIMAL,
                    ),
                )
            }
        }
    }

    @Test
    fun openAiChatCompletionsStillRejectMaxEffort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(
                JSONObject(),
                config(source = ProviderSourceTypes.OPENAI, model = "gpt-5.5", effort = ReasoningEffort.MAX),
            )
        }
    }

    @Test
    fun gpt56MaxIsAcceptedByResponsesAndChatCompletions() {
        for (model in listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            val responses = JSONObject()
            ProviderReasoning.applyResponsesRequest(
                responses,
                config(source = ProviderSourceTypes.OPENAI, model = model, effort = ReasoningEffort.MAX),
            )
            assertEquals("max", responses.getJSONObject("reasoning").getString("effort"))

            val chat = JSONObject()
            ProviderReasoning.applyOpenAiCompatibleRequest(
                chat,
                config(source = ProviderSourceTypes.OPENAI, model = model, effort = ReasoningEffort.MAX),
            )
            assertEquals("max", chat.getString("reasoning_effort"))
        }
    }

    @Test
    fun openAiOffUsesDocumentedNoneValue() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.OFF),
        )

        assertEquals("none", request.getString("reasoning_effort"))
    }

    @Test
    fun gpt6ResponsesUsesExactEffortAndRejectsUnsupportedLevels() {
        val astra = JSONObject()
        ProviderReasoning.applyResponsesRequest(
            astra,
            config(
                source = ProviderSourceTypes.OPENAI,
                model = "gpt-6-astra",
                effort = ReasoningEffort.MAX,
            ),
        )
        assertEquals("max", astra.getJSONObject("reasoning").getString("effort"))

        for (model in listOf("gpt-6-sol", "gpt-6-luna")) {
            val request = JSONObject()
            ProviderReasoning.applyResponsesRequest(
                request,
                config(source = ProviderSourceTypes.OPENAI, model = model, effort = ReasoningEffort.OFF),
            )
            assertEquals("none", request.getJSONObject("reasoning").getString("effort"))
        }

        for (effort in listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL)) {
            assertThrows(IllegalArgumentException::class.java) {
                ProviderReasoning.applyResponsesRequest(
                    JSONObject(),
                    config(source = ProviderSourceTypes.OPENAI, model = "gpt-6-astra", effort = effort),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyResponsesRequest(
                JSONObject(),
                config(
                    source = ProviderSourceTypes.OPENAI,
                    model = "gpt-6-sol",
                    effort = ReasoningEffort.MINIMAL,
                ),
            )
        }
    }

    @Test
    fun anthropicMaxUsesAdaptiveThinkingAndLargeOutputLimit() {
        val request = JSONObject()
            .put("max_tokens", 4096)
            .put("output_config", JSONObject().put("other", true))

        ProviderReasoning.applyAnthropicRequest(
            request,
            config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.MAX),
        )

        assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
        assertEquals("max", request.getJSONObject("output_config").getString("effort"))
        assertTrue(request.getJSONObject("output_config").getBoolean("other"))
        assertEquals(65_536, request.getInt("max_tokens"))
    }

    @Test
    fun anthropicOffUsesDisabledThinkingAndRemovesOnlyEffortOverride() {
        val request = JSONObject()
            .put("thinking", JSONObject().put("type", "adaptive"))
            .put("output_config", JSONObject().put("effort", "high").put("other", true))

        ProviderReasoning.applyAnthropicRequest(
            request,
            config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.OFF),
        )

        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.getJSONObject("output_config").has("effort"))
        assertTrue(request.getJSONObject("output_config").getBoolean("other"))
    }

    @Test
    fun anthropicRejectsUnsupportedMinimalEffort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyAnthropicRequest(
                JSONObject(),
                config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.MINIMAL),
            )
        }
    }

    @Test
    fun deepSeekWritesThinkingTypeAndExactEffort() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.DEEPSEEK, effort = ReasoningEffort.MAX),
        )

        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("max", request.getString("reasoning_effort"))
    }

    @Test
    fun deepSeekFlashLowKeepsLowEffort() {
        val request = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.DEEPSEEK,
                model = "deepseek-flash",
                effort = ReasoningEffort.LOW,
            ),
        )
        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("low", request.getString("reasoning_effort"))
    }

    @Test
    fun kimiK3UsesTopLevelEffortWithoutThinkingObject() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.MOONSHOT,
                model = "kimi-k3",
                effort = ReasoningEffort.HIGH,
            ),
        )

        assertEquals("high", request.getString("reasoning_effort"))
        assertFalse(request.has("thinking"))
    }

    @Test
    fun qwenBudgetReservesAnswerTokensBelowCompletionLimit() {
        val request = JSONObject().put("max_completion_tokens", 20_000)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.BAILIAN,
                model = "qwen3.7-plus",
                effort = ReasoningEffort.HIGH,
            ),
        )

        assertTrue(request.getBoolean("enable_thinking"))
        assertEquals(17_500, request.getInt("thinking_budget"))
    }

    @Test
    fun qwen38NamedEffortClearsIncompatibleBudgetOverride() {
        val request = JSONObject().put("thinking_budget", 8192)
        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.BAILIAN,
                model = "qwen3.8-max",
                effort = ReasoningEffort.XHIGH,
            ),
        )
        assertEquals("xhigh", request.getString("reasoning_effort"))
        assertFalse(request.has("thinking_budget"))
    }

    @Test
    fun bailianNewModelsUseNamedEffort() {
        for ((model, effort, expected) in listOf(
            Triple("deepseek-v4.1-flash", ReasoningEffort.LOW, "low"),
            Triple("kimi-k3", ReasoningEffort.HIGH, "high"),
        )) {
            val request = JSONObject()
            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = ProviderSourceTypes.BAILIAN, model = model, effort = effort),
            )
            assertEquals(expected, request.getString("reasoning_effort"))
        }
    }

    @Test
    fun siliconFlowMapsEveryNamedLevelToBudget() {
        val budgets = mapOf(
            ReasoningEffort.MINIMAL to 128,
            ReasoningEffort.LOW to 1_024,
            ReasoningEffort.MEDIUM to 4_096,
            ReasoningEffort.HIGH to 8_192,
            ReasoningEffort.XHIGH to 16_384,
            ReasoningEffort.MAX to 32_768,
        )

        budgets.forEach { (effort, budget) ->
            val request = JSONObject()
            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = ProviderSourceTypes.SILICONFLOW, effort = effort),
            )
            assertEquals(budget, request.getInt("thinking_budget"))
        }
    }

    @Test
    fun mimoOffUsesDocumentedDisabledThinkingType() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.MIMO,
                model = "mimo-v2.6-pro",
                effort = ReasoningEffort.OFF,
            ),
        )

        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun openRouterUsesNestedReasoningEffort() {
        val request = JSONObject().put("reasoning", JSONObject().put("exclude", true))

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENROUTER, effort = ReasoningEffort.LOW),
        )

        assertEquals("low", request.getJSONObject("reasoning").getString("effort"))
        assertTrue(request.getJSONObject("reasoning").getBoolean("exclude"))
    }

    @Test
    fun stepFunUsesOnlyVerifiedNamedLevels() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.STEPFUN,
                model = "step-3.5-flash-2603",
                effort = ReasoningEffort.LOW,
            ),
        )

        assertEquals("low", request.getString("reasoning_effort"))

        for (model in listOf("step-5-preview", "step-3.7-flash")) {
            val mediumRequest = JSONObject()
            ProviderReasoning.applyOpenAiCompatibleRequest(
                mediumRequest,
                config(
                    source = ProviderSourceTypes.STEPFUN,
                    model = model,
                    effort = ReasoningEffort.MEDIUM,
                ),
            )
            assertEquals("medium", mediumRequest.getString("reasoning_effort"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(
                JSONObject(),
                config(
                    source = ProviderSourceTypes.STEPFUN,
                    model = "step-3.5-flash-2603",
                    effort = ReasoningEffort.MEDIUM,
                ),
            )
        }
    }

    @Test
    fun defaultOnlyProvidersDoNotInventRequestFields() {
        listOf(ProviderSourceTypes.MINIMAX, ProviderSourceTypes.CUSTOM).forEach { source ->
            val request = JSONObject().put("metadata", "kept")

            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = source, effort = ReasoningEffort.DEFAULT),
            )

            assertEquals(setOf("metadata"), request.keys().asSequence().toSet())
        }
    }

    private fun config(
        source: String,
        effort: ReasoningEffort,
        model: String = "test-model",
    ) = AgentModelClient.ModelConfig(
        providerSourceType = source,
        baseUrl = "https://example.com/v1",
        apiKey = "key",
        model = model,
        systemPrompt = "system",
        thinkingEnabled = effort.enablesReasoning,
        reasoningEffort = effort,
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = ReasoningEffort.entries.filter {
                it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT
            },
            canDisable = true,
        ),
    )
}
