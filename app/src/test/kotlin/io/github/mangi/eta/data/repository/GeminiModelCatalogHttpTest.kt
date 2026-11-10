package io.github.mangi.eta.data.repository

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelCatalogHttpTest {
    @Test
    fun remoteThinkingFlagTakesPrecedenceOverLocalKnowledge() {
        val models = GeminiModelCatalog.parsePage("""{"models":[
            {"name":"models/gemini-4-carbon","thinking":false},
            {"name":"models/gemini-future","thinking":true}]}""").models
        assertEquals(false, models.first().reasoning)
        assertNull(models.first().reasoningCapabilities)
        assertEquals(true, models.last().reasoning)
        assertNull(models.last().reasoningCapabilities)
    }

    @Test
    fun carbonCatalogEntryIncludesDocumentedThinkingCapabilities() {
        val model = GeminiModelCatalog.parsePage("""{"models":[{"name":"models/gemini-4-carbon",
            "displayName":"Gemini 4 Carbon","supportedGenerationMethods":["generateContent"]}]}""")
            .models.single()
        assertTrue(model.supportsReasoning)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            requireNotNull(model.reasoningCapabilities).supportedEfforts)
        assertEquals(ReasoningEffort.MEDIUM, model.reasoningCapabilities.defaultEffort)
    }

    @Test
    fun paginatesWithNativeAuthenticationNormalizesNamesAndDoesNotInferUnknownCapabilities() = runBlocking {
        withServer(listOf(
            """{"models":[{"name":"models/gemini-3.8-flash","displayName":"Flash","inputTokenLimit":1048576,
                 "supportedGenerationMethods":["generateContent"]}],"nextPageToken":"page-2"}""",
            """{"models":[{"name":"models/gemini-future","displayName":"未来模型","supportedGenerationMethods":["generateContent"]},
                 {"name":"models/gemini-3.8-flash","displayName":"重复"},
                 {"name":"models/gemini-embedding","supportedGenerationMethods":["embedContent"]}]}""",
        )) { url, requests ->
            val provider = provider(url).copy(customHeaders = listOf(CustomHeader("x-goog-api-key", "replacement-must-not-apply")))
            val result = RemoteModelFetcher.fetchCatalog(provider, OkHttpClient(), noOAuth).getOrThrow()
            assertEquals(listOf("gemini-3.8-flash", "gemini-future"), result.models.map { it.modelId })
            assertTrue(requests.all { it.key == "fixture-key" && it.authorization == null && !it.query.contains("key=") })
            assertEquals("pageSize=1000", requests[0].query)
            assertEquals("pageSize=1000&pageToken=page-2", requests[1].query)
            val known = result.models.first()
            assertEquals(1_048_576, known.effectiveContextWindow)
            assertEquals(ReasoningEffort.MEDIUM, known.reasoningCapabilities!!.defaultEffort)
            assertTrue(known.supportsVision && known.supportsTools)
            val unknown = result.models.last()
            assertNull(unknown.toolCall)
            assertNull(unknown.reasoningCapabilities)
            assertFalse(GeminiModelCapabilities.supportsHostedSearch(unknown.modelId))
            assertNull(result.accountBinding)
        }
    }

    @Test
    fun repeatedPaginationTokenFailsWithoutReturningAPartialCatalog() = runBlocking {
        val body = """{"models":[{"name":"models/gemini-3.8-flash"}],"nextPageToken":"same"}"""
        withServer(listOf(body, body)) { url, requests ->
            val result = RemoteModelFetcher.fetchCatalog(provider(url), OkHttpClient(), noOAuth)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("重复分页令牌"))
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun catalogBudgetAppliesAcrossPages() = runBlocking {
        val padding = "x".repeat(2_200_000)
        withServer(listOf(
            """{"models":[],"padding":"$padding","nextPageToken":"next"}""",
            """{"models":[],"padding":"$padding"}""",
        )) { url, requests ->
            val result = RemoteModelFetcher.fetchCatalog(provider(url), OkHttpClient(), noOAuth)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("大小限制"))
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun malformedCatalogPagesFailInsteadOfSilentlyDroppingModels() {
        for (body in listOf("{}", "{", """{"models":[{}]}""", """{"models":[],"nextPageToken":17}""",
            """{"error":{"message":"fixture"},"models":[]}""")) {
            assertTrue(runCatching { GeminiModelCatalog.parsePage(body) }.isFailure)
        }
    }

    @Test
    fun cancellingDuringResponseBodyReadStopsPagination() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/models") { exchange ->
            count.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val operation = launch {
                RemoteModelFetcher.fetchCatalog(provider("http://127.0.0.1:${server.address.port}"), OkHttpClient(), noOAuth)
                error("取消后不应成功返回目录")
            }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(3_000) { operation.cancelAndJoin() }
            assertTrue(operation.isCancelled)
            assertEquals(1, count.get())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private data class SeenRequest(val query: String, val key: String?, val authorization: String?)

    private suspend fun withServer(bodies: List<String>, block: suspend (String, List<SeenRequest>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<SeenRequest>())
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/models") { exchange ->
            requests += SeenRequest(exchange.requestURI.rawQuery.orEmpty(), exchange.requestHeaders.getFirst("x-goog-api-key"),
                exchange.requestHeaders.getFirst("Authorization"))
            val bytes = bodies[calls.getAndIncrement().coerceAtMost(bodies.lastIndex)].toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }

    private fun provider(baseUrl: String) = GeminiProviderSetting(
        id = "fixture-gemini", name = "Gemini", baseUrl = baseUrl, apiKey = "fixture-key",
    )

    private val noOAuth = object : RemoteModelFetcher.Credentials {
        override fun binding(providerId: String, authMode: String): ProviderCredentialBinding = error("不应读取 OAuth 账号")
        override fun isCurrent(binding: ProviderCredentialBinding): Boolean = error("不应读取 OAuth 账号")
        override fun accessToken(providerId: String, authMode: String, controller: AgentRunController, rejected: String?): String =
            error("不应读取 OAuth 令牌")
    }
}
