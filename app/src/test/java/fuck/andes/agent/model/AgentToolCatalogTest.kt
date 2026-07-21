package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test
    fun featureFlagsProduceExactUniqueToolUnions() {
        val base = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).toolNames()
        val baseTools = base.toSet()
        val variants = listOf(
            ToolVariant(terminalTools = false, browserTools = false, addedTools = emptySet()),
            ToolVariant(terminalTools = false, browserTools = true, addedTools = BROWSER_TOOLS),
            ToolVariant(terminalTools = true, browserTools = false, addedTools = TERMINAL_TOOLS),
            ToolVariant(
                terminalTools = true,
                browserTools = true,
                addedTools = BROWSER_TOOLS + TERMINAL_TOOLS,
            ),
        )

        assertEquals(base.size, baseTools.size)
        assertTrue(base.containsAll(setOf("observe_screen", "skills_list", "skills_read")))
        assertFalse("browser_use" in base)
        assertFalse("terminal" in base)

        variants.forEach { variant ->
            val names = AgentToolCatalog.build(
                terminalTools = variant.terminalTools,
                browserTools = variant.browserTools,
            ).toolNames()
            val label = "terminal=${variant.terminalTools}, browser=${variant.browserTools}"

            assertEquals("$label must not contain duplicate tools", names.size, names.toSet().size)
            assertEquals("$label must be an exact union", baseTools + variant.addedTools, names.toSet())
        }
    }

    @Test
    fun elementToolsRequireObservationIdFromTheSameObservation() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        listOf("tap_element", "long_press_element", "scroll_element").forEach { name ->
            val function = tools.function(name)
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")

            assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
            assertTrue("observation_id must be required for $name", "observation_id" in parameters.requiredNames())
            assertTrue(function.getString("description").contains("同一次"))
            assertTrue(function.getString("description").contains("observe_screen"))
            assertTrue(function.getString("description").contains("重新观察"))
        }
    }

    @Test
    fun scrollDirectionsUseContentBrowsingSemantics() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val expectedDirections = listOf("up", "down", "left", "right")

        listOf("scroll", "scroll_element").forEach { name ->
            val function = tools.function(name)
            val directions = function
                .getJSONObject("parameters")
                .getJSONObject("properties")
                .getJSONObject("direction")
                .getJSONArray("enum")
                .stringValues()

            assertEquals(expectedDirections, directions)
            assertTrue(function.getString("description").contains("down 显示下方内容"))
            assertTrue(function.getString("description").contains("up 显示上方内容"))
        }
    }

    @Test
    fun indexedTextToolsDescribeObservationPairingWithoutRequiringItForFocusedInput() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        listOf("replace_text", "clear_text").forEach { name ->
            val function = tools.function(name)
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")

            assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
            assertFalse("observation_id remains optional when $name targets focus", "observation_id" in parameters.requiredNames())
            assertTrue(function.getString("description").contains("index 与 observation_id"))
            assertTrue(properties.getJSONObject("index").getString("description").contains("同时传入"))
        }

        val inputText = tools.function("input_text")
        val inputProperties = inputText
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertEquals("integer", inputProperties.getJSONObject("index").getString("type"))
        assertEquals(
            "string",
            inputProperties.getJSONObject("observation_id").getString("type"),
        )
        assertTrue(
            inputProperties.getJSONObject("index").getString("description")
                .contains("observation_id"),
        )
    }

    @Test
    fun textToolsDeclareTheSameLimitsAsRuntime() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        assertEquals(1_000, tools.maxTextLength("input_text"))
        assertEquals(4_000, tools.maxTextLength("replace_text"))
        assertEquals(20_000, tools.maxTextLength("set_clipboard"))
        assertEquals(20_000, tools.maxTextLength("paste_text"))
    }

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { index -> getJSONObject(index).getJSONObject("function") }
            .first { function -> function.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> =
        optJSONArray("required")?.stringValues()?.toSet().orEmpty()

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.maxTextLength(name: String): Int =
        function(name)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("text")
            .getInt("maxLength")

    private data class ToolVariant(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val addedTools: Set<String>,
    )

    private companion object {
        val BROWSER_TOOLS = setOf("browser_use")
        val TERMINAL_TOOLS = setOf(
            "terminal",
            "run_command",
            "read_file",
            "write_file",
            "list_directory",
        )
    }
}
