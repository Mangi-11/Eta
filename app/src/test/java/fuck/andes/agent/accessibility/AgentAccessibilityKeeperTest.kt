package fuck.andes.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAccessibilityKeeperTest {
    private val target = AccessibilityServiceIdentity(
        packageName = "fuck.andes",
        className = "fuck.andes.agent.accessibility.AgentAccessibilityService",
    )

    @Test
    fun `matches only the exact enabled service`() {
        assertTrue(
            AgentAccessibilityKeeper.isServiceEnabled(
                enabledComponents = listOf(
                    AccessibilityServiceIdentity("other.package", "other.package.Service"),
                    target,
                ),
                target = target,
            ),
        )
        assertFalse(
            AgentAccessibilityKeeper.isServiceEnabled(
                enabledComponents = listOf(
                    AccessibilityServiceIdentity("fuck.andes", "${target.className}Suffix"),
                ),
                target = target,
            ),
        )
    }

    @Test
    fun `already connected service skips root`() {
        var rootCalls = 0
        val result = AgentAccessibilityKeeper.ensureEnabled(
            targetComponent = TARGET_COMPONENT,
            shortComponent = SHORT_COMPONENT,
            userId = 0,
            serviceAvailable = { true },
            runRootCommand = {
                rootCalls++
                RootCommandResult(exitCode = 0, output = "ok:0")
            },
            awaitServiceBinding = { false },
        )

        assertTrue(result.available)
        assertFalse(result.rootAttempted)
        assertEquals(0, rootCalls)
    }

    @Test
    fun `root enable waits for the real service binding`() {
        var receivedCommand = ""
        val result = AgentAccessibilityKeeper.ensureEnabled(
            targetComponent = TARGET_COMPONENT,
            shortComponent = SHORT_COMPONENT,
            userId = 0,
            serviceAvailable = { false },
            runRootCommand = { command ->
                receivedCommand = command
                RootCommandResult(exitCode = 0, output = "ok:1")
            },
            awaitServiceBinding = { true },
        )

        assertTrue(result.available)
        assertTrue(result.rootAttempted)
        assertTrue(result.settingChanged)
        assertTrue(receivedCommand.contains("enabled_accessibility_services"))
        assertTrue(receivedCommand.contains(TARGET_COMPONENT))
    }

    @Test
    fun `root failure rejects the gui operation`() {
        var bindingChecks = 0
        val result = AgentAccessibilityKeeper.ensureEnabled(
            targetComponent = TARGET_COMPONENT,
            shortComponent = SHORT_COMPONENT,
            userId = 0,
            serviceAvailable = { false },
            runRootCommand = { RootCommandResult(exitCode = 1) },
            awaitServiceBinding = {
                bindingChecks++
                true
            },
        )

        assertFalse(result.available)
        assertEquals("ACCESSIBILITY_ROOT_ENABLE_FAILED", result.code)
        assertEquals(0, bindingChecks)
    }

    @Test
    fun `binding timeout rejects the gui operation after settings were written`() {
        val result = AgentAccessibilityKeeper.ensureEnabled(
            targetComponent = TARGET_COMPONENT,
            shortComponent = SHORT_COMPONENT,
            userId = 0,
            serviceAvailable = { false },
            runRootCommand = { RootCommandResult(exitCode = 0, output = "ok:0") },
            awaitServiceBinding = { false },
        )

        assertFalse(result.available)
        assertTrue(result.rootAttempted)
        assertFalse(result.settingChanged)
        assertEquals("ACCESSIBILITY_BIND_TIMEOUT", result.code)
    }

    @Test
    fun `enable command preserves existing services without toggling the master switch off`() {
        val command = AgentAccessibilityKeeper.buildEnableCommand(
            targetComponent = TARGET_COMPONENT,
            shortComponent = SHORT_COMPONENT,
            userId = 10,
        )

        assertTrue(command.contains("settings --user \"\$user_id\" get secure"))
        assertTrue(command.contains("new_services=\"\$services:\$target\""))
        assertTrue(command.contains("put secure accessibility_enabled 1"))
        assertFalse(command.contains("put secure accessibility_enabled 0"))
        assertTrue(command.contains("user_id='10'"))
    }

    private companion object {
        const val TARGET_COMPONENT =
            "fuck.andes/fuck.andes.agent.accessibility.AgentAccessibilityService"
        const val SHORT_COMPONENT = "fuck.andes/.agent.accessibility.AgentAccessibilityService"
    }
}
