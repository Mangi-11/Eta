package io.github.mangi.eta.agent.model.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderUrlsTest {
    @Test
    fun appendsProviderSpecificPathsToRootBaseUrl() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderUrls.openAiChatCompletionsUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            ProviderUrls.openAiModelsUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            ProviderUrls.openAiResponsesUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            ProviderUrls.anthropicMessagesUrl("https://api.anthropic.com/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ProviderUrls.anthropicModelsUrl("https://api.anthropic.com")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/interactions",
            ProviderUrls.geminiInteractionsUrl(" https://generativelanguage.googleapis.com/v1beta/ "),
        )
        assertEquals(
            "https://proxy.invalid/google/v1beta/models",
            ProviderUrls.geminiModelsUrl("https://proxy.invalid/google/v1beta/"),
        )
    }
}
