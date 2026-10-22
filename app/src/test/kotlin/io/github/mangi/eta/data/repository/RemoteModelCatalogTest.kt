package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelCatalogTest {
    @Test
    fun catalogMatchesConnectionChangesButIgnoresPresentationEdits() {
        val source = provider()
        val catalog = RemoteModelCatalog(emptyList(), source, null)
        assertTrue(catalog.matches(source.copy(name = "新名称", sortOrder = 7, systemPrompt = "新提示词")))
        assertTrue(catalog.matches(source.copy(baseUrl = source.baseUrl + "/")))
        listOf(
            source.copy(id = "other"),
            source.copy(authMode = ProviderAuthModes.OPENAI_CHATGPT),
            source.copy(apiKey = "changed-fixture"),
            source.copy(baseUrl = "https://other.example/v1"),
            source.copy(customHeaders = listOf(CustomHeader("X-Custom", "changed-fixture"))),
            source.copy(endpointMode = OpenAiEndpointMode.RESPONSES),
            source.copy(isEnabled = false),
        ).forEach { assertFalse(catalog.matches(it)) }
    }

    @Test
    fun diagnosticStringCannotRevealConnectionSecrets() {
        val catalog = RemoteModelCatalog(emptyList(), provider(), null)
        assertFalse(catalog.toString().contains("private-key-fixture"))
        assertFalse(catalog.toString().contains("private-header-fixture"))
        assertFalse(catalog.toString().contains("example"))
    }

    private fun provider() = OpenAiCompatibleProviderSetting(
        id = "fixture", name = "Fixture", baseUrl = "https://example.test/v1",
        apiKey = "private-key-fixture", customHeaders = listOf(CustomHeader("X-Custom", "private-header-fixture")),
    )
}
