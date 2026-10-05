package io.github.mangi.eta.agent.device

/**
 * 特权命令网关：Root 优先，Shizuku shell 兜底。
 *
 * 适用命令仅限 ADB/shell 级固定命令（`settings/cmd/pm/am/logcat/dumpsys/ps/content query(非保护域)`）。
 * 以下类别不得经由此网关下发，维持 Root 专用：
 * `/data/misc/apexdata` 与各应用私有 `databases/` 快照、短信/通话等需运行时权限的 provider、
 * chroot/daemon/系统化安装等超越 shell 的操作。
 *
 * 无任何提权通道时返回 `ROOT_REQUIRED`（沿用既有错误码，避免调用方错误映射分裂；
 * Shizuku 满足的工具在 [AgentToolRequirements][io.github.mangi.eta.agent.tool.AgentToolRequirements]
 * 投影阶段即保留，不会走到此处）。
 */
internal class PrivilegedCommandExecutor(
    private val root: BoundedRootCommandExecutor,
    private val shizuku: ShizukuShellExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val shizukuAvailable: () -> Boolean = { ShizukuAccess.isAvailable },
) {
    fun elevatedAvailable(): Boolean = rootAvailable() || shizukuAvailable()

    fun execute(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): BoundedRootCommandExecutor.Result {
        if (rootAvailable()) return root.execute(command, timeoutMillis, maxOutputBytes)
        if (shizukuAvailable()) return shizuku.execute(command, timeoutMillis, maxOutputBytes)
        return BoundedRootCommandExecutor.Result.failed("ROOT_REQUIRED")
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
    }
}
