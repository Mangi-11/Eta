package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelCapabilitiesTest {
    @Test
    fun remoteAndManualCapabilitiesOverrideLocalDefaultsWithoutRequiringKnownModel() {
        val remote = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW))
        val manual = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.HIGH))
        val known = Model(id = "fixture", modelId = "gemini-4-carbon", displayName = "Carbon",
            reasoning = true, reasoningCapabilities = remote)
        assertEquals(remote, GeminiModelCapabilities.resolveReasoning(known))
        assertEquals(emptyList<ReasoningEffort>(), GeminiModelCapabilities.resolveReasoning(
            known.copy(reasoning = false)).supportedEfforts)
        for (id in listOf("gemini-4-carbon", "gemini-future")) {
            assertEquals(manual, GeminiModelCapabilities.resolveReasoning(known.copy(modelId = id,
                reasoning = false, reasoningOverride = true, reasoningCapabilitiesOverride = manual)))
            assertEquals(listOf(ReasoningEffort.DEFAULT), GeminiModelCapabilities.resolveReasoning(
                known.copy(modelId = id, reasoningOverride = false)).selectableEfforts)
        }
        assertEquals(listOf(ReasoningEffort.DEFAULT), GeminiModelCapabilities.resolveReasoning(
            known.copy(modelId = "gemini-future", reasoningCapabilities = null)).selectableEfforts)
    }

    @Test
    fun carbonIsTheOnlyAdditionToPreviousCatalogRevision() {
        val provider = requireNotNull(BuiltinProviders.providerById(BuiltinProviders.GEMINI_ID))
        assertEquals(listOf("gemini-4-carbon"), OfficialModelCatalog.modelsAddedSince(provider, 4).map { it.modelId })
    }

    @Test
    fun nativeProxyIdentityDoesNotChangeExistingCompatibleConnections() {
        val url = "https://proxy.example/v1beta"
        assertEquals(ProviderSourceTypes.GEMINI,
            ProviderSourceRegistry.resolve(GeminiProviderSetting(id = "proxy", name = "代理", baseUrl = url,
                sourceType = ProviderSourceTypes.CUSTOM)))
        assertEquals(ProviderSourceTypes.CUSTOM,
            ProviderSourceRegistry.resolve(CustomProviderSetting(id = "old", name = "旧兼容连接",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai")))
    }

    @Test
    fun currentPresetsExposeOnlyDocumentedThinkingLevelsAndCannotDisableThinking() {
        val provider = requireNotNull(BuiltinProviders.providerById(BuiltinProviders.GEMINI_ID))
        assertTrue(provider is GeminiProviderSetting)
        assertFalse(provider.hostedWebSearchEnabled)
        val presets = OfficialModelCatalog.modelsForProvider(provider)
        assertEquals(listOf("gemini-4-carbon", "gemini-3.8-flash", "gemini-4-argon", "gemini-3.5-flash-lite"), presets.map { it.modelId })
        for (model in presets) {
            assertEquals(1_048_576, model.effectiveContextWindow)
            assertTrue(model.supportsVision && model.supportsTools)
            assertTrue(GeminiModelCapabilities.supportsHostedSearch(model.modelId))
            val capabilities = requireNotNull(model.reasoningCapabilities)
            assertTrue(capabilities.mandatory)
            assertFalse(ReasoningEffort.OFF in capabilities.selectableEfforts)
            assertEquals(ReasoningEffort.DEFAULT, capabilities.normalize(ReasoningEffort.OFF))
        }
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            presets.first().reasoningCapabilities!!.supportedEfforts)
        assertEquals(ReasoningEffort.MINIMAL, presets.last().reasoningCapabilities!!.defaultEffort)
        assertEquals(ReasoningEffort.MINIMAL, presets.last().reasoningCapabilities!!.supportedEfforts.first())
    }

    @Test
    fun searchCapabilitiesRequireConfirmedModelIdentity() {
        for (id in listOf(
            "gemini-4-carbon", "gemini-4-argon", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
            "gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-3-pro-preview",
        )) {
            assertTrue(GeminiModelCapabilities.supportsHostedSearch(" models/$id "))
        }
        for (id in listOf("gemini-4-unknown", "gemini-3.8-flash-tts", "gemini-3.8-live", "gemini-2.5-pro")) {
            assertFalse(GeminiModelCapabilities.supportsHostedSearch(id))
            assertNull(GeminiModelCapabilities.reasoningCapabilities(id))
        }
    }
}
