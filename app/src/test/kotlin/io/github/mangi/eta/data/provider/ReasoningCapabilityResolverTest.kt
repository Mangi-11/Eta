package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningCapabilityResolverTest {
    @Test
    fun userFacingEffortLabelsAreStableEnglishValues() {
        assertEquals(
            listOf("Off", "Default", "Minimal", "Low", "Medium", "High", "XHigh", "Max"),
            ReasoningEffort.entries.map(ReasoningEffort::displayName),
        )
    }

    @Test
    fun deepSeekCatalogExposesOnlyMeaningfulLevels() {
        val flash = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-flash")
        val currentFlash = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-flash")
        val pro = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-pro")

        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            flash.selectableEfforts,
        )
        assertEquals(flash.selectableEfforts, currentFlash.selectableEfforts)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            pro.selectableEfforts,
        )
        assertEquals(ReasoningEffort.HIGH, pro.normalize(ReasoningEffort.XHIGH))
    }

    @Test
    fun gpt6CatalogUsesOnlySupportedEffortLevels() {
        val astra = resolve(ProviderSourceTypes.OPENAI, "gpt-6-astra")
        val sol = resolve(ProviderSourceTypes.OPENAI, "gpt-6-sol")
        val luna = resolve(ProviderSourceTypes.OPENAI, "gpt-6-luna")
        val named = listOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX,
        )

        assertEquals(listOf(ReasoningEffort.DEFAULT) + named, astra.selectableEfforts)
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.DEFAULT) + named, sol.selectableEfforts)
        assertEquals(sol.selectableEfforts, luna.selectableEfforts)
        assertEquals(ReasoningEffort.MEDIUM, sol.defaultEffort)
        assertEquals(ReasoningEffort.MEDIUM, luna.defaultEffort)
    }

    @Test
    fun gpt55And56ExposeDocumentedEffortLevels() {
        val gpt55 = resolve(ProviderSourceTypes.OPENAI, "gpt-5.5")
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            gpt55.selectableEfforts,
        )
        for (model in listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            assertEquals(
                gpt55.selectableEfforts + ReasoningEffort.MAX,
                resolve(ProviderSourceTypes.OPENAI, model).selectableEfforts,
            )
        }
    }

    @Test
    fun latestClaudeModelsRequireThinking() {
        for (model in listOf("claude-fable-5", "claude-fable-5-1", "claude-opus-5-5")) {
            val capabilities = resolve(ProviderSourceTypes.ANTHROPIC, model)
            assertEquals(
                listOf(
                    ReasoningEffort.DEFAULT,
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MAX,
                ),
                capabilities.selectableEfforts,
            )
        }
        assertEquals(
            ReasoningEffort.MEDIUM,
            resolve(ProviderSourceTypes.ANTHROPIC, "claude-opus-5-5").defaultEffort,
        )
        assertEquals(
            ReasoningEffort.OFF,
            resolve(ProviderSourceTypes.ANTHROPIC, "claude-sonnet-5").selectableEfforts.first(),
        )
    }

    @Test
    fun bailianCatalogExposesCanonicalNewModelEfforts() {
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.XHIGH,
            ),
            resolve(ProviderSourceTypes.BAILIAN, "qwen3.8-max").selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            resolve(ProviderSourceTypes.BAILIAN, "deepseek-v4.1-flash").selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            resolve(ProviderSourceTypes.BAILIAN, "kimi-k3").selectableEfforts,
        )
    }

    @Test
    fun mimoV26OffersToggleAndCurrentStepModelsOfferThreeLevels() {
        assertEquals(
            listOf(ReasoningEffort.OFF, ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.MIMO, "mimo-v2.6-pro").selectableEfforts,
        )
        val expected = listOf(
            ReasoningEffort.DEFAULT,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
        )
        assertEquals(expected, resolve(ProviderSourceTypes.STEPFUN, "step-5-preview").selectableEfforts)
        assertEquals(expected, resolve(ProviderSourceTypes.STEPFUN, "step-3.7-flash").selectableEfforts)
    }

    @Test
    fun mandatoryKimiModelsNeverExposeOff() {
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            resolve(ProviderSourceTypes.MOONSHOT, "kimi-k3").selectableEfforts,
        )
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.MOONSHOT, "kimi-k2.7-code").selectableEfforts,
        )
    }

    @Test
    fun unverifiedModelsDegradeToSafeDefault() {
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.MINIMAX, "MiniMax-M3").selectableEfforts,
        )
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.CUSTOM, "unknown-thinking-model").selectableEfforts,
        )
    }

    @Test
    fun exactRemoteMetadataWinsOverProviderFamilyRules() {
        val remote = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MEDIUM),
            defaultEffort = ReasoningEffort.MEDIUM,
            mandatory = true,
        )
        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.DEEPSEEK,
            model = Model(
                id = "id",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek",
                reasoning = true,
                reasoningCapabilities = remote,
            ),
        )

        assertEquals(remote, resolved)
        assertEquals(
            listOf(ReasoningEffort.DEFAULT, ReasoningEffort.MEDIUM),
            resolved?.selectableEfforts,
        )
    }

    @Test
    fun userOverrideWinsOverRemoteMetadata() {
        val overridden = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MINIMAL),
            canDisable = true,
        )

        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.DEEPSEEK,
            model = Model(
                id = "id",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek",
                reasoning = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                ),
                reasoningOverride = true,
                reasoningCapabilitiesOverride = overridden,
            ),
        )

        assertEquals(overridden, resolved)
    }

    @Test
    fun responsesRelayInfersOnlyExactOfficialModelUnlessExplicitlyDisabled() {
        val inferred = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(id = "luna", modelId = "gpt-5.6-luna", displayName = "Luna"),
            inferExactCatalogModel = true,
        )
        val unknown = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(id = "unknown", modelId = "relay-thinking", displayName = "Unknown"),
            inferExactCatalogModel = true,
        )
        val disabled = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.OPENAI,
            model = Model(
                id = "disabled",
                modelId = "gpt-5.6-luna",
                displayName = "Disabled",
                reasoning = false,
            ),
            inferExactCatalogModel = true,
        )

        assertEquals(ReasoningEffort.MEDIUM, inferred?.defaultEffort)
        assertNull(unknown)
        assertNull(disabled)
    }

    @Test
    fun gpt6ResponsesCapabilitiesAreModelSpecificForOpenAiAndCodex() {
        val astra = resolve(ProviderSourceTypes.OPENAI, "gpt-6-astra")
        val sol = resolve(ProviderSourceTypes.OPENAI, "gpt-6-sol")
        val luna = resolve(ProviderSourceTypes.OPENAI_CODEX, "gpt-6-luna")

        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            astra.selectableEfforts,
        )
        assertEquals(false, astra.canDisable)
        assertEquals(true, sol.canDisable)
        assertEquals(true, luna.canDisable)
        assertEquals(ReasoningEffort.MAX, luna.normalize(ReasoningEffort.MAX))
    }

    private fun resolve(source: String, modelId: String): ModelReasoningCapabilities =
        requireNotNull(
            ReasoningCapabilityResolver.resolve(
                sourceType = source,
                model = Model(
                    id = "id-$modelId",
                    modelId = modelId,
                    displayName = modelId,
                    reasoning = true,
                ),
            )
        )
}
