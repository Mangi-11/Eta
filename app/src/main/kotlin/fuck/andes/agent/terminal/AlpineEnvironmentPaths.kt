package fuck.andes.agent.terminal

import android.content.Context
import java.io.File

/** Eta 管理的 Linux 工具环境路径；内部历史包名不参与对外展示。 */
internal object AlpineEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"
    const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"

    fun environmentDir(context: Context): File =
        File(context.filesDir, "terminal/alpine")

    fun rootfsDir(context: Context): File =
        File(environmentDir(context), "rootfs")

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        val rootfs = File(rootfsPath)
        // Alpine 的 /bin/sh 是指向 /bin/busybox 的绝对链接，从 chroot 外检查会落到 Android /bin。
        return File(rootfs, READY_MARKER).isFile && File(rootfs, "bin/busybox").isFile
    }

    fun commonToolsReady(rootfsPath: String?): Boolean {
        if (!rootfsReady(rootfsPath)) return false
        return File(rootfsPath, COMMON_TOOLS_MARKER).isFile
    }
}
