package io.github.mangi.eta

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexDeviceFlowProtocolTest {
    @Test
    fun pollIntervalIsAtLeastThreeSeconds() {
        assertEquals(3L, CodexDeviceFlowProtocol.normalizedPollIntervalSeconds(0L))
        assertEquals(3L, CodexDeviceFlowProtocol.normalizedPollIntervalSeconds(1L))
        assertEquals(5L, CodexDeviceFlowProtocol.normalizedPollIntervalSeconds(5L))
    }

    @Test
    fun pollPayloadMatchesDeviceFlowContractWithoutClientId() {
        val payload = JSONObject(
            CodexDeviceFlowProtocol.pollRequestPayload("fake-device-id", "TEST-CODE"),
        )

        assertEquals("fake-device-id", payload.getString("device_auth_id"))
        assertEquals("TEST-CODE", payload.getString("user_code"))
        assertFalse(payload.has("client_id"))
        assertEquals(2, payload.length())
    }
}
