package fuck.andes.agent.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPayloadLimiterTest {
    @Test
    fun `limits multibyte text by utf8 bytes without splitting surrogate pairs`() {
        val source = "网页🙂".repeat(4_000)
        val payload = BrowserPayloadLimiter.serialize(
            JSONObject()
                .put("ok", true)
                .put("action", "get_readable")
                .put("offset", 120)
                .put("text", source)
                .put("returned_chars", source.length)
                .put("truncated", false),
            maxBytes = 2_048,
        )

        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 2_048)
        val decoded = JSONObject(payload)
        val text = decoded.getString("text")
        assertTrue(text.isNotEmpty())
        assertTrue(decoded.getBoolean("payload_truncated"))
        assertEquals(120 + text.length, decoded.getInt("next_offset"))
        assertTrue(text.lastOrNull()?.isHighSurrogate() != true)
    }

    @Test
    fun `drops trailing element descriptions until payload fits`() {
        val elements = JSONArray().apply {
            repeat(40) { index ->
                put(
                    JSONObject()
                        .put("selector", "#item-$index")
                        .put("text", "很长的元素说明".repeat(40))
                )
            }
        }
        val payload = BrowserPayloadLimiter.serialize(
            JSONObject()
                .put("ok", true)
                .put("action", "find_elements")
                .put("element_count", elements.length())
                .put("elements", elements),
            maxBytes = 2_048,
        )

        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 2_048)
        val decoded = JSONObject(payload)
        assertTrue(decoded.getJSONArray("elements").length() < 40)
        assertTrue(decoded.getBoolean("elements_truncated"))
        assertEquals(decoded.getJSONArray("elements").length(), decoded.getInt("element_count"))
    }
}
