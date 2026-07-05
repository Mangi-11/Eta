package fuck.andes.agent.model

import fuck.andes.data.model.CustomBody
import fuck.andes.data.model.CustomHeader
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomHeaderAndBodyTest {
    @Test
    fun forbiddenHeadersCannotOverrideTransportOrAuthHeaders() {
        val sanitized = CustomHeaderFilter.sanitize(
            listOf(
                CustomHeader("Authorization", "Bearer bad"),
                CustomHeader("x-api-key", "bad"),
                CustomHeader("host", "example.com"),
                CustomHeader("x-extra", "ok")
            )
        )

        assertEquals(listOf(CustomHeader("x-extra", "ok")), sanitized)
        assertTrue(CustomHeaderFilter.isForbidden("authorization"))
        assertFalse(CustomHeaderFilter.isForbidden("x-extra"))
    }

    @Test
    fun customBodyRecursivelyMergesObjectsAndOverridesLeaves() {
        val target = JSONObject("""{"model":"x","metadata":{"a":1,"b":2},"temperature":1}""")
        val body = listOf(
            CustomBody(
                key = "metadata",
                value = Json.parseToJsonElement("""{"b":3,"c":4}""")
            ),
            CustomBody(
                key = "temperature",
                value = Json.parseToJsonElement("0.2")
            )
        )

        RequestBodyMerge.mergeCustomBody(target, body)

        assertEquals(1, target.getJSONObject("metadata").getInt("a"))
        assertEquals(3, target.getJSONObject("metadata").getInt("b"))
        assertEquals(4, target.getJSONObject("metadata").getInt("c"))
        assertEquals(0.2, target.getDouble("temperature"), 0.0001)
    }
}
