package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentModelPickerProjectorTest {
    @Test
    fun project_keepsOnlyEnabledProvidersAndModelsAndResolvesSelection() {
        val selected = model(id = "model-selected", displayName = "GPT 5.6", contextWindow = 1_050_000)
        val providers = listOf(
            provider(
                id = "disabled-provider",
                name = "Disabled",
                enabled = false,
                models = listOf(model(id = "hidden-provider-model")),
            ),
            provider(
                id = "no-enabled-models",
                name = "No enabled models",
                models = listOf(model(id = "disabled-only-model", enabled = false)),
            ),
            provider(
                id = "openai",
                name = "OpenAI",
                sourceType = ProviderSourceTypes.OPENAI,
                models = listOf(
                    model(id = "disabled-model", enabled = false),
                    selected,
                ),
            ),
        )

        val result = AgentModelPickerProjector.project(
            providers = providers,
            selectedProviderId = "openai",
            selectedModelId = selected.id,
        )

        assertEquals(listOf("openai"), result.providerGroups.map { it.providerId })
        assertEquals(listOf(selected.id), result.providerGroups.single().models.map { it.id })
        assertEquals(selected.id, result.selectedModel?.id)
        assertEquals(1_050_000, result.selectedModel?.contextWindow)
    }

    @Test
    fun project_hidesProvidersWithoutApiKeyButPreservesCurrentSelection() {
        val selected = model(id = "selected", displayName = "Selected")
        val result = AgentModelPickerProjector.project(
            providers = listOf(
                provider(
                    id = "missing-key",
                    apiKey = "   ",
                    models = listOf(selected),
                ),
                provider(
                    id = "configured",
                    models = listOf(model(id = "available")),
                ),
            ),
            selectedProviderId = "missing-key",
            selectedModelId = selected.id,
        )

        assertEquals(listOf("configured"), result.providerGroups.map { it.providerId })
        assertEquals(selected.id, result.selectedModel?.id)
        assertEquals("missing-key", result.selectedModel?.providerId)
    }

    @Test
    fun providerGroups_expandCurrentByDefault() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "current",
            providerName = "Current",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = null,
        )
        val expanded = defaultExpandedModelProviderIds(selected)

        assertEquals(setOf("current"), expanded)
    }

    @Test
    fun latestContextUsage_usesLastMeasuredRoundAndSelectedWindow() {
        val selected = AgentModelOptionUi(
            id = "model",
            providerId = "provider",
            providerName = "Provider",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            modelId = "model",
            displayName = "Model",
            contextWindow = 100_000,
        )
        val messages = listOf(
            AgentMessageUi(id = "first", content = "one", usage = TokenUsageUi(contextTokens = 10_000)),
            AgentMessageUi(id = "missing", content = "two"),
            AgentMessageUi(id = "last", content = "three", usage = TokenUsageUi(contextTokens = 82_000)),
        )

        val usage = latestContextUsage(messages, selected)

        assertEquals(82_000, usage.contextTokens)
        assertEquals(100_000, usage.contextWindow)
        assertEquals(0.82f, usage.progress ?: 0f, 0.0001f)
        assertEquals("82K / 100K tokens · 82.0%", formatContextUsage(usage))
    }

    @Test
    fun contextUsageProgress_handlesMissingInvalidAndOverflowValues() {
        assertNull(contextUsageProgress(null, 100_000))
        assertNull(contextUsageProgress(10_000, null))
        assertNull(contextUsageProgress(10_000, 0))
        assertEquals(0f, contextUsageProgress(0, 100_000) ?: -1f, 0f)
        assertEquals(1f, contextUsageProgress(120_000, 100_000) ?: -1f, 0f)
        assertEquals("1.05M", formatCompactTokenCount(1_050_000))
        assertEquals(
            "No usage data yet",
            formatContextUsage(AgentContextUsageUi(contextTokens = null, contextWindow = 100_000)),
        )
        assertEquals(
            "12K tokens\nThis model has no context limit",
            formatContextUsage(AgentContextUsageUi(contextTokens = 12_000, contextWindow = null)),
        )
    }

    @Test
    fun contextUsageFormattingUsesTheRequestedLocale() {
        assertEquals(
            "82K / 100K tokens · 82,0%",
            formatContextUsage(
                usage = AgentContextUsageUi(contextTokens = 82_000, contextWindow = 100_000),
                locale = java.util.Locale.GERMANY,
            ),
        )
    }

    private fun provider(
        id: String,
        name: String = id,
        sourceType: String = ProviderSourceTypes.CUSTOM,
        enabled: Boolean = true,
        apiKey: String = "test-key",
        models: List<Model>,
    ) = CustomProviderSetting(
        id = id,
        name = name,
        baseUrl = "https://example.com/$id",
        sourceType = sourceType,
        apiKey = apiKey,
        isEnabled = enabled,
        models = models,
    )

    private fun model(
        id: String,
        modelId: String = id,
        displayName: String = modelId,
        enabled: Boolean = true,
        contextWindow: Int? = null,
    ) = Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        isEnabled = enabled,
        contextWindow = contextWindow,
    )
}
