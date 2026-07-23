package fuck.andes.agent.skill

/** ZIP 中可安装的 Skill。relativePath 以仓库根目录为基准，仓库根本身使用 `.`。 */
data class SkillArchiveCandidate(
    val id: String,
    val name: String,
    val description: String,
    val relativePath: String,
)

data class InstalledSkill(
    val id: String,
    val name: String,
    val description: String,
)

data class SkillInstallConflict(
    val id: String,
    val name: String,
    val existingSource: String,
    val replaceAllowed: Boolean,
)

enum class SkillInstallErrorCode {
    INVALID_ARCHIVE,
    ARCHIVE_TOO_LARGE,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    EXTRACTED_CONTENT_TOO_LARGE,
    ENTRY_PATH_TOO_DEEP,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY,
    NO_SKILL_FOUND,
    MULTIPLE_SKILLS_FOUND,
    INVALID_SKILL,
    INVALID_SELECTION,
    DUPLICATE_SKILL_ID,
    TARGET_NOT_REPLACEABLE,
    CANCELLED,
    IO_ERROR,
    COMMIT_FAILED,
}

data class SkillInstallError(
    val code: SkillInstallErrorCode,
    val message: String,
)

sealed interface SkillArchiveInspectionResult {
    data class Success(
        val candidates: List<SkillArchiveCandidate>,
    ) : SkillArchiveInspectionResult

    data class Failure(
        val error: SkillInstallError,
    ) : SkillArchiveInspectionResult
}

sealed interface SkillInstallResult {
    data class Success(
        val installed: List<InstalledSkill>,
    ) : SkillInstallResult

    data class Conflict(
        val conflicts: List<SkillInstallConflict>,
        /** 本地 ZIP 冲突时返回；来自安装核心实际物化并检查的同一份归档字节。 */
        val archiveSha256: String? = null,
    ) : SkillInstallResult

    data class Failure(
        val error: SkillInstallError,
        /** 自动回滚不完整，应用私有恢复目录中保留了旧 Skill 备份。 */
        val recoveryRequired: Boolean = false,
    ) : SkillInstallResult
}

data class SkillResourceInfo(
    val relativePath: String,
    val sizeBytes: Long,
)

enum class SkillResourceErrorCode {
    INVALID_SKILL_ROOT,
    INVALID_RELATIVE_PATH,
    RESOURCE_NOT_FOUND,
    RESOURCE_TOO_LARGE,
    BINARY_RESOURCE,
    TOO_MANY_RESOURCES,
    IO_ERROR,
}

data class SkillResourceError(
    val code: SkillResourceErrorCode,
    val message: String,
)

sealed interface SkillResourceListResult {
    data class Success(
        val resources: List<SkillResourceInfo>,
    ) : SkillResourceListResult

    data class Failure(
        val error: SkillResourceError,
    ) : SkillResourceListResult
}

sealed interface SkillResourceReadResult {
    data class Success(
        val relativePath: String,
        val text: String,
    ) : SkillResourceReadResult

    data class Failure(
        val error: SkillResourceError,
    ) : SkillResourceReadResult
}
