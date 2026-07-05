package fuck.andes.agent.model

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
            "https://api.anthropic.com/v1/messages",
            ProviderUrls.anthropicMessagesUrl("https://api.anthropic.com/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ProviderUrls.anthropicModelsUrl("https://api.anthropic.com")
        )
    }
}
