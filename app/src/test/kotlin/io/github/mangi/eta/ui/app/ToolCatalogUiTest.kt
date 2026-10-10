package io.github.mangi.eta.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TravelExplore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val runtimeNames = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        (runtimeNames + cardIds).distinct().forEach { name ->
            assertNotEquals(name, Icons.Rounded.Build, iconForTool(name))
        }
    }

    @Test
    fun hostedSearchNamesAndDynamicMcpNamesUseTheirToolIcons() {
        assertEquals(Icons.Rounded.TravelExplore, iconForTool("网页搜索"))
        assertEquals(iconForTool("网页搜索"), iconForTool("web_search"))
        assertEquals(Icons.Rounded.Language, iconForTool("browser_use"))
        assertEquals(Icons.AutoMirrored.Rounded.Article, iconForTool("fetch_url"))
        assertEquals(Icons.Rounded.Extension, iconForTool("mcp_server_search_012345"))
        assertEquals(Icons.Rounded.Build, iconForTool("unknown_tool"))
    }

    @Test
    fun publicWebCardsHaveNamesAndStayInTheWebGroup() {
        val web = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "web" }
        assertTrue(web.tools.map { it.id }.containsAll(listOf("web_search", "fetch_url", "browser_use")))
        assertEquals(R.string.tool_web_search, toolDisplayNameResource("web_search"))
        assertEquals(R.string.tool_fetch_url, toolDisplayNameResource("fetch_url"))
    }

    @Test
    fun batchCardIsVisibleInCurrentDeviceToolsWithoutRoot() {
        val context = RuntimeEnvironment.getApplication()
        val groups = buildToolsState(context).groups
        val appGroup = projectToolGroups(groups, false, false, false).single { it.id == "app" }
        val batch = appGroup.tools.single { it.id == "batch" }
        assertEquals(context.getString(R.string.tool_batch), batch.title)
        assertEquals(context.getString(R.string.tool_batch_description), batch.summary)
        assertEquals(R.string.tool_batch, toolDisplayNameResource("batch"))
        assertEquals(RootRequirement.NONE, toolCardRequirement("batch").rootRequirement)
    }

    @Test
    fun personalQueriesAndNativeOperationsHaveSeparateGroups() {
        val group = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "personal_data" }
        assertTrue(group.tools.any { it.id == "search_bills" })
        assertTrue(group.tools.none { it.id == "personal_context" || it.id == "create_note" })
        val operations = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "device_direct" }
        assertTrue(operations.tools.any { it.id == "set_flashlight" })
        assertTrue(operations.tools.any { it.id == "create_calendar_event" })
        assertEquals(RootRequirement.REQUIRED, toolCardRequirement("search_bills").rootRequirement)
        assertTrue(toolCardRequirement("search_bills").colorOs)
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState(RuntimeEnvironment.getApplication()).groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertEquals(groups, projectToolGroups(groups, false, true, true))

        val ordinaryCards = projectToolGroups(groups, false, false, false).flatMap { it.tools }
        assertTrue(ordinaryCards.isNotEmpty())
        assertEquals(allCards.filter { card ->
            val requirement = toolCardRequirement(card.id)
            requirement.rootRequirement != RootRequirement.REQUIRED && !requirement.colorOs
        }, ordinaryCards)
    }
}
