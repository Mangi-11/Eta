package io.github.mangi.eta.agent.model.provider.gemini

import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.catalog.AgentWebToolCatalog
import io.github.mangi.eta.agent.model.provider.AgentModelClient

class GeminiToolSchemaTest {
    @Test
    fun nativeRequestProjectsWebCatalogWithoutChangingItsExclusiveArgumentContract() {
        val catalog = JSONArray().also { AgentWebToolCatalog.appendTo(it) }
        val original = catalog.toString()
        val source = catalog.getJSONObject(1).getJSONObject("function").getJSONObject("parameters")
        val request = GeminiInteractionsRequest.build(
            AgentModelClient.ModelConfig(
                baseUrl = "https://example.test/v1beta", apiKey = "test-key", model = "gemini-3.8-flash",
                providerType = ProviderTypes.GEMINI, systemPrompt = "",
                reasoningEffort = ReasoningEffort.DEFAULT,
            ),
            JSONArray().put(AgentConversationCodec.userTextMessage("测试")), catalog,
        )
        val projected = request.getJSONArray("tools").getJSONObject(1).getJSONObject("parameters")
        assertEquals(original, catalog.toString())
        assertFalse(projected.getBoolean("additionalProperties"))
        for ((index, name) in listOf("url", "document_id").withIndex()) {
            val branch = projected.getJSONArray("oneOf").getJSONObject(index)
            assertEquals(source.getJSONObject("properties").getJSONObject(name).asJson(),
                branch.getJSONObject("properties").getJSONObject(name).asJson())
            assertEquals(name, branch.getJSONArray("required").getString(0))
            assertFalse(branch.has("type"))
        }
        val cases = listOf(
            """{"url":"https://example.test"}""" to true,
            """{"document_id":"doc-1","offset_chars":12000,"max_chars":1000}""" to true,
            """{"url":"https://example.test","document_id":"doc-1"}""" to false,
            "{}" to false,
            """{"url":""}""" to false,
            """{"document_id":7}""" to false,
            """{"url":"https://example.test","unknown":1}""" to false,
            """{"url":"https://example.test","offset_chars":-1}""" to false,
            """{"url":"https://example.test","max_chars":999}""" to false,
        )
        for ((json, expected) in cases) {
            assertEquals("原始合同：$json", expected, acceptsWebSchema(source, JSONObject(json)))
            assertEquals("投影合同：$json", expected, acceptsWebSchema(projected, JSONObject(json)))
        }
    }

    @Test
    fun nestedCombinatorsInheritNearestPropertiesWithoutOverwritingLocalDefinitions() {
        val source = JSONObject("""{
            "properties":{"query":{"type":"string","minLength":1},"enabled":{"type":"boolean"}},
            "allOf":[{"properties":{"query":{"type":"string","minLength":3}},"required":["query"],
                "anyOf":[{"required":["query"]},{"oneOf":[{"required":["enabled"]}]}]}]
        }""")
        val projected = GeminiToolSchema.project(source)
        val allOf = projected.getJSONArray("allOf").getJSONObject(0)
        assertEquals(3, allOf.getJSONObject("properties").getJSONObject("query").getInt("minLength"))
        val anyOf = allOf.getJSONArray("anyOf")
        assertEquals(3, anyOf.getJSONObject(0).getJSONObject("properties").getJSONObject("query").getInt("minLength"))
        assertEquals("boolean", anyOf.getJSONObject(1).getJSONArray("oneOf").getJSONObject(0)
            .getJSONObject("properties").getJSONObject("enabled").getString("type"))
        assertEquals(projected.asJson(), GeminiToolSchema.project(projected).asJson())
    }

