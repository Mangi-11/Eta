package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
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
class SkillAuthoringServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var root: File
    private lateinit var index: SkillIndexService
    private lateinit var authoring: SkillAuthoringService

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
        root = temporaryFolder.newFolder("skills")
        index = SkillIndexService(RuntimeEnvironment.getApplication(), root)
        authoring = SkillAuthoringService(index, SkillPackageInstaller(root, index))
    }

    @Test
    fun createdSkillIsIndexedAndCanBeLoadedNextRun() {
        val result = authoring.manage(args())
        assertTrue(result.getBoolean("ok"))
        val entry = index.findInstalledSkill("notes-workflow")!!
        val loaded = SkillLoader(root).load(entry, "test")!!
        assertEquals(result.getString("revision"), loaded.revision)
        assertEquals("# Steps\nObserve the Notes button before tapping.", loaded.bodyMarkdown)
        assertEquals(USER_SKILL_SOURCE, entry.source)
    }

    @Test
    fun updateChecksOldRevisionAndPreservesResources() {
        val created = authoring.manage(args())
        File(root, "notes-workflow/references").mkdirs()
        File(root, "notes-workflow/references/guide.md").writeText("reference")
        val update = args("update").put("expectedRevision", created.getString("revision"))
            .put("bodyMarkdown", "# Steps\nWait for the list, then observe.")
        val result = authoring.manage(update)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("reference", File(root, "notes-workflow/references/guide.md").readText())
        assertEquals("SKILL_REVISION_CONFLICT", authoring.manage(update).getString("code"))
        assertTrue(File(root, "notes-workflow/SKILL.md").readText().contains("Wait for the list"))
    }

    @Test
    fun createCannotOverwriteExistingOrDisabledSkill() {
        val result = authoring.manage(args())
        assertEquals("SKILL_EXISTS", authoring.manage(args()).getString("code"))
        index.setSkillEnabled("notes-workflow", false)
        assertEquals(
            "SKILL_NOT_WRITABLE",
            authoring.manage(args("update").put("expectedRevision", result.getString("revision")))
                .getString("code")
        )
        assertFalse(index.listSkillsForManagement().single { it.id == "notes-workflow" }.enabled)
    }

    @Test
    fun rejectsTraversalOversizedNameAndArbitraryFilePath() {
        for (id in listOf("../escape", "a".repeat(65), "a--b", "", "/tmp/write")) {
            assertFalse(authoring.manage(args().put("skillId", id)).getBoolean("ok"))
        }
        assertFalse(authoring.manage(args().put("path", "/tmp/write")).getBoolean("ok"))
        assertFalse(File(root, "notes-workflow").exists())
    }

    @Test
    fun updateRefusesLinkedResourcesAndKeepsBothTargetsIntact() {
        val created = authoring.manage(args())
        val outside = temporaryFolder.newFile("outside.txt").apply { writeText("private") }
        Files.createSymbolicLink(File(root, "notes-workflow/link.txt").toPath(), outside.toPath())
        val result =
            authoring.manage(args("update").put("expectedRevision", created.getString("revision")))
        assertFalse(result.getBoolean("ok"))
        assertEquals("private", outside.readText())
        assertEquals(
            created.getString("revision"),
            skillRevision(File(root, "notes-workflow/SKILL.md").readBytes())
        )
    }

    @Test
    fun cancelledAuthoringDoesNotInstallFilesOrRegistryEntry() {
        assertFalse(authoring.manage(args()) { true }.getBoolean("ok"))
        assertNull(index.findInstalledSkill("notes-workflow"))
        assertFalse(File(root, "notes-workflow").exists())
    }

    @Test
    fun authoringPreservesCompatibilityAndMetadataWhenUpdatingUserSkill() {
        authoring.manage(args())
        val file = File(root, "notes-workflow/SKILL.md")
        file.writeText(
            file.readText().replace(
                "compatibility: Android",
                "compatibility: Android 16\nmetadata:\n  tested-version: 6.0"
            )
        )
        val result = authoring.manage(
            args("update").put(
                "expectedRevision",
                skillRevision(file.readBytes())
            )
        )
        assertTrue(result.toString(), result.getBoolean("ok"))
        val entry = index.findInstalledSkill("notes-workflow")!!
        assertEquals("Android 16", entry.compatibility)
        assertEquals("6.0", entry.metadata["tested-version"])
    }

    private fun args(action: String = "create") = JSONObject().put("action", action)
        .put("skillId", "notes-workflow").put("description", "Use when operating Android notes.")
        .put("bodyMarkdown", "# Steps\nObserve the Notes button before tapping.")
}
