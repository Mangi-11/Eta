package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestHeadersTest {
    @Test
    fun openCodeSessionIsStableAndIsolatedFromOtherConversations() {
        val first = headers("https://opencode.ai/zen/go/v1", "conversation-1")
        val retry = headers("https://opencode.ai/zen/go/v1", "conversation-1")
        val next = headers("https://opencode.ai/zen/go/v1", "conversation-2")
        assertEquals(first["x-opencode-session"], retry["x-opencode-session"])
        assertNotEquals(first["x-opencode-session"], next["x-opencode-session"])
        assertEquals("Eta", first["User-Agent"])
    }

    @Test
    fun sessionHeaderDoesNotLeakToUnrelatedHosts() {
        listOf("https://example.com/v1", "https://opencode.ai.example.com/v1").forEach {
            assertNull(headers(it, "conversation-1")["x-opencode-session"])
        }
    }

    @Test
    fun customUserAgentOverridesDefaultButSessionAndAuthStayManaged() {
        val builder = Headers.Builder().add("Authorization", "Bearer test-key")
        ProviderRequestHeaders.mergeInto(
            builder, "https://opencode.ai/zen/go/v1",
            listOf(
                CustomHeader("user-agent", "test-client"),
                CustomHeader("Authorization", "bad"),
                CustomHeader("X-OpenCode-Session", "fixed"),
            ),
            "conversation-1",
        )
        val result = builder.build()
        assertEquals("test-client", result["User-Agent"])
        assertEquals(1, result.values("User-Agent").size)
        assertEquals("Bearer test-key", result["Authorization"])
        assertNotEquals("fixed", result["x-opencode-session"])
    }

    @Test
    fun editorRejectsInvalidAndDuplicateHeadersWithoutEchoingValues() {
        assertNull(CustomHeaderFilter.validationError(listOf(CustomHeader("User-Agent", "test-client"))))
        listOf(
            listOf(CustomHeader("X-Test", "secret\r\nInjected: value")),
            listOf(CustomHeader("bad:name", "secret")),
            listOf(CustomHeader("Authorization", "secret")),
            listOf(CustomHeader("X-Test", "1"), CustomHeader(" x-test ", "2")),
        ).forEach { headers ->
            val error = CustomHeaderFilter.validationError(headers)
            assertTrue(error != null && !error.contains("secret"))
        }
    }

    private fun headers(url: String, session: String): Headers = Headers.Builder().also {
        ProviderRequestHeaders.mergeInto(it, url, emptyList(), session)
    }.build()
}
