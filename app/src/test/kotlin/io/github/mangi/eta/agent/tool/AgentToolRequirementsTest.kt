package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRequirementsTest {
    @Test
    fun everyRegisteredToolHasExactlyOneRequirement() {
        val tools = catalog(root = true).also { CharacterMemoryTools.appendSchemas(it) }
        assertEquals(AgentToolRequirements.toolNames - setOf("run_command"), tools.names())
        assertFalse("旧命令名仅保留执行兼容，不再向模型暴露", "run_command" in tools.names())
        assertEquals(tools.length(), tools.names().size)
        assertFalse(tools.toString().contains("rootRequirement"))
    }

    @Test
    fun ordinaryCatalogDoesNotExposeCharacterMemoryTools() {
        assertTrue(catalog(root = true).names().none { it in CharacterMemoryTools.NAMES })
    }

    @Test
    fun unknownToolCannotEnterTheModelCatalog() {
        val unknown = JSONArray().put(JSONObject().put("function", JSONObject().put("name", "new_tool")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolRequirements.project(unknown, rootAvailable = true)
        }
    }

    @Test
    fun rootlessProjectionRemovesPrivilegedToolsAndNarrowsMixedSchemasWithoutMutatingSource() {
        val original = catalog(root = true)
        val projected = AgentToolRequirements.project(original, rootAvailable = false)
        val required = AgentToolRequirements.toolNames.filter {
            AgentToolRequirements.rootRequirement(it) == RootRequirement.REQUIRED
        }
        assertTrue(required.isNotEmpty())
        assertTrue(required.none { it in projected.names() })
        assertTrue(setOf("terminal", "read_file", "read_image", "observe_screen", "browser_use").all {
            it in projected.names()
        })
        assertEquals("[\"user\"]", projected.properties("terminal").getJSONObject("identity").getJSONArray("enum").toString())
        assertEquals(2, original.properties("terminal").getJSONObject("identity").getJSONArray("enum").length())
        assertFalse(projected.properties("press_key").getJSONObject("button").getJSONArray("enum").toString().contains("PASTE"))
        assertFalse(projected.toString().contains("/data/local/tmp/eta"))
        assertFalse(projected.properties("read_image").getJSONObject("path").toString().contains("Root"))
    }

    @Test
    fun ordinaryAuthorizationIsIndependentFromRootAndForegroundIntentsDoNotNeedAccessibility() {
        val restricted = AgentToolCapabilities(
            rootAvailable = false, accessibilityAvailable = false,
            notificationsAllowed = false, usageAllowed = false, locationAllowed = false, colorOs = false,
        )
        val names = restricted.project(catalog(root = true)).names()
        assertTrue(setOf("launch_app", "open_uri", "terminal", "browser_use").all { it in names })
        assertTrue(setOf("observe_screen", "wait_for_text", "wait_for_package", "recent_notifications", "app_usage_summary", "get_current_location").none { it in names })
        assertEquals("ROOT_REQUIRED", restricted.unavailableCode("search_notes"))
        assertEquals("DEVICE_UNSUPPORTED", restricted.copy(rootAvailable = true).unavailableCode("search_notes"))
        assertEquals(null, restricted.copy(notificationsAllowed = true).unavailableCode("recent_notifications"))
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", restricted.copy(rootAvailable = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(rootAvailable = true, colorOs = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(accessibilityAvailable = true).unavailableCode("observe_screen"))
        assertEquals(null, restricted.copy(accessibilityRecoveryAvailable = true).unavailableCode("observe_screen"))
    }

    @Test
    fun legacyRootArgumentsAreDeniedEvenWhenCallingAMixedToolDirectly() {
        assertTrue(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "root"), false))
        assertTrue(AgentToolRequirements.rootDenied("press_key", JSONObject().put("button", "PASTE"), false))
        assertTrue(AgentToolRequirements.rootDenied("set_setting", JSONObject(), false))
        assertFalse(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "user"), false))
        assertFalse(AgentToolRequirements.rootDenied("set_setting", JSONObject(), true))
    }

    @Test
    fun shizukuRetainsAdbLevelToolsWhileKeepingPrivateDataRootOnly() {
        val original = catalog(root = true)
        val projected = AgentToolRequirements.project(original, rootAvailable = false, shizukuAvailable = true)
        val names = projected.names()
        assertTrue(setOf(
            "set_setting", "set_device_state", "get_device_state",
            "get_display_state", "set_brightness", "set_screen_timeout",
            "set_do_not_disturb", "app_state_control",
            "get_logcat", "top_memory_apps", "top_storage_apps",
        ).all { it in names })
        // get_setting 为 PARTIAL：常驻投影（公开读优先，特权通道兜底），与 Shizuku 门控无关。
        assertTrue("get_setting" in names)
        // search_calendar_events 在 main 改为 PARTIAL（CALENDAR_READ），同样常驻投影。
        assertTrue("search_calendar_events" in names)
        assertTrue(setOf(
            "wifi_credentials", "read_sms_code", "search_contacts", "search_messages",
            "list_alarms", "get_health_summary",
            "search_system_memories", "search_wechat_chat_images",
        ).none { it in names })
    }

    @Test
    fun shizukuSatisfiedToolsAreNotDeniedButRootOnlyToolsStillAre() {
        assertFalse(AgentToolRequirements.rootDenied("set_setting", JSONObject(), false, shizukuAvailable = true))
        assertFalse(AgentToolRequirements.rootDenied("get_logcat", JSONObject(), false, shizukuAvailable = true))
        assertTrue(AgentToolRequirements.rootDenied("wifi_credentials", JSONObject(), false, shizukuAvailable = true))
        assertTrue(AgentToolRequirements.rootDenied("read_sms_code", JSONObject(), false, shizukuAvailable = true))
        assertTrue(AgentToolRequirements.rootDenied("search_contacts", JSONObject(), false, shizukuAvailable = true))
        assertTrue(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "root"), false, shizukuAvailable = true))
        assertFalse(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "user"), false, shizukuAvailable = true))
    }

    @Test
    fun shizukuCapabilitiesExposeAdbToolsWithoutUnlockingRootShell() {
        val shizukuOnly = AgentToolCapabilities(rootAvailable = false, shizukuAvailable = true)
        assertEquals(null, shizukuOnly.unavailableCode("set_setting"))
        assertEquals(null, shizukuOnly.unavailableCode("get_logcat"))
        assertEquals(null, shizukuOnly.unavailableCode("get_device_state"))
        assertEquals(null, shizukuOnly.unavailableCode("get_display_state"))
        assertEquals(null, shizukuOnly.unavailableCode("set_brightness"))
        assertEquals(null, shizukuOnly.unavailableCode("set_screen_timeout"))
        assertEquals(null, shizukuOnly.unavailableCode("set_do_not_disturb"))
        assertEquals("ROOT_REQUIRED", shizukuOnly.unavailableCode("wifi_credentials"))
        assertEquals("ROOT_REQUIRED", shizukuOnly.unavailableCode("search_contacts"))
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false).unavailableCode("set_setting"))
    }

    @Test
    fun shizukuOnlyNarrowsPhoneInjectedDeviceStateTargets() {
        val shizukuOnly = AgentToolCapabilities(rootAvailable = false, shizukuAvailable = true)
        val projected = shizukuOnly.project(catalog(root = true))
        val targets = projected.properties("get_device_state").getJSONObject("target").getJSONArray("enum")
        val values = (0 until targets.length()).map { targets.getString(it) }.toSet()
        assertTrue("wifi" in values)
        assertTrue("mobile_data" !in values)
        assertTrue("night_light" !in values)
        val setTargets = projected.properties("set_device_state").getJSONObject("target").getJSONArray("enum")
        val setValues = (0 until setTargets.length()).map { setTargets.getString(it) }.toSet()
        assertTrue("wifi" in setValues)
        assertTrue("mobile_data" !in setValues)
        val rooted = AgentToolCapabilities(rootAvailable = true).project(catalog(root = true))
        val rootedValues = (0 until rooted.properties("get_device_state").getJSONObject("target").getJSONArray("enum").length())
            .map { rooted.properties("get_device_state").getJSONObject("target").getJSONArray("enum").getString(it) }.toSet()
        assertTrue("mobile_data" in rootedValues)
    }

    @Test
    fun frameworkConnectionDoesNotGrantRootAndRootSnapshotDoesNotRequireFramework() {
        assertEquals(LsposedRequirement.OPTIONAL, AgentToolRequirements.find("search_system_memories")?.lsposedRequirement)
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true)
            .unavailableCode("search_system_memories"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, lsposedAvailable = false)
            .unavailableCode("search_system_memories"))
    }

    @Test
    fun indexedSearchNeedsRootAndColorOsButDoesNotRequireAnXposedConnection() {
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, colorOs = true)
            .unavailableCode("search_bills"))
        assertEquals("DEVICE_UNSUPPORTED", AgentToolCapabilities(rootAvailable = true, colorOs = false)
            .unavailableCode("search_bills"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, colorOs = true, lsposedAvailable = false)
            .unavailableCode("search_bills"))
    }

    private fun catalog(root: Boolean) = AgentToolCatalog.build(
        terminalTools = true, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = root),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.properties(name: String): JSONObject = (0 until length())
        .map { getJSONObject(it).getJSONObject("function") }
        .single { it.getString("name") == name }.getJSONObject("parameters").getJSONObject("properties")
}
