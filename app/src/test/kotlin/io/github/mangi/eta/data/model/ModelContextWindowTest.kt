package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContextWindowTest {
    private val model = Model(
        id = "test-model",
        modelId = "test-model",
        displayName = "Test model",
    )

    @Test
    fun usesPositiveRemoteWindowWhenNoOverrideIsConfigured() {
        val remote = model.copy(source = ModelSource.REMOTE, contextWindow = 1_050_000)

        assertEquals(1_050_000, remote.effectiveContextWindow)
        assertNull(remote.contextWindowOverride)
    }

    @Test
    fun manualOverrideTakesPrecedenceOverMetadata() {
        assertEquals(
            64_000,
            model.copy(contextWindow = 1_050_000, contextWindowOverride = 64_000)
                .effectiveContextWindow,
        )
    }

    @Test
    fun manualOverrideWorksWithoutMetadata() {
        assertEquals(64_000, model.copy(contextWindowOverride = 64_000).effectiveContextWindow)
    }

    @Test
    fun clearingOverrideRestoresMetadataWindow() {
        val overridden = model.copy(contextWindow = 1_050_000, contextWindowOverride = 64_000)

        assertEquals(1_050_000, overridden.copy(contextWindowOverride = null).effectiveContextWindow)
    }

    @Test
    fun ignoresNonPositiveMetadataWithoutGuessingAWindow() {
        for (window in listOf(null, 0, -1)) {
            assertNull(model.copy(contextWindow = window).effectiveContextWindow)
            assertEquals(
                64_000,
                model.copy(contextWindow = window, contextWindowOverride = 64_000)
                    .effectiveContextWindow,
            )
        }
    }

    @Test
    fun ignoresNonPositiveOverrides() {
        for (override in listOf(0, -1)) {
            assertEquals(
                1_050_000,
                model.copy(contextWindow = 1_050_000, contextWindowOverride = override)
                    .effectiveContextWindow,
            )
            assertNull(model.copy(contextWindowOverride = override).effectiveContextWindow)
        }
    }
}
