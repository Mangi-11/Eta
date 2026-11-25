package io.github.mangi.eta.agent.model.catalog

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentFileToolCatalogTest {
    @Test fun fileToolDescriptionsReflectAvailableIdentity() {
        val tools = AgentToolCatalog.build(true, false, capabilities = AgentToolCapabilities(rootAvailable = false))
        val fileTools = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .filter { it.getString("name") in AgentFileToolCatalog.names }
        assertEquals(AgentFileToolCatalog.names, fileTools.map { it.getString("name") }.toSet())
        fileTools.forEach { tool ->
            val identities = tool.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("identity").getJSONArray("enum")
            assertEquals(1, identities.length())
            assertEquals("user", identities.getString(0))
        }
    }
}