    @Test
    fun propertiesItemsAndDefinitionBoundariesDoNotInheritOuterObjectFields() {
        val child = JSONObject("""{"oneOf":[{"required":["outer"]}]}""")
        val source = JSONObject().put("properties", JSONObject()
            .put("outer", JSONObject().put("type", "string"))
            .put("object", JSONObject(child.toString()))
            .put("array", JSONObject().put("items", JSONObject(child.toString()))))
            .put("\$defs", JSONObject().put("nested", JSONObject(child.toString())))
            .put("additionalProperties", JSONObject(child.toString()))
            .put("prefixItems", JSONArray().put(JSONObject(child.toString())))
            .put("anyOf", JSONArray().put(JSONObject(child.toString())))
        val projected = GeminiToolSchema.project(source)
        val boundaryChildren = listOf(
            projected.getJSONObject("properties").getJSONObject("object"),
            projected.getJSONObject("properties").getJSONObject("array").getJSONObject("items"),
            projected.getJSONObject("\$defs").getJSONObject("nested"),
            projected.getJSONObject("additionalProperties"),
            projected.getJSONArray("prefixItems").getJSONObject(0),
        )
        for (boundary in boundaryChildren) assertEquals(child.asJson(), boundary.asJson())
        assertEquals("string", projected.getJSONArray("anyOf").getJSONObject(0)
            .getJSONArray("oneOf").getJSONObject(0).getJSONObject("properties")
            .getJSONObject("outer").getString("type"))
    }

    @Test
    fun nestedObjectStartsItsOwnVisibleProperties() {
        val source = JSONObject("""{"properties":{
            "key":{"type":"string"},
            "child":{"properties":{"key":{"type":"integer"}},"oneOf":[{"required":["key"]}]}
        }}""")
        val child = GeminiToolSchema.project(source).getJSONObject("properties").getJSONObject("child")
        assertEquals("integer", child.getJSONArray("oneOf").getJSONObject(0)
            .getJSONObject("properties").getJSONObject("key").getString("type"))
    }

    @Test
    fun schemaShapedDataIsNeverInterpretedAsSchema() {
        val data = JSONObject("""{"properties":{"value":{"type":"string"}},"oneOf":[{"required":["value"]}]}""")
        val source = JSONObject("""{"properties":{"value":{"type":"string"}},"oneOf":[{"required":["value"]}]}""")
            .put("const", data).put("default", data).put("enum", JSONArray().put(data)).put("examples", JSONArray().put(data))
        val projected = GeminiToolSchema.project(source)
        for (keyword in listOf("const", "default")) assertEquals(data.asJson(), projected.getJSONObject(keyword).asJson())
        for (keyword in listOf("enum", "examples")) assertEquals(data.asJson(), projected.getJSONArray(keyword).getJSONObject(0).asJson())
        assertTrue(projected.getJSONArray("oneOf").getJSONObject(0).has("properties"))
    }

    @Test
    fun restrictiveLocalPropertyRulesAreNotWeakenedByAddingDeclarations() {
        for (keyword in listOf("additionalProperties", "unevaluatedProperties")) {
            for (restriction in listOf(false, JSONObject().put("type", "integer"))) {
                val source = JSONObject("""{"properties":{"value":{"type":"string"}},"oneOf":[{"required":["value"]}]}""")
                source.getJSONArray("oneOf").getJSONObject(0).put(keyword, restriction)
                assertEquals(source.asJson(), GeminiToolSchema.project(source).asJson())
            }
        }
        val open = JSONObject("""{"properties":{"value":{"type":"string"}},"oneOf":[{"required":["value"],"additionalProperties":true}]}""")
        assertTrue(GeminiToolSchema.project(open).getJSONArray("oneOf").getJSONObject(0).has("properties"))
    }

    @Test
    fun restrictiveRulesAlsoPreventInheritedDeclarationsInDescendantBranches() {
        for (keyword in listOf("additionalProperties", "unevaluatedProperties")) {
            val source = JSONObject("""{
                "properties":{"value":{"type":"string"}},
                "oneOf":[{"$keyword":false,"oneOf":[{"required":["value"]}]}]
            }""")
            assertEquals(source.asJson(), GeminiToolSchema.project(source).asJson())
        }
    }

