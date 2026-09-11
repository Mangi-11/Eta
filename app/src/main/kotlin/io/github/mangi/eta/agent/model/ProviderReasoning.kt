package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlin.math.max
import org.json.JSONObject

internal object ProviderReasoning {
    private const val ANTHROPIC_LARGE_MAX_TOKENS = 65_536

    fun applyOpenAiCompatibleRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        val effort = validatedEffort(config)
        val sourceType = sourceType(config)
        if (
            config.reasoningCapabilities == null &&
            !isLegacyReasoningModel(sourceType, config.model)
        ) {
            return
        }
        if (effort == ReasoningEffort.DEFAULT) {
            applyProviderDefault(request, config, sourceType)
            return
        }
        when (sourceType) {
            ProviderSourceTypes.BAILIAN -> applyBailian(request, config, effort)
            ProviderSourceTypes.SILICONFLOW -> applySiliconFlow(request, config, effort)
            ProviderSourceTypes.DEEPSEEK -> applyDeepSeek(request, effort)
            ProviderSourceTypes.MOONSHOT -> applyMoonshot(request, config, effort)
            ProviderSourceTypes.MIMO -> applyToggleOnlyProvider(request, "MiMo", effort)
            ProviderSourceTypes.MINIMAX -> unsupportedEffort("MiniMax", effort)
            ProviderSourceTypes.OPENROUTER -> applyOpenRouter(request, effort)
            ProviderSourceTypes.STEPFUN -> applyStepFun(request, effort)
            ProviderSourceTypes.OPENAI -> applyOpenAi(request, effort)
            ProviderSourceTypes.CUSTOM -> applyNamedReasoningEffort(request, effort)
        }
    }

    fun applyResponsesRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        if (config.reasoningCapabilities == null) return
        val effort = validatedEffort(config)
        if (sourceType(config) == ProviderSourceTypes.OPENAI && effort == ReasoningEffort.MAX) {
            unsupportedEffort("OpenAI", effort)
        }
        request.put(
            "reasoning",
            JSONObject().apply {
                if (effort == ReasoningEffort.OFF) {
                    put("effort", "none")
                } else {
                    put("summary", "auto")
                    if (effort != ReasoningEffort.DEFAULT) put("effort", effort.wireValue)
                }
            },
        )
    }

    private fun applyProviderDefault(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        sourceType: String,
    ) {
        if (request.has("thinking")) return
        val model = config.model.trim().lowercase()
        if (
            (sourceType == ProviderSourceTypes.MOONSHOT || sourceType == ProviderSourceTypes.BAILIAN) &&
            model.startsWith("kimi-k2.6")
        ) {
            request.put(
                "thinking",
                JSONObject()
                    .put("type", "enabled")
                    .put("keep", "all")
            )
        }
    }

    fun applyAnthropicRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        val effort = validatedEffort(config)
        if (effort == ReasoningEffort.DEFAULT) return
        if (effort == ReasoningEffort.OFF) {
            request.put("thinking", JSONObject().put("type", "disabled"))
            request.optJSONObject("output_config")?.let { outputConfig ->
                outputConfig.remove("effort")
                if (outputConfig.length() == 0) request.remove("output_config")
            }
            return
        }
        require(effort != ReasoningEffort.MINIMAL) {
            "Anthropic 不支持 Minimal thinking effort"
        }
        request.put(
            "thinking",
            JSONObject()
                .put("type", "adaptive")
                .put("display", "summarized")
        )
        val outputConfig = request.optJSONObject("output_config") ?: JSONObject()
        outputConfig.put("effort", effort.wireValue)
        request.put("output_config", outputConfig)
        if (effort == ReasoningEffort.XHIGH || effort == ReasoningEffort.MAX) {
            request.put("max_tokens", max(request.optInt("max_tokens", 0), ANTHROPIC_LARGE_MAX_TOKENS))
        }
    }

    private fun applyBailian(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        val model = config.model.trim().lowercase()
        when {
            model.startsWith("qwen3.7-") -> applyQwenBudget(request, config, effort)
            model.startsWith("qwen3.8-") -> applyNamedReasoningEffort(request, effort)
            "deepseek" in model || model.startsWith("kimi-k3") ->
                applyNamedReasoningEffort(request, effort)
            model.startsWith("kimi-k2.6") || model.startsWith("kimi-k2.5") ->
                applyToggleOnlyProvider(
                    request = request,
                    providerName = "百炼 Kimi",
                    effort = effort,
                    keepAll = model.startsWith("kimi-k2.6"),
                )
            else -> applyNamedReasoningEffort(request, effort)
        }
    }

    private fun applyQwenBudget(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        if (effort == ReasoningEffort.OFF) {
            request.put("enable_thinking", false)
            request.remove("thinking_budget")
            return
        }
        val requestedBudget = when (effort) {
            ReasoningEffort.MINIMAL -> unsupportedEffort("百炼 Qwen", effort)
            ReasoningEffort.LOW -> 4_096
            ReasoningEffort.MEDIUM -> 16_384
            ReasoningEffort.HIGH -> 32_768
            ReasoningEffort.XHIGH -> 65_536
            ReasoningEffort.MAX -> config.reasoningCapabilities?.maxBudgetTokens ?: 65_536
            ReasoningEffort.OFF,
            ReasoningEffort.DEFAULT -> return
        }
        request.put("enable_thinking", true)
        request.put("thinking_budget", clampBudgetToCompletionLimit(request, requestedBudget))
    }

    private fun clampBudgetToCompletionLimit(request: JSONObject, requestedBudget: Int): Int {
        if (!request.has("max_completion_tokens") || request.isNull("max_completion_tokens")) {
            return requestedBudget
        }
        val completionLimit = request.optInt("max_completion_tokens", requestedBudget)
        if (completionLimit <= 0) return requestedBudget
        val answerReserve = max(2_048, completionLimit / 8)
        return requestedBudget.coerceAtMost((completionLimit - answerReserve).coerceAtLeast(1))
    }

    private fun applySiliconFlow(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        if (effort == ReasoningEffort.OFF) {
            request.put("enable_thinking", false)
            request.remove("thinking_budget")
            return
        }
        val budget = when (effort) {
            ReasoningEffort.MINIMAL -> 128
            ReasoningEffort.LOW -> 1_024
            ReasoningEffort.MEDIUM -> 4_096
            ReasoningEffort.HIGH -> 8_192
            ReasoningEffort.XHIGH -> 16_384
            ReasoningEffort.MAX -> 32_768
            ReasoningEffort.OFF,
            ReasoningEffort.DEFAULT -> return
        }.coerceAtMost(config.reasoningCapabilities?.maxBudgetTokens ?: 32_768)
        if (config.reasoningCapabilities?.canDisable == true) {
            request.put("enable_thinking", true)
        }
        request.put("thinking_budget", budget)
    }

    private fun applyDeepSeek(request: JSONObject, effort: ReasoningEffort) {
        applyThinkingToggle(request, effort)
        if (effort != ReasoningEffort.OFF) {
            val providerEffort = when (effort) {
                ReasoningEffort.MINIMAL -> unsupportedEffort("DeepSeek", effort)
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH -> ReasoningEffort.HIGH
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX -> ReasoningEffort.MAX
                ReasoningEffort.DEFAULT,
                ReasoningEffort.OFF -> return
            }
            request.put("reasoning_effort", providerEffort.wireValue)
        } else {
            request.remove("reasoning_effort")
        }
    }

    private fun applyMoonshot(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        val model = config.model.trim().lowercase()
        when {
            model.startsWith("kimi-k3") -> {
                require(effort in setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)) {
                    "Kimi K3 不支持 ${effort.displayName} thinking effort"
                }
                applyNamedReasoningEffort(request, effort)
            }
            model.startsWith("kimi-k2.7-code") -> unsupportedEffort("Kimi K2.7 Code", effort)
            model.startsWith("kimi-k2.6") || model.startsWith("kimi-k2.5") ->
                applyToggleOnlyProvider(
                    request = request,
                    providerName = "Kimi",
                    effort = effort,
                    keepAll = model.startsWith("kimi-k2.6"),
                )
            else -> applyNamedReasoningEffort(request, effort)
        }
    }

    private fun applyToggleOnlyProvider(
        request: JSONObject,
        providerName: String,
        effort: ReasoningEffort,
        keepAll: Boolean = false,
    ) {
        if (effort != ReasoningEffort.OFF) unsupportedEffort(providerName, effort)
        applyThinkingToggle(request, effort, keepAll)
    }

    private fun applyStepFun(request: JSONObject, effort: ReasoningEffort) {
        require(effort == ReasoningEffort.LOW || effort == ReasoningEffort.HIGH) {
            "StepFun 不支持 ${effort.displayName} thinking effort"
        }
        applyNamedReasoningEffort(request, effort)
    }

    private fun applyThinkingToggle(
        request: JSONObject,
        effort: ReasoningEffort,
        keepAll: Boolean = false,
    ) {
        val enabled = effort != ReasoningEffort.OFF
        val thinking = JSONObject().put("type", if (enabled) "enabled" else "disabled")
        if (enabled && keepAll) thinking.put("keep", "all")
        request.put("thinking", thinking)
    }

    private fun applyOpenRouter(request: JSONObject, effort: ReasoningEffort) {
        val reasoning = request.optJSONObject("reasoning") ?: JSONObject()
        reasoning.put("effort", if (effort == ReasoningEffort.OFF) "none" else effort.wireValue)
        request.put("reasoning", reasoning)
    }

    private fun applyOpenAi(request: JSONObject, effort: ReasoningEffort) {
        if (effort == ReasoningEffort.MAX) unsupportedEffort("OpenAI", effort)
        applyNamedReasoningEffort(request, effort)
    }

    private fun applyNamedReasoningEffort(request: JSONObject, effort: ReasoningEffort) {
        request.put("reasoning_effort", if (effort == ReasoningEffort.OFF) "none" else effort.wireValue)
    }

    private fun unsupportedEffort(providerName: String, effort: ReasoningEffort): Nothing =
        throw IllegalArgumentException("$providerName 不支持 ${effort.displayName} thinking effort")

    private fun validatedEffort(config: AgentModelClient.ModelConfig): ReasoningEffort {
        val effort = config.effectiveReasoningEffort
        val capabilities = config.reasoningCapabilities ?: return effort
        require(effort in capabilities.selectableEfforts) {
            "当前模型不支持 ${effort.displayName} thinking effort"
        }
        require(!capabilities.mandatory || effort != ReasoningEffort.OFF) {
            "当前模型强制启用思考，不能选择 Off"
        }
        return effort
    }

    private fun sourceType(config: AgentModelClient.ModelConfig): String =
        ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )

    private fun isLegacyReasoningModel(sourceType: String, modelId: String): Boolean {
        val model = modelId.trim().lowercase()
        return when (sourceType) {
            ProviderSourceTypes.OPENAI -> model.startsWith("gpt-5") || model.startsWith("o")
            ProviderSourceTypes.ANTHROPIC -> model.startsWith("claude-")
            ProviderSourceTypes.BAILIAN ->
                model.startsWith("qwen3.7-") ||
                    model.startsWith("qwen3.8-") ||
                    model.startsWith("kimi-") ||
                    "deepseek" in model
            ProviderSourceTypes.DEEPSEEK -> true
            ProviderSourceTypes.MOONSHOT -> model.startsWith("kimi-")
            ProviderSourceTypes.MIMO -> model.startsWith("mimo-v2.5")
            ProviderSourceTypes.STEPFUN -> model.startsWith("step-3.5-flash-2603")
            ProviderSourceTypes.OPENROUTER -> true
            ProviderSourceTypes.MINIMAX,
            ProviderSourceTypes.SILICONFLOW,
            ProviderSourceTypes.CUSTOM -> false
            else -> false
        }
    }
}
