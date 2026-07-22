package fuck.andes.agent.skill

import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.data.db.SkillRegistryEntity
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkillRecoveryJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("fuck_andes.db")
    }

    @Test
    fun recoversWhenProcessCrashesAfterBackupMoveBeforeJournalUpdate() {
        val skillsRoot = temporaryFolder.newFolder("backup-crash-skills")
        val target = writeSkill(skillsRoot, "recover-me", "Old content survives.")
        val operation = newOperation(skillsRoot)
        val backup = File(operation, "backup/recover-me")
        backup.parentFile?.mkdirs()
        PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(SkillRecoveryRecord("recover-me", originalTargetExisted = true)),
        )
        moveSkillDirectoryAtomically(target, backup)

        val entries = service(skillsRoot).listSkillsForManagement(forceRefresh = true)

        val restored = entries.single { it.id == "recover-me" }
        assertEquals("Old content survives.", restored.description)
        assertTrue(File(skillsRoot, "recover-me/SKILL.md").isFile)
        assertFalse(operation.exists())
    }

    @Test
    fun recoversOldSkillsAfterPartialNewDirectoryCommitAndDoesNotIndexNewContent() {
        val skillsRoot = temporaryFolder.newFolder("partial-commit-skills")
        val alpha = writeSkill(skillsRoot, "alpha", "Old alpha.")
        val beta = writeSkill(skillsRoot, "beta", "Old beta.")
        val operation = newOperation(skillsRoot)
        val backupRoot = File(operation, "backup").also { it.mkdirs() }
        val journal = PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(
                SkillRecoveryRecord("alpha", originalTargetExisted = true),
                SkillRecoveryRecord("beta", originalTargetExisted = true),
            ),
        )
        moveSkillDirectoryAtomically(alpha, File(backupRoot, "alpha"))
        journal.markBackupCompleted("alpha")
        moveSkillDirectoryAtomically(beta, File(backupRoot, "beta"))
        journal.markBackupCompleted("beta")
        writeSkill(skillsRoot, "alpha", "Uncommitted new alpha.")
        journal.markNewTargetCommitted("alpha")

        val entries = service(skillsRoot).listSkillsForManagement(forceRefresh = true)

        assertEquals("Old alpha.", entries.single { it.id == "alpha" }.description)
        assertEquals("Old beta.", entries.single { it.id == "beta" }.description)
        assertTrue(File(skillsRoot, "alpha/SKILL.md").readText().contains("Old alpha"))
        assertFalse(File(skillsRoot, "alpha/SKILL.md").readText().contains("Uncommitted"))
        assertFalse(operation.exists())
    }

    @Test
    fun removesNewSkillCommittedBeforeProcessCrash() {
        val skillsRoot = temporaryFolder.newFolder("new-install-crash-skills")
        val operation = newOperation(skillsRoot)
        val journal = PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(SkillRecoveryRecord("new-skill", originalTargetExisted = false)),
        )
        writeSkill(skillsRoot, "new-skill", "Must be rolled back.")
        journal.markNewTargetCommitted("new-skill")

        val entries = service(skillsRoot).listSkillsForManagement(forceRefresh = true)

        assertFalse(entries.any { it.id == "new-skill" })
        assertFalse(File(skillsRoot, "new-skill").exists())
        assertFalse(operation.exists())
    }

    @Test
    fun malformedJournalFailsClosedBeforeIndexOrLoaderCanReadPartialSkill() {
        val skillsRoot = temporaryFolder.newFolder("malformed-journal-skills")
        val partial = writeSkill(skillsRoot, "partial-skill", "Must never be loaded.")
        val operation = newOperation(skillsRoot)
        File(operation, JOURNAL_FILE_NAME).writeText("{not-json")
        val entry = entryFor(partial)

        assertRecoveryRequired {
            service(skillsRoot).listSkillsForManagement(forceRefresh = true)
        }
        assertRecoveryRequired {
            SkillLoader(skillsRoot).load(entry, "test")
        }
        assertTrue(File(operation, JOURNAL_FILE_NAME).isFile)
        assertTrue(partial.isDirectory)
    }

    @Test
    fun missingRequiredBackupFailsClosedAndKeepsJournalForRetry() {
        val skillsRoot = temporaryFolder.newFolder("missing-backup-skills")
        val target = writeSkill(skillsRoot, "missing-backup", "Untrusted current content.")
        val operation = newOperation(skillsRoot)
        val journal = PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(SkillRecoveryRecord("missing-backup", originalTargetExisted = true)),
        )
        journal.markBackupCompleted("missing-backup")

        assertRecoveryRequired {
            service(skillsRoot).listSkillsForManagement(forceRefresh = true)
        }
        assertTrue(File(operation, JOURNAL_FILE_NAME).isFile)
        assertTrue(target.isDirectory)
    }

    @Test
    fun crashAfterRoomCommitRestoresOldFileAndDisabledRegistrySnapshot() {
        val skillsRoot = temporaryFolder.newFolder("room-commit-crash-skills")
        val target = writeSkill(skillsRoot, "disabled-skill", "Old disabled content.")
        val originalService = service(skillsRoot)
        originalService.listSkillsForManagement(forceRefresh = true)
        originalService.setSkillEnabled("disabled-skill", false)
        val snapshot = originalService
            .captureRegistryRecoverySnapshots(listOf("disabled-skill"))
            .single()
        assertFalse(snapshot.enabled)

        val operation = newOperation(skillsRoot)
        val backup = File(operation, "backup/disabled-skill")
        backup.parentFile?.mkdirs()
        val journal = PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(
                SkillRecoveryRecord(
                    id = "disabled-skill",
                    originalTargetExisted = true,
                    registrySnapshot = snapshot,
                )
            ),
        )
        moveSkillDirectoryAtomically(target, backup)
        journal.markBackupCompleted("disabled-skill")
        writeSkill(skillsRoot, "disabled-skill", "New committed content.")
        journal.markNewTargetCommitted("disabled-skill")
        upsertRegistry("disabled-skill", enabled = true)

        val recovered = service(skillsRoot).listSkillsForManagement(forceRefresh = true)
            .single { it.id == "disabled-skill" }

        assertEquals("Old disabled content.", recovered.description)
        assertFalse(recovered.enabled)
        assertFalse(registryEntry("disabled-skill")!!.enabled)
        assertFalse(operation.exists())
    }

    @Test
    fun deleteCrashRestoresDirectoryAndOldRegistryBeforeIndexing() {
        val skillsRoot = temporaryFolder.newFolder("delete-crash-skills")
        val target = writeSkill(skillsRoot, "delete-crash", "Restore deleted Skill.")
        val originalService = service(skillsRoot)
        originalService.listSkillsForManagement(forceRefresh = true)
        originalService.setSkillEnabled("delete-crash", true)
        val snapshot = originalService
            .captureRegistryRecoverySnapshots(listOf("delete-crash"))
            .single()
        val operation = newOperation(skillsRoot)
        val backup = File(operation, "backup/delete-crash")
        backup.parentFile?.mkdirs()
        val journal = PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(
                SkillRecoveryRecord(
                    id = "delete-crash",
                    originalTargetExisted = true,
                    registrySnapshot = snapshot,
                )
            ),
        )
        moveSkillDirectoryAtomically(target, backup)
        journal.markBackupCompleted("delete-crash")
        deleteRegistry("delete-crash")

        val recovered = service(skillsRoot).listSkillsForManagement(forceRefresh = true)
            .single { it.id == "delete-crash" }

        assertTrue(recovered.enabled)
        assertTrue(File(skillsRoot, "delete-crash/SKILL.md").isFile)
        assertEquals("user", registryEntry("delete-crash")?.source)
        assertFalse(operation.exists())
    }

    @Test
    fun roomFailureDuringDeleteRecoveryKeepsJournalAndRetriesBeforeIndexing() {
        val skillsRoot = temporaryFolder.newFolder("delete-room-failure-skills")
        val target = writeSkill(skillsRoot, "retry-delete", "Retry registry restore.")
        val originalService = service(skillsRoot)
        originalService.listSkillsForManagement(forceRefresh = true)
        originalService.setSkillEnabled("retry-delete", false)
        val snapshot = originalService
            .captureRegistryRecoverySnapshots(listOf("retry-delete"))
            .single()
        val operation = newOperation(skillsRoot)
        val backup = File(operation, "backup/retry-delete")
        backup.parentFile?.mkdirs()
        val journal = PendingSkillRecoveryJournal.begin(
            skillsRoot,
            operation,
            listOf(
                SkillRecoveryRecord(
                    id = "retry-delete",
                    originalTargetExisted = true,
                    registrySnapshot = snapshot,
                )
            ),
        )
        moveSkillDirectoryAtomically(target, backup)
        journal.markBackupCompleted("retry-delete")
        deleteRegistry("retry-delete")
        var protectedBlockRan = false

        assertRecoveryRequired {
            SkillMutationLock.withLock(
                skillsRoot = skillsRoot,
                recoveryHandler = { throw IOException("injected Room failure") },
            ) {
                protectedBlockRan = true
            }
        }

        assertFalse(protectedBlockRan)
        assertTrue(File(operation, JOURNAL_FILE_NAME).isFile)
        assertTrue(File(skillsRoot, "retry-delete/SKILL.md").isFile)
        assertEquals(null, registryEntry("retry-delete"))

        val recovered = service(skillsRoot).listSkillsForManagement(forceRefresh = true)
            .single { it.id == "retry-delete" }
        assertFalse(recovered.enabled)
        assertFalse(registryEntry("retry-delete")!!.enabled)
        assertFalse(operation.exists())
    }

    private fun service(skillsRoot: File): SkillIndexService = SkillIndexService(
        context = RuntimeEnvironment.getApplication(),
        skillsRoot = skillsRoot,
    )

    private fun newOperation(skillsRoot: File): File {
        val workRoot = skillInstallerWorkRoot(skillsRoot)
        assertTrue(workRoot.mkdirs() || workRoot.isDirectory)
        return File(workRoot, "operation-${UUID.randomUUID()}").also { assertTrue(it.mkdir()) }
    }

    private fun writeSkill(skillsRoot: File, id: String, description: String): File {
        val root = File(skillsRoot, id).also { it.mkdirs() }
        File(root, "SKILL.md").writeText(
            "---\nname: $id\ndescription: $description\n---\n\n# $id"
        )
        return root
    }

    private fun entryFor(root: File): SkillIndexEntry = SkillIndexEntry(
        id = root.name,
        name = root.name,
        description = "partial",
        rootPath = root.canonicalPath,
        skillFilePath = File(root, "SKILL.md").canonicalPath,
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
    )

    private fun upsertRegistry(skillId: String, enabled: Boolean) {
        runBlocking {
            FuckAndesDatabase.get(RuntimeEnvironment.getApplication())
                .skillDao()
                .upsertRegistryEntry(
                    SkillRegistryEntity(
                        skillId = skillId,
                        enabled = enabled,
                        source = USER_SKILL_SOURCE,
                        installState = "installed",
                    )
                )
        }
    }

    private fun deleteRegistry(skillId: String) {
        runBlocking {
            FuckAndesDatabase.get(RuntimeEnvironment.getApplication())
                .skillDao()
                .deleteRegistryEntry(skillId)
        }
    }

    private fun registryEntry(skillId: String): SkillRegistryEntity? = runBlocking {
        FuckAndesDatabase.get(RuntimeEnvironment.getApplication())
            .skillDao()
            .registryEntries()
            .firstOrNull { it.skillId == skillId }
    }

    private fun assertRecoveryRequired(block: () -> Unit) {
        try {
            block()
            fail("Expected SkillRecoveryRequiredException")
        } catch (_: SkillRecoveryRequiredException) {
            // 预期 fail-closed。
        }
    }
}
