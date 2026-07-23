package fuck.andes.ui.app

import android.content.Context
import fuck.andes.agent.skill.SkillInstallErrorCode
import fuck.andes.agent.skill.SkillInstallResult
import fuck.andes.agent.skill.SkillRuntime
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * UI 与技能安装内核之间的窄适配层。UI 只负责重新打开 SAF 输入流，不解析或解压 ZIP。
 */
internal fun interface SkillZipImportGateway {
    suspend fun installLocalZip(
        openStream: () -> InputStream,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ): SkillZipImportOutcome
}

internal class CoreSkillZipImportGateway(
    context: Context,
) : SkillZipImportGateway {
    private val installer = SkillRuntime.createPackageInstaller(context.applicationContext)

    override suspend fun installLocalZip(
        openStream: () -> InputStream,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ): SkillZipImportOutcome {
        val callingContext = currentCoroutineContext()
        return installer.installLocalZip(
            openStream = openStream,
            replaceUserSkill = replaceUserSkill,
            expectedReplacementId = expectedReplacementId,
            expectedArchiveSha256 = expectedArchiveSha256,
            isCancelled = { !callingContext.isActive },
        ).toZipImportOutcome()
    }
}

internal fun SkillInstallResult.toZipImportOutcome(): SkillZipImportOutcome = when (this) {
    is SkillInstallResult.Success -> SkillZipImportOutcome.Success(
        skills = installed.map { skill ->
            SkillZipImportOutcome.InstalledSkill(
                id = skill.id,
                name = skill.name,
            )
        },
    )

    is SkillInstallResult.Conflict -> SkillZipImportOutcome.Conflict(
        skills = conflicts.map { conflict ->
            SkillZipImportOutcome.ConflictingSkill(
                id = conflict.id,
                name = conflict.name,
                source = conflict.existingSource,
                replaceAllowed = conflict.replaceAllowed,
            )
        },
        archiveSha256 = archiveSha256,
    )

    is SkillInstallResult.Failure -> SkillZipImportOutcome.Failure(
        code = if (recoveryRequired) {
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED
        } else {
            error.code.toUiFailureCode()
        },
    )
}

internal fun SkillInstallErrorCode.toUiFailureCode(): SkillZipImportOutcome.FailureCode = when (this) {
    SkillInstallErrorCode.INVALID_ARCHIVE -> SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE
    SkillInstallErrorCode.ARCHIVE_TOO_LARGE,
    SkillInstallErrorCode.TOO_MANY_ENTRIES,
    SkillInstallErrorCode.ENTRY_TOO_LARGE,
    SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE,
    SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
    -> SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED

    SkillInstallErrorCode.UNSAFE_ENTRY_PATH,
    SkillInstallErrorCode.DUPLICATE_ENTRY,
    -> SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE

    SkillInstallErrorCode.NO_SKILL_FOUND -> SkillZipImportOutcome.FailureCode.NO_SKILL
    SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND -> SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
    SkillInstallErrorCode.INVALID_SELECTION -> SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
    SkillInstallErrorCode.INVALID_SKILL,
    SkillInstallErrorCode.DUPLICATE_SKILL_ID,
    -> SkillZipImportOutcome.FailureCode.INVALID_SKILL

    SkillInstallErrorCode.TARGET_NOT_REPLACEABLE ->
        SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
    SkillInstallErrorCode.CANCELLED -> SkillZipImportOutcome.FailureCode.READ_FAILED
    SkillInstallErrorCode.IO_ERROR -> SkillZipImportOutcome.FailureCode.READ_FAILED
    SkillInstallErrorCode.COMMIT_FAILED -> SkillZipImportOutcome.FailureCode.STORAGE_FAILED
}

internal sealed interface SkillZipImportOutcome {
    data class Success(
        val skills: List<InstalledSkill>,
    ) : SkillZipImportOutcome

    data class Conflict(
        val skills: List<ConflictingSkill>,
        val archiveSha256: String?,
    ) : SkillZipImportOutcome

    data class Failure(
        val code: FailureCode,
    ) : SkillZipImportOutcome

    data class InstalledSkill(
        val id: String,
        val name: String,
    )

    data class ConflictingSkill(
        val id: String,
        val name: String,
        val source: String,
        val replaceAllowed: Boolean,
    )

    enum class FailureCode {
        INVALID_ARCHIVE,
        ARCHIVE_LIMIT_EXCEEDED,
        UNSAFE_ARCHIVE,
        NO_SKILL,
        MULTIPLE_SKILLS,
        INVALID_SKILL,
        PACKAGE_CHANGED,
        BUILTIN_CONFLICT,
        TARGET_NOT_REPLACEABLE,
        READ_FAILED,
        STORAGE_FAILED,
        RECOVERY_REQUIRED,
    }
}
