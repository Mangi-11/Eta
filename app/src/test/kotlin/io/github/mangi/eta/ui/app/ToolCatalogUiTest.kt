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
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import io.github.mangi.eta.ui.screens.mcp.collapseDescriptionPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun terminalToolsExposeSeparateLifecyclesInTheSameGroup() {
        val terminal = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "terminal" }
        assertTrue(terminal.tools.map { it.id }.containsAll(listOf("terminal", "terminal_job", "terminal_daemon")))
        assertEquals(R.string.tool_terminal_job, toolDisplayNameResource("terminal_job"))
        assertEquals(R.string.tool_terminal_daemon, toolDisplayNameResource("terminal_daemon"))
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

    @Test
    fun disabledToolsAreOmittedFromModelCatalogAndPersistInPrefs() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        Prefs.setToolsEnabled(listOf("read_sms_code", "tap_area"), false)
        assertFalse(Prefs.isToolEnabled("read_sms_code"))
        assertFalse(Prefs.isToolEnabled("tap"))
        assertFalse(Prefs.isToolEnabled("tap_area"))
        assertTrue(Prefs.isToolEnabled("list_alarms"))

        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            disabledTools = Prefs.disabledTools(),
        )
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertFalse("read_sms_code" in names)
        assertFalse("tap" in names)
        assertFalse("tap_area" in names)
        assertTrue("list_alarms" in names)
        assertTrue("tap_element" in names)

        Prefs.setToolsEnabled(listOf("read_sms_code", "tap_area"), true)
        assertTrue(Prefs.isToolEnabled("read_sms_code"))
        assertTrue(Prefs.isToolEnabled("tap"))
    }

    @Test
    fun mcpDescriptionPreviewCollapsesHeadingsAndBulletLines() {
        val raw = """
            # Search Tool
            - First capability
            * Second capability
        """.trimIndent()
        assertEquals(
            "Search Tool · First capability · Second capability",
            collapseDescriptionPreview(raw),
        )
    }
}
