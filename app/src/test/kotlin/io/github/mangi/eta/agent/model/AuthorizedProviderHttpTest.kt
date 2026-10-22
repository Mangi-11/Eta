package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.auth.CodexOAuthFixture
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedProviderHttpTest {
    @Test
    fun refreshesRejectedCredentialBeforeConsumingResponseAndNeverReusesSnapshot() {
        val authorizations = mutableListOf<String?>()
        val accounts = mutableListOf<String?>()
        val oldToken = CodexOAuthFixture.accessToken("old-workspace")
        val newToken = CodexOAuthFixture.accessToken("new-workspace")
        val rejected = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            authorizations += chain.request().header("Authorization")
            accounts += chain.request().header("ChatGPT-Account-Id")
            response(chain.request(), if (authorizations.size == 1) 401 else 200)
        }.build()
        val value = AuthorizedProviderHttp.execute(
            client, "account", ProviderAuthModes.OPENAI_CHATGPT, request(), AgentRunController(),
            accessToken = { _, _, _, previous ->
                rejected += previous
                if (previous == null) oldToken else newToken
            },
        ) { it.code }
        assertEquals(listOf("old-workspace", "new-workspace"), accounts)
        assertEquals(200, value)
        assertEquals(listOf(null, oldToken), rejected)
        assertEquals(listOf("Bearer $oldToken", "Bearer $newToken"), authorizations)
    }

    @Test
    fun aSecondUnauthorizedResponseEndsRecovery() {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            response(chain.request(), 401)
        }.build()
        val failure = runCatching {
            AuthorizedProviderHttp.execute(
                client, "account", ProviderAuthModes.OPENAI_CHATGPT, request(), AgentRunController(),
                accessToken = { _, _, _, _ -> CodexOAuthFixture.accessToken() },
            ) { error("未授权响应不应进入模型解析") }
        }.exceptionOrNull()
        assertEquals("OAUTH_UNAUTHORIZED", (failure as AgentModelFailure).code)
        assertEquals(2, attempts.get())
    }

    @Test
    fun malformedOriginAndPathAreRejectedBeforeReadingCredential() {
        val forbidden = listOf(
            "https://chatgpt.com.evil.test/backend-api/codex/responses",
            "http://chatgpt.com/backend-api/codex/responses",
            "https://chatgpt.com:8443/backend-api/codex/responses",
            "https://chatgpt.com/v1/chat/completions",
            "https://chatgpt.com/backend-api/codex/responses?target=elsewhere",
            "https://user@chatgpt.com/backend-api/codex/responses",
            "https://cli-chat-proxy.grok.com/v1/chat/completions",
        )
        forbidden.forEach { url ->
            val failure = runCatching {
                AuthorizedProviderHttp.execute(
                    OkHttpClient(), "account", ProviderAuthModes.OPENAI_CHATGPT,
                    Request.Builder().url(url).build(), AgentRunController(),
                    accessToken = { _, _, _, _ -> error("不应读取凭据") },
                ) { error("不应连接网络") }
            }.exceptionOrNull()
            assertTrue(url, failure is IllegalArgumentException)
        }
    }

    @Test
    fun streamFailuresNeverTriggerCredentialRefreshOrReplay() {
        val attempts = AtomicInteger()
        val reads = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            response(chain.request(), 200)
        }.build()
        val failure = runCatching {
            AuthorizedProviderHttp.execute(
                client, "account", ProviderAuthModes.OPENAI_CHATGPT, request(), AgentRunController(),
                accessToken = { _, _, _, _ -> reads.incrementAndGet(); CodexOAuthFixture.accessToken() },
            ) { throw IOException("fixture-stream-failed") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, attempts.get())
        assertEquals(1, reads.get())
    }

    @Test
    fun apiKeyUnauthorizedDoesNotEnterOAuthRecovery() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer api-fixture", chain.request().header("Authorization"))
            response(chain.request(), 401)
        }.build()
        val status = AuthorizedProviderHttp.execute(
            client, "api", ProviderAuthModes.API_KEY,
            Request.Builder().url("https://gateway.example/v1/models")
                .header("Authorization", "Bearer api-fixture").build(),
            AgentRunController(),
            accessToken = { _, _, _, _ -> error("API Key 不应读取 OAuth 凭据") },
        ) { it.code }
        assertEquals(401, status)
    }

    @Test
    fun subscriptionRedirectIsReturnedWithoutSendingBearerToRedirectTarget() {
        val redirected = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            assertEquals("Bearer ${CodexOAuthFixture.accessToken()}", exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Location", "/redirected")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redirected") { exchange ->
            redirected.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                // 官方目标仍由生产入口校验，测试连接只改向本机 fixture。
                chain.proceed(chain.request().newBuilder()
                    .url("http://127.0.0.1:${server.address.port}/start").build())
            }.build()
            val status = AuthorizedProviderHttp.execute(
                client, "account", ProviderAuthModes.OPENAI_CHATGPT, request(), AgentRunController(),
                accessToken = { _, _, _, _ -> CodexOAuthFixture.accessToken() },
            ) { it.code }
            assertEquals(302, status)
            assertEquals(0, redirected.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancellationClosesInFlightRequestBeforeHeaders() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = AgentRunController()
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            assertTrue(chain.call().isCanceled())
            throw IOException("cancelled-fixture")
        }.build()
        try {
            val future = executor.submit<Throwable?> {
                runCatching {
                    AuthorizedProviderHttp.execute(
                        client, "account", ProviderAuthModes.OPENAI_CHATGPT, request(), controller,
                        accessToken = { _, _, _, _ -> CodexOAuthFixture.accessToken() },
                    ) { it.code }
                }.exceptionOrNull()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.cancel()
            release.countDown()
            assertTrue(future.get(5, TimeUnit.SECONDS) is AgentRunCancelledException)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun request() = Request.Builder()
        .url(ProviderConnectionProfile.OPENAI_CODEX.inferenceUrl).build()

    private fun response(request: Request, code: Int) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body("{}".toResponseBody()).build()
}
