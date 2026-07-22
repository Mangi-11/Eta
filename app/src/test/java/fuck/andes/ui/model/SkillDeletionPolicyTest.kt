package fuck.andes.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDeletionPolicyTest {
    @Test
    fun onlyInstalledUserSkillsCanBeDeleted() {
        assertTrue(skill(source = "user", installed = true).canDeleteUserSkill)
        assertFalse(skill(source = "builtin", installed = true).canDeleteUserSkill)
        assertFalse(skill(source = "user", installed = false).canDeleteUserSkill)
        assertFalse(skill(source = "unknown", installed = true).canDeleteUserSkill)
    }

    private fun skill(source: String, installed: Boolean) = SkillItemUi(
        id = "test-skill",
        name = "Test Skill",
        description = "Test deletion policy.",
        source = source,
        enabled = true,
        installed = installed,
        capabilities = emptyList(),
    )
}
