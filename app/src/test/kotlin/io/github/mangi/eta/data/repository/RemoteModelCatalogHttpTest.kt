package io.github.mangi.eta.data.repository

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.auth.CodexOAuthFixture
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelCatalogHttpTest {
    @Test
    fun codexCatalogRefreshesAuthorizationAndUsesOnlyTheLatestRemoteModels() = runBlocking {
        val provider = provider(ProviderAuthModes.OPENAI_CHATGPT)
        val credentials = FixtureCredentials(provider.id, provider.authMode)
        val targets = mutableListOf<String>()
        withServer(listOf(
            401 to """{"error":{"code":"invalid_token"}}""",
            200 to """{"models":[
              {"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list","context_window":260000,
               "supported_in_api":false,"input_modalities":["text","image"],
               "supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}],"default_reasoning_level":"high"},
              {"slug":"future-model","display_name":"远端新模型","visibility":"list"},
              {"slug":"gpt-5.5","display_name":"旧模型","visibility":"hide"}
            ]}""",
        )) { url, seen ->
            val result = RemoteModelFetcher.fetchCatalog(provider, redirectedClient(url, targets), credentials).getOrThrow()
            assertEquals(listOf("gpt-6.1-sol", "future-model"), result.models.map { it.modelId })
            assertEquals(260000, result.models.first().contextWindow)
            assertEquals(ReasoningEffort.HIGH, result.models.first().reasoningCapabilities!!.defaultEffort)
            assertEquals(credentials.binding, result.accountBinding)
            assertEquals(2, credentials.rejectedTokens.size)
            assertEquals(listOf("workspace-old", "workspace-new"), seen.map { it.accountId })
            assertTrue(seen.all { it.originator == "eta" && it.authorization?.startsWith("Bearer ") == true })
            assertEquals(listOf(ProviderConnectionProfile.OPENAI_CODEX.modelsUrl, ProviderConnectionProfile.OPENAI_CODEX.modelsUrl), targets)
            val target = targets.first().toHttpUrl()
            assertEquals("https", target.scheme)
            assertEquals("chatgpt.com", target.host)
            assertEquals("/backend-api/codex/models", target.encodedPath)
            assertTrue(target.queryParameter("client_version")!!.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        }
    }

    @Test
    fun codexCatalogFailureNeverReturnsLocalPresetsAsRemoteResults() = runBlocking {
        val provider = provider(ProviderAuthModes.OPENAI_CHATGPT)
        val credentials = FixtureCredentials(provider.id, provider.authMode)
        for ((status, body) in listOf(503 to """{"error":{"message":"unavailable"}}""", 200 to "{}")) {
            withServer(listOf(status to body)) { url, seen ->
                val result = RemoteModelFetcher.fetchCatalog(provider, redirectedClient(url, mutableListOf()), credentials)
                assertTrue(result.isFailure)
                assertEquals(1, seen.size)
            }
        }
    }

    @Test
    fun grokCatalogUsesNativeProxyHeadersAndPreservesNativeModelMetadata() = runBlocking {
        val provider = provider(ProviderAuthModes.XAI_GROK)
        val credentials = FixtureCredentials(provider.id, provider.authMode)
        val targets = mutableListOf<String>()
        withServer(listOf(200 to """{"object":"list","data":[
          {"id":"display-key","model":"grok-chat","name":"Grok Chat","context_window":256000,
           "capabilities":{"reasoning_effort":["low","high"],"default_reasoning_effort":"high"}},
          {"modelId":"grok-explicit-chat","apiBackend":"chat_completions"},
          {"id":"hidden","hidden":true},
          {"id":"grok-imagine-image","output_modalities":["image"]}
        ]}""")) { url, seen ->
            val result = RemoteModelFetcher.fetchCatalog(provider, redirectedClient(url, targets), credentials).getOrThrow()
            assertEquals(listOf("grok-chat", "grok-explicit-chat"), result.models.map { it.modelId })
            assertEquals(256000, result.models.first().contextWindow)
            assertEquals(ReasoningEffort.HIGH, result.models.first().reasoningCapabilities!!.defaultEffort)
            assertEquals("Bearer fixture-old", seen.single().authorization)
            assertEquals("xai-grok-cli", seen.single().tokenAuth)
            assertEquals("application/json", seen.single().accept)
            assertEquals(listOf(ProviderConnectionProfile.XAI_GROK.modelsUrl), targets)
        }
    }

    @Test
    fun accountChangeDuringHttpResponseRejectsTheCatalog() = runBlocking {
        val provider = provider(ProviderAuthModes.XAI_GROK)
        val credentials = FixtureCredentials(provider.id, provider.authMode)
        withServer(listOf(200 to """{"data":[{"id":"grok-model"}]}""")) { url, _ ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(url).build()).also {
                    credentials.current = false
                }
            }.build()
            val failure = RemoteModelFetcher.fetchCatalog(provider, client, credentials).exceptionOrNull()
            assertEquals("oauth_session_changed", (failure as ProviderAuthException).code)
        }
    }

    @Test
    fun apiKeyCatalogDoesNotReadOAuthAccountOrReplaceItsCredential() = runBlocking {
        val noOAuth = object : RemoteModelFetcher.Credentials {
            override fun binding(providerId: String, authMode: String): ProviderCredentialBinding = error("不应读取 OAuth 账号")
            override fun isCurrent(binding: ProviderCredentialBinding): Boolean = error("不应读取 OAuth 账号")
            override fun accessToken(providerId: String, authMode: String, controller: AgentRunController, rejected: String?): String =
                error("不应读取 OAuth 令牌")
        }
        withServer(listOf(200 to """{"data":[{"id":"api-chat","name":"API 模型"}]}""")) { url, seen ->
            val provider = provider(ProviderAuthModes.API_KEY).copy(baseUrl = url.removeSuffix("/models"), apiKey = "api-fixture")
            val result = RemoteModelFetcher.fetchCatalog(provider, OkHttpClient(), noOAuth).getOrThrow()
            assertEquals("api-chat", result.models.single().modelId)
            assertEquals("Bearer api-fixture", seen.single().authorization)
            assertEquals(null, result.accountBinding)
            assertFalse(result.toString().contains("api-fixture"))
        }
    }

    private class FixtureCredentials(providerId: String, authMode: String) : RemoteModelFetcher.Credentials {
        val binding = ProviderCredentialBinding(providerId, authMode, 7)
        @Volatile var current = true
        val rejectedTokens = mutableListOf<String?>()
        override fun binding(providerId: String, authMode: String) = binding
        override fun isCurrent(binding: ProviderCredentialBinding) = current && this.binding == binding
        override fun accessToken(providerId: String, authMode: String, controller: AgentRunController, rejected: String?): String {
            rejectedTokens += rejected
            return if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                CodexOAuthFixture.accessToken(if (rejected == null) "workspace-old" else "workspace-new")
            } else if (rejected == null) "fixture-old" else "fixture-new"
        }
    }

    private data class SeenRequest(
        val authorization: String?, val tokenAuth: String?, val accept: String?, val accountId: String?, val originator: String?,
    )

    private suspend fun withServer(
        responses: List<Pair<Int, String>>,
        block: suspend (String, List<SeenRequest>) -> Unit,
    ) {
        val seen = Collections.synchronizedList(mutableListOf<SeenRequest>())
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/models") { exchange ->
            seen += SeenRequest(
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("X-XAI-Token-Auth"),
                exchange.requestHeaders.getFirst("Accept"),
                exchange.requestHeaders.getFirst("ChatGPT-Account-Id"),
                exchange.requestHeaders.getFirst("originator"),
            )
            val (status, body) = responses[calls.getAndIncrement().coerceAtMost(responses.lastIndex)]
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/models", seen)
        } finally {
            server.stop(0)
        }
    }

    private fun redirectedClient(url: String, targets: MutableList<String>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            targets += chain.request().url.toString()
            // 生产入口仍校验官方地址，测试传输只连接本地 fixture。
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build()

    private fun provider(mode: String) = OpenAiCompatibleProviderSetting(
        id = "fixture-account", name = "Fixture", baseUrl = "https://unused.example/v1", authMode = mode,
    )
}
