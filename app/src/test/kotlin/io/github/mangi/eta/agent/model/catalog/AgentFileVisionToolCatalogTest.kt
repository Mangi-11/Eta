package io.github.mangi.eta.agent.model.catalog

import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileVisionToolCatalogTest {
    @Test
    fun readImageFollowsFileToolsInsteadOfPersonalDataReads() {
        assertFalse("read_image" in names(terminalTools = false, sensitiveReads = true))
        assertTrue("read_image" in names(terminalTools = true, sensitiveReads = false))
        assertTrue(readImageDescription().contains("同一轮最多调用一次"))
        assertTrue(readImageDescription().contains("下一轮读取下一张"))
    }

    private fun readImageDescription(): String {
        val tools = AgentToolCatalog.build(terminalTools = true, browserTools = false)
        return (0 until tools.length())
            .map(tools::getJSONObject)
            .first { it.getJSONObject("function").getString("name") == "read_image" }
            .getJSONObject("function")
            .getString("description")
    }

    private fun names(terminalTools: Boolean, sensitiveReads: Boolean): Set<String> =
        AgentToolCatalog.build(
            terminalTools = terminalTools,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = sensitiveReads,
            deviceSensitiveActionTools = false,
        ).toolNames()

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
