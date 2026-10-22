package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderOAuthClientTest {
    @Test fun devicePollingHonorsServerIntervalAndSlowDown() {
        var nanos = 0L
        var tokenRequests = 0
        val delays = mutableListOf<Long>()
        val http = http { request ->
            if (request.url.encodedPath == "/oauth2/device/code") deviceResponse() else {
                val body = request.body as FormBody
                assertEquals("urn:ietf:params:oauth:grant-type:device_code", body.value((0 until body.size).first { body.name(it) == "grant_type" }))
                400 to when (++tokenRequests) {
                    1 -> """{"error":"authorization_pending"}"""
                    2 -> """{"error":"slow_down"}"""
                    else -> """{"error":"access_denied"}"""
                }
            }
        }
        val client = LiveProviderOAuthClient(http, monotonicNow = { nanos }, waitForPoll = { _, delay ->
            delays += delay
            nanos += delay * 1_000_000
        })
        val error = assertThrows(ProviderAuthException::class.java) {
            client.login(OAuthProvider.XAI, AgentRunController()) { prompt ->
                assertEquals("TEST-CODE", prompt.userCode)
                assertEquals("https://accounts.x.ai/device", prompt.authorizationUrl)
            }
        }
        assertEquals("oauth_access_denied", error.code)
        assertEquals(listOf(2_000L, 2_000L, 7_000L), delays)
    }

    @Test fun expiryAndCancellationStopPollingBeforeAnotherRequest() {
        for (cancel in listOf(false, true)) {
            var nanos = 0L
            var requests = 0
            val controller = AgentRunController()
            val http = http { requests++; deviceResponse(expiresIn = 1) }
            val client = LiveProviderOAuthClient(http, monotonicNow = { nanos }, waitForPoll = { _, delay ->
                nanos += delay * 1_000_000
                if (cancel) controller.cancel()
            })
            if (cancel) {
                assertThrows(AgentRunCancelledException::class.java) {
                    client.login(OAuthProvider.XAI, controller) {}
                }
            } else {
                assertEquals("oauth_login_expired", assertThrows(ProviderAuthException::class.java) {
                    client.login(OAuthProvider.XAI, controller) {}
                }.code)
            }
            assertEquals(1, requests)
        }
    }

    @Test fun rejectsDeviceAuthorizationLinksOutsideTrustedOrigins() {
        val client = LiveProviderOAuthClient(http { 200 to """{"device_code":"test","user_code":"TEST","verification_uri":"https://example.invalid/login","expires_in":600}""" })
        assertEquals("oauth_invalid_response", assertThrows(ProviderAuthException::class.java) {
            client.login(OAuthProvider.XAI, AgentRunController()) { error("Untrusted browser URL") }
        }.code)
    }

    @Test fun codexRefreshUsesItsTokenEndpointAndPreservesUnrotatedRefreshToken() {
        val previous = ProviderCredential(ProviderAuthModes.OPENAI_CHATGPT, CodexOAuth.CLIENT_ID, "account",
            refreshToken = "fake-refresh", accountId = "workspace", scopes = CodexOAuth.SCOPE.split(' ').toSet())
        val client = LiveProviderOAuthClient(http { request ->
            val form = request.body as FormBody
            val values = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals(CodexOAuth.CLIENT_ID, values["client_id"])
            assertEquals("https://auth.openai.com/oauth/token", request.url.toString())
            assertNull(values["resource"])
            assertFalse("scope" in values)
            200 to """{"access_token":"${CodexOAuthFixture.accessToken()}","token_type":"Bearer","expires_in":3600}"""
        })
        val refreshed = client.refresh(previous, AgentRunController())
        assertEquals(previous.refreshToken, refreshed.refreshToken)
        assertEquals(previous.scopes, refreshed.scopes)
        assertFalse(refreshed.toString().contains("fake-"))
    }

    @Test fun codexBrowserLoginBindsPkceCallbackAndVerifiedIdentityToWorkspace() {
        lateinit var authorization: HttpUrl
        val requests = mutableListOf<String>()
        val http = http { request ->
            requests += request.url.toString()
            when (request.url.toString()) {
                OAuthProvider.OPENAI.jwksUrl -> 200 to CodexOAuthFixture.jwks
                OAuthProvider.OPENAI.tokenEndpoint -> {
                    val form = request.body as FormBody
                    val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals(CodexOAuth.CLIENT_ID, values["client_id"])
                    assertEquals("fixture-code", values["code"])
                    assertEquals("authorization_code", values["grant_type"])
                    assertEquals(authorization.queryParameter("redirect_uri"), values["redirect_uri"])
                    val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(values.getValue("code_verifier").toByteArray(Charsets.US_ASCII)))
                    assertEquals(authorization.queryParameter("code_challenge"), challenge)
                    assertNull(values["resource"])
                    200 to JSONObject().put("access_token", CodexOAuthFixture.accessToken())
                        .put("id_token", CodexOAuthFixture.idToken(requireNotNull(authorization.queryParameter("nonce"))))
                        .put("refresh_token", "fixture-refresh").put("token_type", "Bearer")
                        .put("expires_in", 3600).toString()
                }
                else -> error("非预期请求地址")
            }
        }
        val client = LiveProviderOAuthClient(http, callbackReceiver = { LoopbackAuthorizationReceiver(port = 0) })
        val executor = Executors.newSingleThreadExecutor()
        var callback: Future<Int>? = null
        try {
            val credential = client.login(OAuthProvider.OPENAI, AgentRunController()) { prompt ->
                authorization = prompt.authorizationUrl.toHttpUrl()
                assertEquals("https://auth.openai.com/oauth/authorize", authorization.toString().substringBefore('?'))
                assertEquals(CodexOAuth.CLIENT_ID, authorization.queryParameter("client_id"))
                assertEquals("openid profile email offline_access", authorization.queryParameter("scope"))
                assertEquals("S256", authorization.queryParameter("code_challenge_method"))
                assertEquals("true", authorization.queryParameter("codex_cli_simplified_flow"))
                assertEquals("true", authorization.queryParameter("id_token_add_organizations"))
                assertEquals("eta", authorization.queryParameter("originator"))
                assertNull(authorization.queryParameter("resource"))
                callback = executor.submit<Int> {
                    val uri = authorization.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
                        .addQueryParameter("code", "fixture-code")
                        .addQueryParameter("state", authorization.queryParameter("state")).build()
                    val connection = URI(uri.toString()).toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    try { connection.responseCode } finally { connection.disconnect() }
                }
            }
            assertEquals(200, callback!!.get(3, TimeUnit.SECONDS))
            assertEquals("workspace", credential.accountId)
            assertEquals("account", credential.subject)
            assertTrue(credential.planEnabled)
            assertEquals(listOf(OAuthProvider.OPENAI.tokenEndpoint, OAuthProvider.OPENAI.jwksUrl), requests)
            assertNull(client.revoke(credential, AgentRunController()))
            assertEquals(2, requests.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun codexRefreshRejectsAChangedWorkspace() {
        val previous = ProviderCredential(ProviderAuthModes.OPENAI_CHATGPT, CodexOAuth.CLIENT_ID, "account",
            refreshToken = "fixture-refresh", accountId = "original")
        val client = LiveProviderOAuthClient(http { 200 to
            """{"access_token":"${CodexOAuthFixture.accessToken("different")}","token_type":"Bearer","expires_in":3600}""" })
        assertEquals("oauth_account_mismatch", assertThrows(ProviderAuthException::class.java) {
            client.refresh(previous, AgentRunController())
        }.code)
    }

    private fun deviceResponse(expiresIn: Int = 600) = 200 to
        """{"device_code":"fake-device","user_code":"TEST-CODE","verification_uri":"https://accounts.x.ai/device","expires_in":$expiresIn,"interval":2}"""

    private fun http(respond: (Request) -> Pair<Int, String>) = OAuthHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = respond(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
}
