package fuck.andes.agent.skill

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class SkillPackageLimits(
    val maxArchiveBytes: Long = 32L * 1024L * 1024L,
    val maxExtractedBytes: Long = 128L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
    val maxSkillFileBytes: Long = 512L * 1024L,
    val maxEntries: Int = 2_048,
    val maxPathDepth: Int = 16,
)

/**
 * Skill ZIP 安装器。
 *
 * 所有内容先落入 skills 目录外的私有暂存区，完成路径、配额和 Skill 元数据验证后才会
 * 移入正式目录。批量替换会先备份旧目录；任一步失败都会删除新目录并恢复备份。
 */
class SkillPackageInstaller internal constructor(
    skillsRoot: File,
    private val indexService: SkillIndexService,
    private val limits: SkillPackageLimits = SkillPackageLimits(),
    private val directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
    private val builtinIdLookup: (String) -> Boolean = indexService::isBuiltinSkillId,
) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val workRoot = File(
        requireNotNull(canonicalSkillsRoot.parentFile) { "Skills 目录必须有父目录" },
        ".eta-skill-installer",
    )

    fun installLocalZip(
        openStream: () -> InputStream,
        replaceUserSkill: Boolean = false,
        expectedReplacementId: String? = null,
        expectedArchiveSha256: String? = null,
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult = withArchive(openStream, isCancelled) { operation, extractedRoot ->
        val candidates = discoverCandidates(extractedRoot, extractedRoot, isCancelled)
        when {
            candidates.isEmpty() -> fail(
                SkillInstallErrorCode.NO_SKILL_FOUND,
                "ZIP 中没有找到 SKILL.md",
            )
            candidates.size > 1 -> fail(
                SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND,
                "本地 ZIP 必须只包含一个 Skill，当前发现 ${candidates.size} 个",
            )
        }
        if (replaceUserSkill) {
            val expectedId = expectedReplacementId?.trim().orEmpty()
            val expectedDigest = expectedArchiveSha256?.trim().orEmpty()
            if (expectedId.isBlank()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "替换用户 Skill 时必须指定已确认的 Skill id",
                )
            }
            if (candidates.single().id != expectedId) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "重新读取的 ZIP 与已确认替换的 Skill 不一致",
                )
            }
            if (!SHA_256_REGEX.matches(expectedDigest)) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "替换用户 Skill 时必须提供有效的小写 SHA-256",
                )
            }
            if (operation.archiveSha256 != expectedDigest) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "重新读取的 ZIP 内容与已确认归档不一致",
                )
            }
        } else if (expectedReplacementId != null || expectedArchiveSha256 != null) {
            fail(
                SkillInstallErrorCode.INVALID_SELECTION,
                "仅替换现有用户 Skill 时可指定替换身份与归档摘要",
            )
        }
        installCandidates(
            operation = operation,
            candidates = candidates,
            replaceUserSkills = replaceUserSkill,
            isCancelled = isCancelled,
            conflictArchiveSha256 = operation.archiveSha256,
        )
    }

    /** 检查仓库 ZIP。单一顶层目录按 GitHub codeload 的 envelope 处理。 */
    fun inspectRepositoryZip(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean = { false },
    ): SkillArchiveInspectionResult = withArchiveInspection(openStream, isCancelled) { extractedRoot ->
        val repositoryRoot = repositoryContentRoot(extractedRoot)
        val candidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
        if (candidates.isEmpty()) {
            fail(SkillInstallErrorCode.NO_SKILL_FOUND, "仓库 ZIP 中没有找到 SKILL.md")
        }
        SkillArchiveInspectionResult.Success(candidates.map { it.publicModel })
    }

    /** selectedPaths 使用 [inspectRepositoryZip] 返回的仓库相对路径。 */
    fun installRepositoryZip(
        openStream: () -> InputStream,
        selectedPaths: List<String>,
        replaceUserSkills: Boolean = false,
        expectedReplacementIds: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult {
        val selectedPathsSnapshot = selectedPaths.toList()
        val expectedIdsSnapshot = expectedReplacementIds.toSet()
        return withArchive(openStream, isCancelled) { operation, extractedRoot ->
            if (selectedPathsSnapshot.isEmpty()) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "至少选择一个 Skill 路径")
            }
            val repositoryRoot = repositoryContentRoot(extractedRoot)
            val allCandidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
            if (allCandidates.isEmpty()) {
                fail(SkillInstallErrorCode.NO_SKILL_FOUND, "仓库 ZIP 中没有找到 SKILL.md")
            }
            val candidatesByPath = allCandidates.associateBy { it.relativePath }
            val normalizedSelections = selectedPathsSnapshot.map(::normalizeSelectionPath)
            if (normalizedSelections.distinct().size != normalizedSelections.size) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "选择中包含重复的 Skill 路径")
            }
            val selected = normalizedSelections.map { relativePath ->
                candidatesByPath[relativePath]
                    ?: fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "所选路径不是有效的 Skill 根目录：$relativePath",
                    )
            }
            rejectNestedCandidateSelections(selected, allCandidates)
            val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
            if (replaceUserSkills) {
                if (expectedIdsSnapshot.isEmpty() || selectedIds != expectedIdsSnapshot) {
                    fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "重新读取的仓库 Skill 与已确认替换的 Skill 集合不一致",
                    )
                }
            } else if (expectedIdsSnapshot.isNotEmpty()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "仅替换现有用户 Skill 时可指定 expectedReplacementIds",
                )
            }
            installCandidates(
                operation = operation,
                candidates = selected,
                replaceUserSkills = replaceUserSkills,
                isCancelled = isCancelled,
                conflictArchiveSha256 = null,
            )
        }
    }

    private fun installCandidates(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult = indexService.withMutationLock {
        checkCancelled(isCancelled)
        installCandidatesLocked(
            operation,
            candidates,
            replaceUserSkills,
            isCancelled,
            conflictArchiveSha256,
        )
    }

    private fun installCandidatesLocked(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult {
        val duplicateIds = candidates.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            fail(
                SkillInstallErrorCode.DUPLICATE_SKILL_ID,
                "所选 Skill 使用了重复名称：${duplicateIds.sorted().joinToString()}",
            )
        }

        val installedEntries = indexService.listSkillsForManagement(forceRefresh = true)
            .associateBy { it.id }
        val conflicts = mutableListOf<SkillInstallConflict>()
        candidates.forEach { candidate ->
            val target = File(canonicalSkillsRoot, candidate.id)
            val existing = installedEntries[candidate.id]
            when {
                builtinIdLookup(candidate.id) -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = BUILTIN_SKILL_SOURCE,
                    replaceAllowed = false,
                )
                existing != null && !replaceUserSkills -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = existing.source,
                    replaceAllowed = existing.source == USER_SKILL_SOURCE &&
                        isReplaceableTarget(existing, target),
                )
                existing != null && !isReplaceableTarget(existing, target) -> conflicts +=
                    SkillInstallConflict(
                        id = candidate.id,
                        name = candidate.name,
                        existingSource = existing.source,
                        replaceAllowed = false,
                    )
                existing == null && target.exists() -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = "unknown",
                    replaceAllowed = false,
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            return SkillInstallResult.Conflict(
                conflicts = conflicts.sortedBy { it.id },
                archiveSha256 = conflictArchiveSha256,
            )
        }

        // 从此处开始进入不可中断的短事务；取消只在任何正式文件变更发生前生效。
        checkCancelled(isCancelled)
        if (!canonicalSkillsRoot.exists() && !canonicalSkillsRoot.mkdirs()) {
            fail(SkillInstallErrorCode.IO_ERROR, "无法创建 Skills 目录")
        }
        val backupRoot = File(operation.directory, "backup")
        if (!backupRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "无法创建安装备份目录")
        }
        val registrySnapshots = indexService
            .captureRegistryRecoverySnapshots(candidates.map { it.id })
            .associateBy { it.skillId }
        var recoveryJournal: PendingSkillRecoveryJournal? = null
        try {
            recoveryJournal = PendingSkillRecoveryJournal.begin(
                skillsRoot = canonicalSkillsRoot,
                operationDirectory = operation.directory,
                records = candidates.map { candidate ->
                    SkillRecoveryRecord(
                        id = candidate.id,
                        originalTargetExisted = Files.exists(
                            File(canonicalSkillsRoot, candidate.id).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                        registrySnapshot = checkNotNull(registrySnapshots[candidate.id]),
                    )
                },
            )
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                if (target.exists()) {
                    val backup = File(backupRoot, candidate.id)
                    directoryMover.move(target, backup)
                    recoveryJournal.markBackupCompleted(candidate.id)
                }
            }
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                directoryMover.move(candidate.directory, target)
                recoveryJournal.markNewTargetCommitted(candidate.id)
            }
            indexService.registerInstalledUserSkills(candidates.map { it.id })
            recoveryJournal.clear()
        } catch (error: Exception) {
            val journalExists = Files.exists(
                File(operation.directory, JOURNAL_FILE_NAME).toPath(),
                LinkOption.NOFOLLOW_LINKS,
            )
            val rollbackComplete = !journalExists || runCatching {
                val recovered = recoverPendingSkillOperations(canonicalSkillsRoot, directoryMover)
                indexService.restoreRecoveredRegistry(recovered)
                completeRecoveredSkillOperations(canonicalSkillsRoot, recovered)
            }.isSuccess
            if (!rollbackComplete) operation.preserveForRecovery = true
            val suffix = if (rollbackComplete) "，旧 Skill 已恢复" else "，且自动恢复未完整完成"
            return SkillInstallResult.Failure(
                SkillInstallError(
                    code = SkillInstallErrorCode.COMMIT_FAILED,
                    message = "Skill 安装提交失败$suffix",
                ),
                recoveryRequired = !rollbackComplete,
            )
        }

        return SkillInstallResult.Success(
            installed = candidates.map {
                InstalledSkill(id = it.id, name = it.name, description = it.description)
            }
        )
    }

    private fun isReplaceableTarget(entry: SkillIndexEntry, expectedTarget: File): Boolean {
        if (entry.source != USER_SKILL_SOURCE || !entry.installed) return false
        val existingRoot = runCatching { File(entry.rootPath).canonicalFile }.getOrNull() ?: return false
        if (existingRoot != expectedTarget.canonicalFile || !existingRoot.isDirectory) return false
        return isRecoverableSkillDirectoryTree(canonicalSkillsRoot, existingRoot)
    }

    private fun rejectNestedCandidateSelections(
        selected: List<PreparedCandidate>,
        allCandidates: List<PreparedCandidate>,
    ) {
        selected.forEach { parent ->
            val nested = allCandidates.firstOrNull { candidate ->
                candidate !== parent && candidate.directory.toPath().startsWith(parent.directory.toPath())
            }
            if (nested != null) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "Skill ${parent.relativePath} 内还包含另一个 SKILL.md：${nested.relativePath}",
                )
            }
        }
    }

    private fun discoverCandidates(
        scanRoot: File,
        relativeRoot: File,
        isCancelled: () -> Boolean,
    ): List<PreparedCandidate> {
        val skillFiles = scanRoot.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name == SKILL_FILE_NAME }
            .toList()
        return skillFiles.map { skillFile ->
            checkCancelled(isCancelled)
            val directory = requireNotNull(skillFile.parentFile).canonicalFile
            val relativePath = directory.relativeTo(relativeRoot.canonicalFile)
                .invariantSeparatorsPath
                .ifBlank { "." }
            val metadata = parseAndValidateSkill(skillFile)
            PreparedCandidate(
                id = metadata.name,
                name = metadata.name,
                description = metadata.description,
                relativePath = relativePath,
                directory = directory,
            )
        }.sortedBy { it.relativePath }
    }

    private fun parseAndValidateSkill(skillFile: File): ValidatedSkillMetadata {
        if (skillFile.length() > limits.maxSkillFileBytes) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "${skillFile.name} 超过 ${limits.maxSkillFileBytes} 字节限制",
            )
        }
        val raw = readStrictUtf8(skillFile, limits.maxSkillFileBytes)
            ?: fail(SkillInstallErrorCode.INVALID_SKILL, "SKILL.md 必须是 UTF-8 文本")
        val frontmatter = strictFrontmatter(raw)
            ?: fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "SKILL.md 必须包含以 --- 包围的 YAML frontmatter",
            )
        val parsed = SkillParser.parseSimpleFrontmatter(frontmatter)
        val name = parsed["name"]?.trim().orEmpty()
        val description = parsed["description"]?.trim().orEmpty()
        if (name.length !in 1..MAX_SKILL_NAME_LENGTH || !SKILL_NAME_REGEX.matches(name)) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "Skill name 必须为不超过 $MAX_SKILL_NAME_LENGTH 字符的小写字母、数字和单连字符组合",
            )
        }
        if (description.isBlank() || description.length > MAX_SKILL_DESCRIPTION_LENGTH) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "Skill description 必填且不能超过 $MAX_SKILL_DESCRIPTION_LENGTH 字符",
            )
        }
        return ValidatedSkillMetadata(name = name, description = description)
    }

    private fun strictFrontmatter(raw: String): String? {
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0 || raw.substring(0, firstLineEnd).trimEnd('\r') != "---") return null
        var lineStart = firstLineEnd + 1
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            if (raw.substring(lineStart, lineEnd).trimEnd('\r') == "---") {
                return raw.substring(firstLineEnd + 1, lineStart).trimEnd('\r', '\n')
            }
            if (lineEnd == raw.length) break
            lineStart = lineEnd + 1
        }
        return null
    }

    private fun repositoryContentRoot(extractedRoot: File): File {
        val children = extractedRoot.listFiles().orEmpty()
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: extractedRoot
    }

    private fun normalizeSelectionPath(raw: String): String {
        val value = raw.trim().removeSuffix("/")
        if (value == ".") return value
        val segments = validateRelativePath(value, SkillInstallErrorCode.INVALID_SELECTION)
        return segments.joinToString("/")
    }

    private fun extractArchive(
        archiveFile: File,
        targetRoot: File,
        isCancelled: () -> Boolean,
    ) {
        if (!targetRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "无法创建 ZIP 暂存目录")
        }
        val pathKinds = linkedMapOf<String, Boolean>()
        var entryCount = 0
        var extractedBytes = 0L
        ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > limits.maxEntries) {
                    fail(
                        SkillInstallErrorCode.TOO_MANY_ENTRIES,
                        "ZIP 条目数超过 ${limits.maxEntries} 个",
                    )
                }
                val normalizedName = entry.name.removeSuffix("/")
                val segments = validateRelativePath(
                    normalizedName,
                    SkillInstallErrorCode.UNSAFE_ENTRY_PATH,
                )
                if (segments.size > limits.maxPathDepth) {
                    fail(
                        SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
                        "ZIP 条目路径层级超过 ${limits.maxPathDepth} 层",
                    )
                }
                val relativePath = segments.joinToString("/")
                val collisionKey = collisionKey(relativePath)
                if (pathKinds.containsKey(collisionKey)) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 包含重复条目：$relativePath")
                }
                val ancestorKeys = segments.indices.drop(1).map { index ->
                    collisionKey(segments.take(index).joinToString("/"))
                }
                if (ancestorKeys.any { pathKinds[it] == false }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 条目存在文件与目录冲突：$relativePath")
                }
                if (!entry.isDirectory && pathKinds.keys.any { it.startsWith("$collisionKey/") }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 条目存在文件与目录冲突：$relativePath")
                }
                pathKinds[collisionKey] = entry.isDirectory

                val target = File(targetRoot, relativePath)
                ensureWithin(targetRoot, target)
                if (entry.isDirectory) {
                    if (!target.mkdirs() && !target.isDirectory) {
                        fail(SkillInstallErrorCode.IO_ERROR, "无法创建 ZIP 目录")
                    }
                    if (zip.read() != -1) {
                        fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 目录条目包含数据")
                    }
                } else {
                    if (entry.size > limits.maxSingleFileBytes) {
                        fail(
                            SkillInstallErrorCode.ENTRY_TOO_LARGE,
                            "ZIP 单个文件超过 ${limits.maxSingleFileBytes} 字节限制",
                        )
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.mkdirs() && !parent.isDirectory) {
                            fail(SkillInstallErrorCode.IO_ERROR, "无法创建 ZIP 文件目录")
                        }
                    }
                    var fileBytes = 0L
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkCancelled(isCancelled)
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (fileBytes > limits.maxSingleFileBytes) {
                                fail(
                                    SkillInstallErrorCode.ENTRY_TOO_LARGE,
                                    "ZIP 单个文件超过 ${limits.maxSingleFileBytes} 字节限制",
                                )
                            }
                            if (extractedBytes > limits.maxExtractedBytes) {
                                fail(
                                    SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE,
                                    "ZIP 解压内容超过 ${limits.maxExtractedBytes} 字节限制",
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0) {
            fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 为空或格式无效")
        }
    }

    private fun materializeArchive(
        openStream: () -> InputStream,
        target: File,
        isCancelled: () -> Boolean,
    ): String {
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        checkCancelled(isCancelled)
        openStream().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limits.maxArchiveBytes) {
                        fail(
                            SkillInstallErrorCode.ARCHIVE_TOO_LARGE,
                            "ZIP 压缩包超过 ${limits.maxArchiveBytes} 字节限制",
                        )
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total == 0L) {
            fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 为空")
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun validateRelativePath(
        raw: String,
        errorCode: SkillInstallErrorCode,
    ): List<String> {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') ||
            WINDOWS_DRIVE_PREFIX.containsMatchIn(raw) || raw.contains('\\') || raw.contains('\u0000') ||
            raw.any { it.isISOControl() }
        ) {
            fail(errorCode, "路径不是安全的相对路径")
        }
        val segments = raw.split('/')
        if (
            segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment.length > MAX_PATH_SEGMENT_LENGTH
            }
        ) {
            fail(errorCode, "路径包含非法层级")
        }
        return segments
    }

    private fun collisionKey(path: String): String = Normalizer
        .normalize(path, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    private fun ensureWithin(root: File, target: File) {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) {
            fail(SkillInstallErrorCode.UNSAFE_ENTRY_PATH, "ZIP 条目试图写出暂存目录")
        }
    }

    private fun withArchive(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (operation: ArchiveOperation, extractedRoot: File) -> SkillInstallResult,
    ): SkillInstallResult {
        val operationDir = createOperationDirectory()
            ?: return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "无法创建 Skill 安装暂存目录")
            )
        val operation = ArchiveOperation(operationDir)
        return try {
            val archiveFile = File(operationDir, "package.zip")
            operation.archiveSha256 = materializeArchive(openStream, archiveFile, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            extractArchive(archiveFile, extractedRoot, isCancelled)
            block(operation, extractedRoot)
        } catch (error: SkillInstallException) {
            SkillInstallResult.Failure(error.error)
        } catch (_: SkillRecoveryRequiredException) {
            SkillInstallResult.Failure(
                error = SkillInstallError(
                    SkillInstallErrorCode.COMMIT_FAILED,
                    "检测到未完成的 Skill 恢复，已停止安装",
                ),
                recoveryRequired = true,
            )
        } catch (_: ZipException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 格式无效或内容已损坏")
            )
        } catch (_: IOException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "读取或暂存 ZIP 失败")
            )
        } catch (_: SecurityException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "没有读取该 ZIP 的权限")
            )
        } finally {
            if (!operation.preserveForRecovery) {
                deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
            }
        }
    }

    private fun withArchiveInspection(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (extractedRoot: File) -> SkillArchiveInspectionResult,
    ): SkillArchiveInspectionResult {
        val operationDir = createOperationDirectory()
            ?: return SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "无法创建 Skill 检查暂存目录")
            )
        return try {
            val archiveFile = File(operationDir, "package.zip")
            materializeArchive(openStream, archiveFile, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            extractArchive(archiveFile, extractedRoot, isCancelled)
            block(extractedRoot)
        } catch (error: SkillInstallException) {
            SkillArchiveInspectionResult.Failure(error.error)
        } catch (_: ZipException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 格式无效或内容已损坏")
            )
        } catch (_: IOException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "读取或暂存 ZIP 失败")
            )
        } catch (_: SecurityException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "没有读取该 ZIP 的权限")
            )
        } finally {
            deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
        }
    }

    private fun createOperationDirectory(): File? {
        val safeWorkRoot = runCatching {
            prepareSkillInstallerWorkRoot(canonicalSkillsRoot)
        }.getOrNull() ?: return null
        if (safeWorkRoot != workRoot) return null
        repeat(4) {
            val candidate = File(safeWorkRoot, "operation-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        return null
    }

    private fun fail(code: SkillInstallErrorCode, message: String): Nothing =
        throw SkillInstallException(SkillInstallError(code, message))

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            fail(SkillInstallErrorCode.CANCELLED, "Skill 安装已取消")
        }
    }

    private data class PreparedCandidate(
        val id: String,
        val name: String,
        val description: String,
        val relativePath: String,
        val directory: File,
    ) {
        val publicModel: SkillArchiveCandidate
            get() = SkillArchiveCandidate(
                id = id,
                name = name,
                description = description,
                relativePath = relativePath,
            )
    }

    private data class ValidatedSkillMetadata(
        val name: String,
        val description: String,
    )

    private data class ArchiveOperation(
        val directory: File,
        var archiveSha256: String = "",
        var preserveForRecovery: Boolean = false,
    )

    private class SkillInstallException(
        val error: SkillInstallError,
    ) : RuntimeException(error.message)

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_NAME_LENGTH = 64
        const val MAX_SKILL_DESCRIPTION_LENGTH = 1_024
        const val MAX_PATH_SEGMENT_LENGTH = 255
        val SKILL_NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
        val SHA_256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}

internal fun interface SkillDirectoryMover {
    fun move(source: File, target: File)
}

internal object AtomicSkillDirectoryMover : SkillDirectoryMover {
    override fun move(source: File, target: File) {
        moveSkillDirectoryAtomically(source, target)
    }
}
