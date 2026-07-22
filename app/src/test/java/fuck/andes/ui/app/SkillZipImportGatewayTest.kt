package fuck.andes.ui.app

import fuck.andes.agent.skill.InstalledSkill
import fuck.andes.agent.skill.SkillInstallConflict
import fuck.andes.agent.skill.SkillInstallErrorCode
import fuck.andes.agent.skill.SkillInstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillZipImportGatewayTest {

    @Test
    fun `success keeps display metadata only`() {
        val outcome = SkillInstallResult.Success(
            installed = listOf(
                InstalledSkill(
                    id = "example",
                    name = "Example",
                    description = "description",
                ),
            ),
        ).toZipImportOutcome()

        assertTrue(outcome is SkillZipImportOutcome.Success)
        val success = outcome as SkillZipImportOutcome.Success
        assertEquals(
            listOf(SkillZipImportOutcome.InstalledSkill(id = "example", name = "Example")),
            success.skills,
        )
    }

    @Test
    fun `conflict preserves replacement policy`() {
        val outcome = SkillInstallResult.Conflict(
            conflicts = listOf(
                SkillInstallConflict(
                    id = "builtin",
                    name = "Builtin",
                    existingSource = "builtin",
                    replaceAllowed = false,
                ),
            ),
            archiveSha256 = "a".repeat(64),
        ).toZipImportOutcome()

        assertTrue(outcome is SkillZipImportOutcome.Conflict)
        val conflict = (outcome as SkillZipImportOutcome.Conflict).skills.single()
        assertEquals("builtin", conflict.source)
        assertFalse(conflict.replaceAllowed)
        assertEquals("a".repeat(64), outcome.archiveSha256)
    }

    @Test
    fun `core errors map to bounded user facing categories`() {
        assertEquals(
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED,
            SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE.toUiFailureCode(),
        )
        assertEquals(
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE,
            SkillInstallErrorCode.UNSAFE_ENTRY_PATH.toUiFailureCode(),
        )
        assertEquals(
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE,
            SkillInstallErrorCode.TARGET_NOT_REPLACEABLE.toUiFailureCode(),
        )
        assertEquals(
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED,
            SkillInstallErrorCode.INVALID_SELECTION.toUiFailureCode(),
        )
    }

    @Test
    fun `incomplete rollback maps to recovery required`() {
        val outcome = SkillInstallResult.Failure(
            error = fuck.andes.agent.skill.SkillInstallError(
                code = SkillInstallErrorCode.COMMIT_FAILED,
                message = "rollback incomplete",
            ),
            recoveryRequired = true,
        ).toZipImportOutcome()

        assertEquals(
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED,
            (outcome as SkillZipImportOutcome.Failure).code,
        )
    }
}
