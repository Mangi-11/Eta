package fuck.andes.agent.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentExternalArchivePayloadTest {
    @Test
    fun roundTripPreservesArchiveFieldsAndOpaqueAdapterPayload() {
        val payload = AgentExternalArchivePayload(
            userText = "查一下系统状态",
            conversationKey = "session-1",
            title = "外部入口",
            thinkingEnabled = true,
            adapterPayload = JSONObject()
                .put("recordId", "record-1")
                .put("roomId", "room-1"),
        )

        val restored = AgentExternalArchivePayload.from(payload.toJson())

        requireNotNull(restored)
        assertEquals(payload.userText, restored.userText)
        assertEquals(payload.conversationKey, restored.conversationKey)
        assertEquals(payload.title, restored.title)
        assertEquals(payload.thinkingEnabled, restored.thinkingEnabled)
        assertEquals("record-1", restored.adapterPayload.optString("recordId"))
        assertEquals("room-1", restored.adapterPayload.optString("roomId"))
    }

    @Test
    fun fromRejectsNonArchivePayload() {
        assertNull(AgentExternalArchivePayload.from("""{"userText":"legacy"}"""))
    }
}
