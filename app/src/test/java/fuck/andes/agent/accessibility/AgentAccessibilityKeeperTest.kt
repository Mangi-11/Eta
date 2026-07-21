package fuck.andes.agent.accessibility

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
    fun `disabled state stays disabled without mutating settings`() {
        assertFalse(
            AgentAccessibilityKeeper.isServiceEnabled(
                enabledComponents = emptyList(),
                target = target,
            ),
        )
    }
}