    @Test
    fun resourceIdentitiesAndReferencesAreNotRelocatedOrInheritedAcross() {
        val keywords = listOf("\$id", "id", "\$anchor", "\$dynamicAnchor", "\$recursiveAnchor", "\$ref", "\$dynamicRef", "\$recursiveRef")
        for (keyword in keywords) {
            val source = JSONObject("""{"properties":{"value":{"type":"string"}},"oneOf":[{"required":["value"]}]}""")
            val metadata = if (keyword == "\$recursiveAnchor") true else "fixture"
            source.getJSONObject("properties").getJSONObject("value").put(keyword, metadata)
            assertEquals("引用属性必须保持原位置：$keyword", source.asJson(), GeminiToolSchema.project(source).asJson())
            source.getJSONObject("properties").getJSONObject("value").remove(keyword)
            source.getJSONArray("oneOf").getJSONObject(0).put(keyword, metadata)
            assertEquals("不跨资源作用域继承：$keyword", source.asJson(), GeminiToolSchema.project(source).asJson())
        }
        val nestedAnchor = JSONObject("""{
            "properties":{"value":{"properties":{"child":{"${'$'}anchor":"nested","type":"string"}}}},
            "oneOf":[{"required":["value"]}]
        }""")
        assertEquals(nestedAnchor.asJson(), GeminiToolSchema.project(nestedAnchor).asJson())
    }

    @Test
    fun copyingDoesNotAliasSourceOrSiblingDefinitionsAndUnknownFieldsAreNotInvented() {
        val source = JSONObject("""{
            "properties":{"value":{"type":"string","default":{"${'$'}id":"literal-data"}}},
            "oneOf":[{"required":["value","missing"]},{"required":["value"]}]
        }""")
        val original = source.toString()
        val projected = GeminiToolSchema.project(source)
        val first = projected.getJSONArray("oneOf").getJSONObject(0).getJSONObject("properties")
        assertFalse(first.has("missing"))
        first.getJSONObject("value").put("minLength", 7)
        assertFalse(projected.getJSONObject("properties").getJSONObject("value").has("minLength"))
        assertFalse(projected.getJSONArray("oneOf").getJSONObject(1)
            .getJSONObject("properties").getJSONObject("value").has("minLength"))
        assertEquals(original, source.toString())
    }

    private fun JSONObject.asJson() = Json.parseToJsonElement(toString())

    /** 独立解释网页工具使用的 JSON Schema 子集；遇到新关键字时显式扩展测试，避免静默忽略约束。 */
    private fun acceptsWebSchema(schema: JSONObject, value: Any): Boolean {
        val supported = setOf("type", "properties", "required", "additionalProperties", "oneOf",
            "minLength", "maxLength", "minimum", "maximum", "description", "default")
        for (keyword in schema.keys()) require(keyword in supported) { "未覆盖的测试关键字：$keyword" }
        val typeMatches = when (schema.optString("type")) {
            "" -> true
            "object" -> value is JSONObject
            "string" -> value is String
            "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
            else -> error("未覆盖的测试类型")
        }
        if (!typeMatches) return false
        if (value is String) {
            val length = value.codePointCount(0, value.length)
            if (length < schema.optInt("minLength", 0) || length > schema.optInt("maxLength", Int.MAX_VALUE)) return false
        }
        if (value is Number && (value.toDouble() < schema.optDouble("minimum", Double.NEGATIVE_INFINITY) ||
            value.toDouble() > schema.optDouble("maximum", Double.POSITIVE_INFINITY))) return false
        if (value is JSONObject) {
            schema.optJSONArray("required")?.let { required ->
                for (index in 0 until required.length()) if (!value.has(required.getString(index))) return false
            }
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            for (name in value.keys()) {
                if (properties.has(name)) {
                    if (!acceptsWebSchema(properties.getJSONObject(name), value.get(name))) return false
                } else if (schema.opt("additionalProperties") == false) return false
            }
        }
        schema.optJSONArray("oneOf")?.let { branches ->
            if ((0 until branches.length()).count { acceptsWebSchema(branches.getJSONObject(it), value) } != 1) return false
        }
        return true
    }
}
