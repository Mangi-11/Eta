package io.github.mangi.eta.hook.support

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import io.github.mangi.eta.core.ModuleLogger

internal enum class HookInstallStatus {
    INSTALLED,
    MISSING,
    FAILED,
    SKIPPED
}

internal data class HookInstallEntry(
    val group: String,
    val id: String,
    val description: String,
    val status: HookInstallStatus,
    val detail: String? = null
)

internal data class HookInstallReport(
    val group: String,
    val entries: List<HookInstallEntry>
) {
    val installedCount: Int = entries.count { it.status == HookInstallStatus.INSTALLED }
    val missingCount: Int = entries.count { it.status == HookInstallStatus.MISSING }
    val failedCount: Int = entries.count { it.status == HookInstallStatus.FAILED }
    val skippedCount: Int = entries.count { it.status == HookInstallStatus.SKIPPED }

    fun summary(): String =
        "Hook 安装完成: installed=$installedCount, missing=$missingCount, " +
            "failed=$failedCount, skipped=$skippedCount"

    companion object {
        fun combine(group: String, reports: Iterable<HookInstallReport>): HookInstallReport {
            val reportList = reports.toList()
            return HookInstallReport(
                group = group,
                entries = reportList.flatMap { it.entries }
            )
        }
    }
}

internal data class HookInstallation(
    val report: HookInstallReport,
    val handles: List<XposedInterface.HookHandle>
) {
    companion object {
        fun combine(group: String, installations: Iterable<HookInstallation>): HookInstallation {
            val installationList = installations.toList()
            return HookInstallation(
                report = HookInstallReport.combine(group, installationList.map { it.report }),
                handles = installationList.flatMap { it.handles }
            )
        }
    }
}

/** 安装报告的纯状态账本，不持有框架对象。 */
internal class HookInstallJournal(private val group: String) {
    private val entries = mutableListOf<HookInstallEntry>()

    fun capture(block: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            failed(
                id = "install.failed",
                description = "$group Hook 组安装",
                detail = exception.javaClass.simpleName
            )
            onFailure(exception)
        }
    }

    fun installed(id: String, description: String) {
        record(id, description, HookInstallStatus.INSTALLED)
    }

    fun missing(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.MISSING, detail)
    }

    fun failed(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.FAILED, detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        record(id, description, HookInstallStatus.SKIPPED, detail)
    }

    fun report(): HookInstallReport = HookInstallReport(group, entries.toList())

    private fun record(
        id: String,
        description: String,
        status: HookInstallStatus,
        detail: String? = null
    ) {
        entries += HookInstallEntry(group, id, description, status, detail)
    }
}

/**
 * 单个功能域的 Hook 注册器。这里只处理 API 注册与安装诊断，目标定位仍由功能域负责。
 */
internal class HookRegistrar(
    private val module: XposedModule,
    logger: ModuleLogger,
    private val group: String
) {
    val logger: ModuleLogger = logger.scoped(group)

    private val journal = HookInstallJournal(group)
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val registrationKeys = mutableSetOf<RegistrationKey>()

    /**
     * 在功能组边界内完成安装。异常发生前已经注册的 Hook 仍会保留在报告和句柄集合中。
     */
    fun install(block: HookRegistrar.() -> Unit): HookInstallation {
        journal.capture(block = { block() }) { exception ->
            // XposedFrameworkError 属于 Error，不会被功能组隔离层吞掉。
            logger.error("Hook 组安装失败", exception)
        }
        return finish()
    }

    fun intercept(
        id: String,
        executable: Executable,
        description: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hooker: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle? {
        require(STABLE_ID.matches(id)) { "Hook id 格式无效: $id" }
        val fullId = "eta.$id"
        val registrationKey = RegistrationKey(executable, fullId)
        if (!registrationKeys.add(registrationKey)) {
            val detail = "重复 Hook 注册: $description ($fullId)"
            journal.failed(id, description, detail)
            logger.error(detail)
            return null
        }
        return try {
            val handle = module.hook(executable)
                .setPriority(priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(fullId)
                .intercept { chain -> hooker(chain) }
            handles += handle
            journal.installed(id, description)
            logger.debug { "已安装 Hook: $description" }
            handle
        } catch (exception: Exception) {
            // HookFailedError 属于 Error，不会进入这里，必须继续交给框架处理。
            registrationKeys.remove(registrationKey)
            journal.failed(id, description, exception.javaClass.simpleName)
            logger.error("安装 Hook 失败: $description", exception)
            null
        }
    }

    fun missing(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "Hook id 格式无效: $id" }
        journal.missing(id, description, detail)
        logger.warn(detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "Hook id 格式无效: $id" }
        journal.skipped(id, description, detail)
        logger.debug { detail }
    }

    private fun finish(): HookInstallation = HookInstallation(
        report = journal.report(),
        handles = handles.toList()
    )

    private companion object {
        val STABLE_ID = Regex("[a-z0-9][a-z0-9._-]*")
    }

    private data class RegistrationKey(
        val executable: Executable,
        val fullId: String
    )
}
