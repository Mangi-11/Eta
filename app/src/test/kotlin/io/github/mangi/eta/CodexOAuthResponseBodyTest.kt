package io.github.mangi.eta

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexOAuthResponseBodyTest {
    @Test
    fun tokenJsonLargerThanFourKilobytesIsReadWithoutTruncation() {
        val accessToken = "fake-access-token-" + "a".repeat(5_000)
        val body = """{"access_token":"$accessToken","expires_in":3600}"""

        response(body).use { response ->
            val text = CodexOAuthResponseBody.read(response, CodexOAuthStage.TOKEN_EXCHANGE)
            assertEquals(accessToken, JSONObject(text).getString("access_token"))
        }
    }

    @Test
    fun responseOverOneMiBIsRejectedWithoutLeakingBody() {
        val fakeSecret = "fake-oauth-secret-for-test-only"
        val body = """{"access_token":"$fakeSecret","padding":"${"x".repeat(CodexOAuthResponseBody.MAX_RESPONSE_BYTES)}"}"""

        val failure = response(body).use { response ->
            assertThrows(CodexOAuthHttpException::class.java) {
                CodexOAuthResponseBody.read(response, CodexOAuthStage.TOKEN_EXCHANGE)
            }
        }

        assertEquals("response_too_large", failure.errorCode)
        assertEquals(200, failure.httpStatus)
        assertFalse(failure.message.orEmpty().contains(fakeSecret))
    }

    private fun response(body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://auth.openai.com/oauth/token").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
