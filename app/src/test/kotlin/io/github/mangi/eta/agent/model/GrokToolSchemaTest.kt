package io.github.mangi.eta.agent.model

import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GrokToolSchemaTest {
    @Test
    fun requiredBranchesInheritObjectConstraintsWithoutMutatingTheSource() {
        for (union in listOf("oneOf", "anyOf")) {
            val source = JSONObject("""{
              "type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}},
              "required":["a"],"additionalProperties":false,
              "$union":[{"required":["a"]},{"required":["b"]}]
            }""")
            val original = source.toString()
            val projected = GrokToolSchema.project(source)
            val branches = projected.getJSONArray(union)
            for (index in 0 until branches.length()) {
                val branch = branches.getJSONObject(index)
                assertEquals("object", branch.getString("type"))
                assertFalse(branch.getBoolean("additionalProperties"))
                assertEquals(source.getJSONObject("properties").toString(), branch.getJSONObject("properties").toString())
                assertEquals(if (index == 0) "[\"a\"]" else "[\"a\",\"b\"]", branch.getJSONArray("required").toString())
            }
            branches.getJSONObject(0).getJSONObject("properties").remove("a")
            assertEquals(original, source.toString())
            assertEquals(2, branches.getJSONObject(1).getJSONObject("properties").length())
        }
    }

    @Test
    fun completeBranchesNestedUnionsAndReferencesAreNotRewritten() {
        val fixtures = listOf(
            """{"type":"object","oneOf":[{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}]}""",
            """{"type":"object","properties":{"value":{"anyOf":[{"type":"string"},{"type":"integer"}]}}}""",
            """{"type":"object","properties":{"value":{"${'$'}ref":"#/${'$'}defs/value"}},"oneOf":[{"required":["value"]}]}""",
            """{"type":"object","oneOf":[{"required":["a"]},{"properties":{"b":{"type":"number"}},"required":["b"]}]}""",
        )
        for (fixture in fixtures) {
            assertEquals(Json.parseToJsonElement(fixture),
                Json.parseToJsonElement(GrokToolSchema.project(JSONObject(fixture)).toString()))
        }
    }
}
