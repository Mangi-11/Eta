package fuck.andes.agent.skill

import fuck.andes.data.db.FuckAndesDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
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
class SkillPackageInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        FuckAndesDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("fuck_andes.db")
    }

    @Test
    fun localZipInstallsWrappedSkillAndRegistersEnabledUserSource() {
        val fixture = fixture("local-success")
        fixture.service.listSkillsForManagement()
        val archive = zip(
            "bundle/SKILL.md" to skill("zip-demo", "Install from a local ZIP."),
            "bundle/references/nested/guide.md" to "guide".encodeToByteArray(),
            "bundle/scripts/run.sh" to "#!/bin/sh\n".encodeToByteArray(),
        )

        val result = fixture.installer.installLocalZip({ archive.inputStream() })

        val success = result as SkillInstallResult.Success
        assertEquals(listOf("zip-demo"), success.installed.map { it.id })
        assertEquals(
            "guide",
            File(fixture.skillsRoot, "zip-demo/references/nested/guide.md").readText(),
        )
        val indexed = fixture.service.listSkillsForManagement(forceRefresh = true)
            .single { it.id == "zip-demo" }
        assertEquals(USER_SKILL_SOURCE, indexed.source)
        assertTrue(indexed.enabled)
        assertTrue(indexed.hasScripts)
        assertTrue(indexed.hasReferences)
    }

    @Test
    fun localZipRejectsMultipleSkillsWithoutChangingExistingSkill() {
        val fixture = fixture("local-multiple")
        val existing = File(fixture.skillsRoot, "stable/SKILL.md")
        existing.parentFile?.mkdirs()
        existing.writeBytes(skill("stable", "Keep the installed version."))
        val archive = zip(
            "one/SKILL.md" to skill("one", "First skill."),
            "two/SKILL.md" to skill("two", "Second skill."),
        )

        val result = fixture.installer.installLocalZip({ archive.inputStream() })

        assertFailureCode(result, SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND)
        assertTrue(existing.readText().contains("Keep the installed version"))
        assertFalse(File(fixture.skillsRoot, "one").exists())
        assertFalse(File(fixture.skillsRoot, "two").exists())
    }

    @Test
    fun repositoryInspectionAndExplicitBatchSelectionUseRepositoryRelativePaths() {
        val fixture = fixture("repository-batch")
        val archive = zip(
            "repo-main/README.md" to "repository".encodeToByteArray(),
            "repo-main/skills/alpha/SKILL.md" to skill("alpha", "Alpha skill."),
            "repo-main/skills/beta/SKILL.md" to skill("beta", "Beta skill."),
            "repo-main/skills/beta/assets/prompt.txt" to "prompt".encodeToByteArray(),
        )

        val inspection = fixture.installer.inspectRepositoryZip(
            openStream = { archive.inputStream() },
        )

        val candidates = (inspection as SkillArchiveInspectionResult.Success).candidates
        assertEquals(listOf("skills/alpha", "skills/beta"), candidates.map { it.relativePath })

        val result = fixture.installer.installRepositoryZip(
            openStream = { archive.inputStream() },
            selectedPaths = candidates.map { it.relativePath },
        )
        val success = result as SkillInstallResult.Success
        assertEquals(listOf("alpha", "beta"), success.installed.map { it.id })
        assertEquals(
            "prompt",
            File(fixture.skillsRoot, "beta/assets/prompt.txt").readText(),
        )
    }

    @Test
    fun repositorySelectionRejectsSkillContainingNestedSkill() {
        val fixture = fixture("nested-selection")
        val archive = zip(
            "repo-main/skills/parent/SKILL.md" to skill("parent", "Parent skill."),
            "repo-main/skills/parent/child/SKILL.md" to skill("child", "Nested skill."),
        )

        val result = fixture.installer.installRepositoryZip(
            openStream = { archive.inputStream() },
            selectedPaths = listOf("skills/parent"),
        )

        assertFailureCode(result, SkillInstallErrorCode.INVALID_SELECTION)
        assertFalse(File(fixture.skillsRoot, "parent").exists())
        assertFalse(File(fixture.skillsRoot, "child").exists())
    }

    @Test
    fun invalidSkillNameIsRejectedInsteadOfSanitized() {
        val fixture = fixture("invalid-name")
        val archive = zip(
            "SKILL.md" to skill("Invalid Name", "Must not be silently renamed."),
        )

        val result = fixture.installer.installLocalZip({ archive.inputStream() })

        assertFailureCode(result, SkillInstallErrorCode.INVALID_SKILL)
        assertFalse(File(fixture.skillsRoot, "invalid-name").exists())
    }

    @Test
    fun quotedFrontmatterScalarsRemainCompatibleWithYamlSkills() {
        val fixture = fixture("quoted-frontmatter")
        val archive = zip(
            "SKILL.md" to """
                ---
                name: 'quoted-skill'
                description: "Quoted YAML metadata."
                ---

                # Quoted skill
            """.trimIndent().encodeToByteArray(),
        )

        val result = fixture.installer.installLocalZip(
            openStream = { archive.inputStream() },
        )

        val success = result as SkillInstallResult.Success
        assertEquals("quoted-skill", success.installed.single().id)
        assertEquals("Quoted YAML metadata.", success.installed.single().description)
    }

    @Test
    fun foldedFrontmatterDescriptionUsedByCodexSkillsIsParsed() {
        val fixture = fixture("folded-frontmatter")
        val archive = zip(
            "SKILL.md" to """
                ---
                name: folded-skill
                description: >-
                  Install and maintain a Codex-compatible Skill
                  from a trusted package.
                ---

                # Folded skill
            """.trimIndent().encodeToByteArray(),
        )

        val result = fixture.installer.installLocalZip(
            openStream = { archive.inputStream() },
        )

        val success = result as SkillInstallResult.Success
        assertEquals(
            "Install and maintain a Codex-compatible Skill from a trusted package.",
            success.installed.single().description,
        )
    }

    @Test
    fun missingOrOversizedDescriptionIsRejected() {
        val fixture = fixture("invalid-description")
        val missing = zip(
            "SKILL.md" to "---\nname: missing-description\n---\n".encodeToByteArray(),
        )
        val oversized = zip(
            "SKILL.md" to skill("long-description", "x".repeat(1_025)),
        )

        assertFailureCode(
            fixture.installer.installLocalZip({ missing.inputStream() }),
            SkillInstallErrorCode.INVALID_SKILL,
        )
        assertFailureCode(
            fixture.installer.installLocalZip({ oversized.inputStream() }),
            SkillInstallErrorCode.INVALID_SKILL,
        )
    }

    @Test
    fun unsafeZipPathsAndCaseInsensitiveDuplicatesAreRejected() {
        listOf("../escape.txt", "/absolute.txt", "C:/drive.txt", "folder\\file.txt").forEachIndexed {
                index,
                unsafePath,
            ->
            val fixture = fixture("unsafe-$index")
            val archive = zip(
                "SKILL.md" to skill("safe-$index", "Path validation fixture."),
                unsafePath to "bad".encodeToByteArray(),
            )
            assertFailureCode(
                fixture.installer.installLocalZip({ archive.inputStream() }),
                SkillInstallErrorCode.UNSAFE_ENTRY_PATH,
            )
        }

        val duplicateFixture = fixture("duplicate")
        val duplicateArchive = zip(
            "SKILL.md" to skill("duplicate-check", "Duplicate path fixture."),
            "references/Guide.md" to "one".encodeToByteArray(),
            "references/guide.md" to "two".encodeToByteArray(),
        )
        assertFailureCode(
            duplicateFixture.installer.installLocalZip({ duplicateArchive.inputStream() }),
            SkillInstallErrorCode.DUPLICATE_ENTRY,
        )
    }

    @Test
    fun archiveEntryAndExtractionQuotasAreEnforced() {
        val archive = zip(
            "SKILL.md" to skill("quota-check", "Quota fixture."),
            "references/a.txt" to "123456".encodeToByteArray(),
            "references/b.txt" to "123456".encodeToByteArray(),
        )

        val archiveLimited = fixture(
            "archive-limit",
            SkillPackageLimits(maxArchiveBytes = 16),
        )
        assertFailureCode(
            archiveLimited.installer.installLocalZip({ archive.inputStream() }),
            SkillInstallErrorCode.ARCHIVE_TOO_LARGE,
        )

        val entryLimited = fixture(
            "entry-limit",
            SkillPackageLimits(maxSingleFileBytes = 16),
        )
        assertFailureCode(
            entryLimited.installer.installLocalZip({ archive.inputStream() }),
            SkillInstallErrorCode.ENTRY_TOO_LARGE,
        )

        val totalLimited = fixture(
            "total-limit",
            SkillPackageLimits(maxExtractedBytes = 40),
        )
        assertFailureCode(
            totalLimited.installer.installLocalZip({ archive.inputStream() }),
            SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE,
        )

        val countLimited = fixture(
            "count-limit",
            SkillPackageLimits(maxEntries = 2),
        )
        assertFailureCode(
            countLimited.installer.installLocalZip({ archive.inputStream() }),
            SkillInstallErrorCode.TOO_MANY_ENTRIES,
        )

        val depthLimited = fixture(
            "depth-limit",
            SkillPackageLimits(maxPathDepth = 1),
        )
        assertFailureCode(
            depthLimited.installer.installLocalZip({ archive.inputStream() }),
            SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
        )
    }

    @Test
    fun userConflictRequiresReplaceAndBuiltinCanNeverBeOverwritten() {
        val fixture = fixture("conflicts")
        val first = zip("SKILL.md" to skill("replace-me", "Old version."))
        assertTrue(
            fixture.installer.installLocalZip({ first.inputStream() }) is SkillInstallResult.Success
        )
        val replacement = zip("SKILL.md" to skill("replace-me", "New version."))

        val conflict = fixture.installer.installLocalZip({ replacement.inputStream() })

        val conflictResult = conflict as SkillInstallResult.Conflict
        val userConflict = conflictResult.conflicts.single()
        assertEquals("replace-me", userConflict.id)
        assertEquals(USER_SKILL_SOURCE, userConflict.existingSource)
        assertTrue(userConflict.replaceAllowed)
        assertTrue(File(fixture.skillsRoot, "replace-me/SKILL.md").readText().contains("Old version"))

        val replaced = fixture.installer.installLocalZip(
            openStream = { replacement.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "replace-me",
            expectedArchiveSha256 = conflictResult.archiveSha256,
        )
        assertTrue(replaced is SkillInstallResult.Success)
        assertTrue(File(fixture.skillsRoot, "replace-me/SKILL.md").readText().contains("New version"))

        val builtinArchive = zip(
            "SKILL.md" to skill("self-improving-agent", "Attempt to replace a builtin."),
        )
        val builtinAwareInstaller = SkillPackageInstaller(
            skillsRoot = fixture.skillsRoot,
            indexService = fixture.service,
            builtinIdLookup = { it == "self-improving-agent" },
        )
        val initialBuiltinConflict = builtinAwareInstaller.installLocalZip(
            openStream = { builtinArchive.inputStream() },
        ) as SkillInstallResult.Conflict
        val builtinConflict = builtinAwareInstaller.installLocalZip(
            openStream = { builtinArchive.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "self-improving-agent",
            expectedArchiveSha256 = initialBuiltinConflict.archiveSha256,
        ) as SkillInstallResult.Conflict
        assertFalse(builtinConflict.conflicts.single().replaceAllowed)
        assertEquals(BUILTIN_SKILL_SOURCE, builtinConflict.conflicts.single().existingSource)
    }

    @Test
    fun replacementRejectsInstalledTreeContainingNestedSymlink() {
        val fixture = fixture("replacement-nested-link")
        val initial = zip("SKILL.md" to skill("linked-replacement", "Keep linked version."))
        assertTrue(
            fixture.installer.installLocalZip({ initial.inputStream() }) is SkillInstallResult.Success
        )
        val external = temporaryFolder.newFolder("replacement-link-external")
        val marker = File(external, "keep.txt").also { it.writeText("outside") }
        try {
            Files.createSymbolicLink(
                File(fixture.skillsRoot, "linked-replacement/references-link").toPath(),
                external.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val replacement = zip(
            "SKILL.md" to skill("linked-replacement", "Must not replace unsafe tree."),
        )

        val conflict = fixture.installer.installLocalZip(
            openStream = { replacement.inputStream() },
        ) as SkillInstallResult.Conflict

        assertFalse(conflict.conflicts.single().replaceAllowed)
        val rejected = fixture.installer.installLocalZip(
            openStream = { replacement.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "linked-replacement",
            expectedArchiveSha256 = conflict.archiveSha256,
        ) as SkillInstallResult.Conflict
        assertFalse(rejected.conflicts.single().replaceAllowed)
        assertTrue(
            File(fixture.skillsRoot, "linked-replacement/SKILL.md")
                .readText()
                .contains("Keep linked version")
        )
        assertEquals("outside", marker.readText())
    }

    @Test
    fun symlinkedWorkRootFailsBeforeOpeningArchiveOrCreatingExternalFiles() {
        val parent = temporaryFolder.newFolder("symlinked-work-root-parent")
        val skillsRoot = File(parent, "skills").also { assertTrue(it.mkdir()) }
        val external = temporaryFolder.newFolder("symlinked-work-root-external")
        try {
            Files.createSymbolicLink(
                File(parent, ".eta-skill-installer").toPath(),
                external.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        val installer = SkillPackageInstaller(skillsRoot, service)
        var streamOpened = false

        val result = installer.installLocalZip(
            openStream = {
                streamOpened = true
                ByteArrayInputStream(zip("SKILL.md" to skill("never-opened", "No external write.")))
            },
        )

        assertFailureCode(result, SkillInstallErrorCode.IO_ERROR)
        assertFalse(streamOpened)
        assertTrue(external.listFiles().orEmpty().isEmpty())
        try {
            service.listSkillsForManagement(forceRefresh = true)
            fail("Expected unsafe lock directory to fail closed")
        } catch (_: IOException) {
            // 预期在创建 install.lock 之前拒绝 symlink 工作目录。
        }
        assertTrue(external.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedBatchCommitRestoresEveryExistingSkill() {
        val fixture = fixture("rollback")
        val initial = zip(
            "repo-main/alpha/SKILL.md" to skill("alpha", "Old alpha."),
            "repo-main/beta/SKILL.md" to skill("beta", "Old beta."),
        )
        assertTrue(
            fixture.installer.installRepositoryZip(
                openStream = { initial.inputStream() },
                selectedPaths = listOf("alpha", "beta"),
            ) is SkillInstallResult.Success
        )

        var moveCount = 0
        val failingMover = SkillDirectoryMover { source, target ->
            moveCount += 1
            if (moveCount == 4) throw IOException("injected commit failure")
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath())
        }
        val failingInstaller = SkillPackageInstaller(
            skillsRoot = fixture.skillsRoot,
            indexService = fixture.service,
            directoryMover = failingMover,
        )
        val replacement = zip(
            "repo-main/alpha/SKILL.md" to skill("alpha", "New alpha."),
            "repo-main/beta/SKILL.md" to skill("beta", "New beta."),
        )

        val result = failingInstaller.installRepositoryZip(
            openStream = { replacement.inputStream() },
            selectedPaths = listOf("alpha", "beta"),
            replaceUserSkills = true,
            expectedReplacementIds = setOf("alpha", "beta"),
        )

        assertFailureCode(result, SkillInstallErrorCode.COMMIT_FAILED)
        assertFalse((result as SkillInstallResult.Failure).recoveryRequired)
        assertTrue(File(fixture.skillsRoot, "alpha/SKILL.md").readText().contains("Old alpha"))
        assertTrue(File(fixture.skillsRoot, "beta/SKILL.md").readText().contains("Old beta"))
        assertFalse(File(fixture.skillsRoot, "alpha/SKILL.md").readText().contains("New alpha"))
    }

    @Test
    fun incompleteRollbackPreservesOldSkillBackupOutsideIndexRoot() {
        val fixture = fixture("incomplete-rollback")
        val initial = zip(
            "repo-main/alpha/SKILL.md" to skill("alpha", "Recoverable old alpha."),
            "repo-main/beta/SKILL.md" to skill("beta", "Recoverable old beta."),
        )
        assertTrue(
            fixture.installer.installRepositoryZip(
                openStream = { initial.inputStream() },
                selectedPaths = listOf("alpha", "beta"),
            ) is SkillInstallResult.Success
        )
        var moveCount = 0
        val failingMover = SkillDirectoryMover { source, target ->
            moveCount += 1
            if (moveCount == 4 || moveCount == 6) {
                throw IOException("injected commit/restore failure")
            }
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath())
        }
        val installer = SkillPackageInstaller(
            skillsRoot = fixture.skillsRoot,
            indexService = fixture.service,
            directoryMover = failingMover,
        )
        val replacement = zip(
            "repo-main/alpha/SKILL.md" to skill("alpha", "New alpha."),
            "repo-main/beta/SKILL.md" to skill("beta", "New beta."),
        )

        val result = installer.installRepositoryZip(
            openStream = { replacement.inputStream() },
            selectedPaths = listOf("alpha", "beta"),
            replaceUserSkills = true,
            expectedReplacementIds = setOf("alpha", "beta"),
        )

        assertFailureCode(result, SkillInstallErrorCode.COMMIT_FAILED)
        assertTrue((result as SkillInstallResult.Failure).recoveryRequired)
        assertTrue(File(fixture.skillsRoot, "alpha/SKILL.md").readText().contains("Recoverable old alpha"))
        val recoveryRoot = File(fixture.skillsRoot.parentFile, ".eta-skill-installer")
        val recoveryOperation = recoveryRoot.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("operation-") }
            .single { File(it, JOURNAL_FILE_NAME).isFile }
        val retainedBackup = File(recoveryOperation, "backup/beta/SKILL.md")
        assertTrue(retainedBackup.isFile)
        assertTrue(retainedBackup.readText().contains("Recoverable old beta"))
        assertFalse(File(fixture.skillsRoot, "beta").exists())
    }

    @Test
    fun cancellationStopsBeforeReadingArchive() {
        val fixture = fixture("cancel-before-read")
        var streamOpened = false

        val result = fixture.installer.installLocalZip(
            openStream = {
                streamOpened = true
                ByteArrayInputStream(zip("SKILL.md" to skill("cancelled", "Cancelled skill.")))
            },
            isCancelled = { true },
        )

        assertFailureCode(result, SkillInstallErrorCode.CANCELLED)
        assertFalse(streamOpened)
        assertFalse(File(fixture.skillsRoot, "cancelled").exists())
    }

    @Test
    fun cancellationIsCheckedAgainImmediatelyBeforeCommit() {
        val fixture = fixture("cancel-before-commit")
        var cancelled = false
        val installer = SkillPackageInstaller(
            skillsRoot = fixture.skillsRoot,
            indexService = fixture.service,
            builtinIdLookup = {
                cancelled = true
                false
            },
        )
        val archive = zip("SKILL.md" to skill("late-cancel", "Cancel before commit."))

        val result = installer.installLocalZip(
            openStream = { archive.inputStream() },
            isCancelled = { cancelled },
        )

        assertFailureCode(result, SkillInstallErrorCode.CANCELLED)
        assertFalse(File(fixture.skillsRoot, "late-cancel").exists())
        assertTrue(
            fixture.service.listSkillsForManagement(forceRefresh = true).none { it.id == "late-cancel" }
        )
    }

    @Test
    fun localReplacementRejectsChangedUriContentAfterConfirmation() {
        val fixture = fixture("replacement-identity")
        val original = zip("SKILL.md" to skill("confirmed-skill", "Confirmed old version."))
        assertTrue(
            fixture.installer.installLocalZip({ original.inputStream() }) is SkillInstallResult.Success
        )
        val changedContent = zip("SKILL.md" to skill("different-skill", "Different content."))

        val result = fixture.installer.installLocalZip(
            openStream = { changedContent.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "confirmed-skill",
        )

        assertFailureCode(result, SkillInstallErrorCode.INVALID_SELECTION)
        assertTrue(
            File(fixture.skillsRoot, "confirmed-skill/SKILL.md")
                .readText()
                .contains("Confirmed old version")
        )
        assertFalse(File(fixture.skillsRoot, "different-skill").exists())
    }

    @Test
    fun localReplacementBindsConfirmationToCoreMaterializedArchiveDigest() {
        val fixture = fixture("replacement-digest")
        val original = zip("SKILL.md" to skill("digest-skill", "Installed old version."))
        assertTrue(
            fixture.installer.installLocalZip({ original.inputStream() }) is SkillInstallResult.Success
        )
        val reviewedArchive = zip(
            "SKILL.md" to skill("digest-skill", "Reviewed replacement bytes."),
        )
        val conflict = fixture.installer.installLocalZip(
            openStream = { reviewedArchive.inputStream() },
        ) as SkillInstallResult.Conflict
        val reviewedDigest = requireNotNull(conflict.archiveSha256)
        assertEquals(sha256(reviewedArchive), reviewedDigest)
        val changedArchive = zip(
            "SKILL.md" to skill("digest-skill", "Changed after confirmation."),
        )

        val rejected = fixture.installer.installLocalZip(
            openStream = { changedArchive.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "digest-skill",
            expectedArchiveSha256 = reviewedDigest,
        )

        assertFailureCode(rejected, SkillInstallErrorCode.INVALID_SELECTION)
        assertTrue(
            File(fixture.skillsRoot, "digest-skill/SKILL.md")
                .readText()
                .contains("Installed old version")
        )

        val installed = fixture.installer.installLocalZip(
            openStream = { reviewedArchive.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "digest-skill",
            expectedArchiveSha256 = reviewedDigest,
        )
        assertTrue(installed is SkillInstallResult.Success)
        assertTrue(
            File(fixture.skillsRoot, "digest-skill/SKILL.md")
                .readText()
                .contains("Reviewed replacement bytes")
        )
    }

    @Test
    fun localReplacementRequiresValidLowercaseArchiveDigest() {
        val fixture = fixture("replacement-invalid-digest")
        val original = zip("SKILL.md" to skill("digest-required", "Keep this version."))
        assertTrue(
            fixture.installer.installLocalZip({ original.inputStream() }) is SkillInstallResult.Success
        )
        val replacement = zip(
            "SKILL.md" to skill("digest-required", "Replacement version."),
        )
        val digest = requireNotNull(
            (fixture.installer.installLocalZip(openStream = { replacement.inputStream() }) as
                SkillInstallResult.Conflict).archiveSha256
        )

        val missing = fixture.installer.installLocalZip(
            openStream = { replacement.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "digest-required",
        )
        val invalid = fixture.installer.installLocalZip(
            openStream = { replacement.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "digest-required",
            expectedArchiveSha256 = "not-a-sha256",
        )
        val uppercase = fixture.installer.installLocalZip(
            openStream = { replacement.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "digest-required",
            expectedArchiveSha256 = digest.uppercase(),
        )

        assertFailureCode(missing, SkillInstallErrorCode.INVALID_SELECTION)
        assertFailureCode(invalid, SkillInstallErrorCode.INVALID_SELECTION)
        assertFailureCode(uppercase, SkillInstallErrorCode.INVALID_SELECTION)
        assertTrue(
            File(fixture.skillsRoot, "digest-required/SKILL.md")
                .readText()
                .contains("Keep this version")
        )
    }

    @Test
    fun repositoryReplacementRejectsChangedSkillIdentityAtConfirmedPath() {
        val fixture = fixture("repository-replacement-identity")
        val initial = zip(
            "repo-main/skills/target/SKILL.md" to skill("confirmed-repo-skill", "Old repository version."),
        )
        assertTrue(
            fixture.installer.installRepositoryZip(
                openStream = { initial.inputStream() },
                selectedPaths = listOf("skills/target"),
            ) is SkillInstallResult.Success
        )
        val changedArchive = zip(
            "repo-main/skills/target/SKILL.md" to skill("different-repo-skill", "Changed identity."),
        )

        val result = fixture.installer.installRepositoryZip(
            openStream = { changedArchive.inputStream() },
            selectedPaths = listOf("skills/target"),
            replaceUserSkills = true,
            expectedReplacementIds = setOf("confirmed-repo-skill"),
        )

        assertFailureCode(result, SkillInstallErrorCode.INVALID_SELECTION)
        assertTrue(
            File(fixture.skillsRoot, "confirmed-repo-skill/SKILL.md")
                .readText()
                .contains("Old repository version")
        )
        assertFalse(File(fixture.skillsRoot, "different-repo-skill").exists())
    }

    private fun fixture(
        name: String,
        limits: SkillPackageLimits = SkillPackageLimits(),
    ): Fixture {
        val root = temporaryFolder.newFolder(name, "skills")
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), root)
        return Fixture(
            skillsRoot = root,
            service = service,
            installer = SkillPackageInstaller(root, service, limits),
        )
    }

    private fun assertFailureCode(result: SkillInstallResult, code: SkillInstallErrorCode) {
        val failure = result as SkillInstallResult.Failure
        assertEquals(code, failure.error.code)
    }

    private data class Fixture(
        val skillsRoot: File,
        val service: SkillIndexService,
        val installer: SkillPackageInstaller,
    )

    private fun skill(name: String, description: String): ByteArray =
        """
        ---
        name: $name
        description: $description
        ---

        # $name
        """.trimIndent().encodeToByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
