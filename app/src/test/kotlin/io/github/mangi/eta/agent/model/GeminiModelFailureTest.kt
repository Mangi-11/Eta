package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiModelFailureTest {
    @Test
    fun invalidArgumentsExposeOnlyKnownProtocolFieldsAndErrorCodes() {
        val body = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"private-prompt-fixture",
            "details":[{"fieldViolations":[{"field":"tools[12].parameters.properties.private_key_fixture",
            "description":"private-key-fixture"},{"field":"generation_config.thinking_level"}]}]}}"""
        val failure = AgentModelFailure.http(400, body, includeParameterDetails = true)
        assertTrue(failure.message.orEmpty().contains("INVALID_ARGUMENT"))
        assertTrue(failure.message.orEmpty().contains("tools[12].parameters.properties.<自定义字段>"))
        assertTrue(failure.message.orEmpty().contains("generation_config.thinking_level"))
        assertFalse(failure.message.orEmpty().contains("private"))
        assertFalse(failure.retryable)
        assertEquals("HTTP_400", failure.code)
    }

    @Test
    fun invalidOrUntrustedDiagnosticFieldsNeverLeakResponseText() {
        for (field in listOf("private_key_fixture", "input[0].text='private-key-fixture'", "input[9999999].text")) {
            val body = JSONObject().put("error", JSONObject()
                .put("code", "private-key-fixture").put("param", field).put("message", "private-prompt-fixture")).toString()
            val failure = AgentModelFailure.http(400, body, includeParameterDetails = true)
            assertEquals(AgentModelFailure.http(400, "{}").message, failure.message)
        }
    }

    @Test
    fun legacyErrorsKeepTheirMessageUnlessParameterDiagnosticsAreRequested() {
        val body = """{"error":{"code":"invalid_request","param":"input[0].type"}}"""
        assertEquals(AgentModelFailure.http(400, "{}").message, AgentModelFailure.http(400, body).message)
        val message = AgentModelFailure.http(400, body, includeParameterDetails = true).message.orEmpty()
        assertTrue(message.contains("invalid_request"))
        assertTrue(message.contains("input[0].type"))
    }

    @Test
    fun parameterDiagnosticsDoNotMaskBillingFailures() {
        val body = """{"error":{"status":"FAILED_PRECONDITION","message":"billing is required"}}"""
        val failure = AgentModelFailure.http(400, body, includeParameterDetails = true)
        assertTrue(failure.message.orEmpty().contains("额度或计费受限"))
        assertFalse(failure.retryable)
    }

    @Test
    fun invalidKeyAtHttp400IsAuthenticationFailureWithoutServerText() {
        val failure = AgentModelFailure.http(400,
            """{"error":{"status":"INVALID_ARGUMENT","message":"private-key-fixture","details":[{"reason":"API_KEY_INVALID"}]}}""")
        assertEquals("PROVIDER_AUTH_FAILED", failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.message.orEmpty().contains("private-key-fixture"))
    }

    @Test
    fun canonicalTransientStreamStatusesUseExistingRetryPolicy() {
        for (status in listOf("RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED")) {
            assertTrue(AgentModelFailure.stream(JSONObject().put("status", status), "请求暂时失败").retryable)
        }
        assertFalse(AgentModelFailure.stream(JSONObject().put("status", "INVALID_ARGUMENT"), "参数无效").retryable)
    }

    @Test
    fun inputTokenOverflowHasExplicitNonRetryableFailure() {
        for (message in listOf(
            "The input token count (123) exceeds the maximum number of tokens allowed (100).",
            "Total token count (1500000) exceeds the maximum allowed (1048576).",
            "Request payload size exceeds the limit: 20971520 bytes.",
        )) {
            val failure = AgentModelFailure.http(400, JSONObject().put("error", JSONObject().put("message", message)).toString())
            assertEquals("CONTEXT_OVERFLOW", failure.code)
            assertFalse(failure.retryable)
        }
        val outputLimitConfigFailure = AgentModelFailure.http(
            400,
            """{"error":{"message":"max_output_tokens exceeds the allowed limit"}}""",
        )
        assertEquals("HTTP_400", outputLimitConfigFailure.code)
    }
}
