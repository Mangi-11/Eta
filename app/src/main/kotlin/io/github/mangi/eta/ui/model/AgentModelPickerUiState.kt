package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Locale

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
)

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
)

@Immutable
internal data class SessionStatsSummaryUi(
    val turns: Int = 0,
    val steps: Int = 0,
    val llmMs: Long = 0L,
    val toolMs: Long = 0L,
    val ttftMs: Long = 0L,
    val ttftSteps: Int = 0,
    val decodeMs: Long = 0L,
    val decodeTokens: Int = 0,
    val promptTokens: Int = 0,
    val uncachedInputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val hasTokenUsage: Boolean = false,
) {
    val totalTokens: Int
        get() = promptTokens + outputTokens

    val hasPerformanceStats: Boolean
        get() = llmMs > 0L || toolMs > 0L || ttftSteps > 0 || (decodeMs > 0L && decodeTokens > 0)
}

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
    val sessionStats: SessionStatsSummaryUi = SessionStatsSummaryUi(),
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .filter { it.authMode == ProviderAuthModes.API_KEY || ProviderAuthModes.isOAuth(it.authMode) }
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter {
                ProviderAuthModes.isOAuth(it.authMode) ||
                    (it.authMode == ProviderAuthModes.API_KEY && it.apiKey.isNotBlank())
            }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
        )
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
        )
}

internal val AgentModelPickerUiState.modelCount: Int
    get() = providerGroups.sumOf { it.models.size }

/** 服务商名称也参与匹配：搜索服务商时列出它的全部模型。 */
internal fun AgentModelPickerUiState.filterGroups(query: String): List<AgentModelProviderGroupUi> {
    val search = ModelSearchQuery(query)
    if (search.isEmpty) return providerGroups
    return providerGroups.mapNotNull { group ->
        val models = group.models.filter { model ->
            search.matches(model.displayName, model.modelId, group.providerName)
        }
        models.takeIf(List<*>::isNotEmpty)?.let { group.copy(models = it) }
    }
}

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi {
    val sessionStats = deriveSessionStatsSummary(messages)
    for (message in messages.asReversed()) {
        when (message) {
            is AgentMessageUi -> message.usage?.contextTokens?.let {
                return AgentContextUsageUi(it, selectedModel?.contextWindow, sessionStats)
            }
            is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
                return AgentContextUsageUi(null, selectedModel?.contextWindow, sessionStats)
            }
            else -> Unit
        }
    }
    return AgentContextUsageUi(null, selectedModel?.contextWindow, sessionStats)
}

internal fun deriveSessionStatsSummary(messages: List<AgentChatMessageUi>): SessionStatsSummaryUi {
    var turns = 0
    var steps = 0
    var llmMs = 0L
    var toolMs = 0L
    var ttftMs = 0L
    var ttftSteps = 0
    var decodeMs = 0L
    var decodeTokens = 0
    var promptTokens = 0
    var uncachedInputTokens = 0
    var cachedTokens = 0
    var outputTokens = 0
    var reasoningTokens = 0
    var hasTokenUsage = false

    messages.forEach { message ->
        if (message !is AgentMessageUi) return@forEach
        val usage = message.usage ?: return@forEach
        val turnSteps = (usage.stepCount ?: 1).coerceAtLeast(1)
        turns += 1
        steps += turnSteps
        usage.llmMs?.takeIf { it > 0L }?.let { llmMs += it }
        usage.toolMs?.takeIf { it > 0L }?.let { toolMs += it }
        val recordedTtftSteps = usage.ttftSteps ?: if (usage.ttftMs != null) 1 else 0
        if (recordedTtftSteps > 0 && usage.ttftMs != null && usage.ttftMs > 0L) {
            ttftMs += usage.ttftMs
            ttftSteps += recordedTtftSteps
        }
        val turnDecodeTokens = usage.decodeTokens ?: usage.outputTokens
        if (usage.decodeMs != null && usage.decodeMs > 0L && turnDecodeTokens != null && turnDecodeTokens > 0) {
            decodeMs += usage.decodeMs
            decodeTokens += turnDecodeTokens
        }
        val rawInput = usage.inputTokens ?: 0
        val rawCache = usage.cachedTokens ?: 0
        val rawOut = usage.outputTokens ?: 0
        val rawReasoning = usage.reasoningTokens ?: 0
        if (usage.inputTokens != null || usage.cachedTokens != null || usage.outputTokens != null || usage.contextTokens != null) {
            hasTokenUsage = true
            val turnPrompt = when {
                rawInput >= rawCache && rawInput > 0 -> rawInput
                rawInput > 0 && rawCache > 0 -> rawInput + rawCache
                else -> maxOf(rawInput, rawCache, ((usage.contextTokens ?: 0) - rawOut).coerceAtLeast(0))
            }
            val turnUncached = (turnPrompt - rawCache).coerceAtLeast(0)
            promptTokens += turnPrompt
            uncachedInputTokens += turnUncached
            cachedTokens += rawCache
            outputTokens += rawOut
            reasoningTokens += rawReasoning
        }
    }

    return SessionStatsSummaryUi(
        turns = turns,
        steps = steps,
        llmMs = llmMs,
        toolMs = toolMs,
        ttftMs = ttftMs,
        ttftSteps = ttftSteps,
        decodeMs = decodeMs,
        decodeTokens = decodeTokens,
        promptTokens = promptTokens,
        uncachedInputTokens = uncachedInputTokens,
        cachedTokens = cachedTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        hasTokenUsage = hasTokenUsage,
    )
}

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No usage data yet",
    noLimitText: String = "Context window unknown, so automatic compaction is off",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextWindow == null || usage.contextWindow <= 0 -> noLimitText
    usage.contextTokens == null -> noUsageText
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
