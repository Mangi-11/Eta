package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort

/** 仅明确确认的模型启用原生能力，不能由 generateContent 目录能力推断工具混用。 */
internal object GeminiModelCapabilities {
    private data class Capabilities(
        val reasoning: ModelReasoningCapabilities,
        val hostedSearch: Boolean,
    )

    private val lowToHigh = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
    private val minimalToHigh = listOf(ReasoningEffort.MINIMAL) + lowToHigh
    val configurableEfforts: List<ReasoningEffort> = minimalToHigh
    private val knownModels = mapOf(
        "gemini-4-carbon" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-4-argon" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.8-flash" to capabilities(lowToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.6-flash" to capabilities(minimalToHigh, ReasoningEffort.MEDIUM, hostedSearch = true),
        "gemini-3.5-flash-lite" to capabilities(minimalToHigh, ReasoningEffort.MINIMAL, hostedSearch = true),
        "gemini-3.1-pro-preview" to capabilities(lowToHigh, ReasoningEffort.HIGH, hostedSearch = true),
        "gemini-3-flash-preview" to capabilities(minimalToHigh, ReasoningEffort.HIGH, hostedSearch = true),
        "gemini-3-pro-preview" to capabilities(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningEffort.HIGH, hostedSearch = true,
        ),
    )

    fun reasoningCapabilities(modelId: String): ModelReasoningCapabilities? = knownModels[normalize(modelId)]?.reasoning

    fun resolveReasoning(model: Model): ModelReasoningCapabilities = when (model.reasoningOverride) {
        false -> ModelReasoningCapabilities()
        true -> model.reasoningCapabilitiesOverride ?: ModelReasoningCapabilities()
        null -> if (model.reasoning == false) ModelReasoningCapabilities() else {
            model.reasoningCapabilities ?: requestReasoningCapabilities(model.modelId)
        }
    }

    // 未知模型只使用服务端默认值；不据此推断支持思考或必须思考。
    fun requestReasoningCapabilities(modelId: String): ModelReasoningCapabilities =
        reasoningCapabilities(modelId) ?: ModelReasoningCapabilities()

    fun supportsHostedSearch(modelId: String): Boolean = knownModels[normalize(modelId)]?.hostedSearch == true

    fun normalize(modelId: String): String = modelId.trim().removePrefix("models/").lowercase()

    private fun capabilities(
        efforts: List<ReasoningEffort>,
        default: ReasoningEffort,
        hostedSearch: Boolean = false,
    ) = Capabilities(
        reasoning = ModelReasoningCapabilities(
            supportedEfforts = efforts,
            defaultEffort = default,
            defaultEnabled = true,
            mandatory = true,
        ),
        hostedSearch = hostedSearch,
    )
}
