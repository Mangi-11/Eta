package fuck.andes.agent.terminal

import android.content.Context
import android.os.Build
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.safeLogType
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

internal enum class AlpineEnvironmentState {
    NOT_INSTALLED,
    BASE_READY,
    READY,
}

internal data class AlpineEnvironmentStatus(
    val state: AlpineEnvironmentState,
    val version: String? = null,
)

internal enum class AlpineInstallStage(val displayName: String) {
    CHECKING("检查 Root 与 BusyBox"),
    DOWNLOADING("下载 Alpine 基础环境"),
    EXTRACTING("解压基础环境"),
    INSTALLING_TOOLS("安装常用工具"),
    COMPLETE("环境已就绪"),
}

internal data class AlpineInstallProgress(
    val stage: AlpineInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface AlpineInstallResult {
    data object AlreadyReady : AlpineInstallResult
    data class Installed(val version: String) : AlpineInstallResult
    data class UnsupportedAbi(val abi: String) : AlpineInstallResult
    data object RootUnavailable : AlpineInstallResult
    data object BusyBoxUnavailable : AlpineInstallResult
    data object EnvironmentUnavailable : AlpineInstallResult
    data class Failed(val stage: AlpineInstallStage) : AlpineInstallResult
}

/**
 * 下载官方 Alpine minirootfs，并在 Root 授权边界内完成原子解压与常用工具安装。
 * 下载内容先校验固定 SHA-256；安装过程不会扩大到 App 私有环境目录之外。
 */
internal class AlpineEnvironmentInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    fun status(): AlpineEnvironmentStatus {
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        val version = readInstalledVersion(rootfs)
        val state = when {
            AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath) -> AlpineEnvironmentState.READY
            AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath) -> AlpineEnvironmentState.BASE_READY
            else -> AlpineEnvironmentState.NOT_INSTALLED
        }
        return AlpineEnvironmentStatus(state = state, version = version)
    }

    suspend fun install(
        onProgress: suspend (AlpineInstallProgress) -> Unit = {},
    ): AlpineInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (AlpineInstallProgress) -> Unit,
    ): AlpineInstallResult = withContext(Dispatchers.IO) {
        if (status().state == AlpineEnvironmentState.READY) {
            return@withContext AlpineInstallResult.AlreadyReady
        }
        val artifact = artifactForAbis(Build.SUPPORTED_ABIS.toList())
            ?: return@withContext AlpineInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            )

        onProgress(AlpineInstallProgress(AlpineInstallStage.CHECKING))
        when (runPreflight().exitCode) {
            0 -> Unit
            PREFLIGHT_ROOT_UNAVAILABLE -> return@withContext AlpineInstallResult.RootUnavailable
            PREFLIGHT_BUSYBOX_UNAVAILABLE, PREFLIGHT_BUSYBOX_INCOMPLETE ->
                return@withContext AlpineInstallResult.BusyBoxUnavailable
            PREFLIGHT_ENVIRONMENT_UNAVAILABLE ->
                return@withContext AlpineInstallResult.EnvironmentUnavailable
            else -> return@withContext AlpineInstallResult.Failed(AlpineInstallStage.CHECKING)
        }

        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        if (!AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            val archive = File(context.cacheDir, artifact.fileName + ".download")
            try {
                onProgress(AlpineInstallProgress(AlpineInstallStage.DOWNLOADING))
                val downloaded = downloadArtifact(artifact, archive, onProgress)
                if (!downloaded) {
                    return@withContext AlpineInstallResult.Failed(AlpineInstallStage.DOWNLOADING)
                }
                coroutineContext.ensureActive()
                onProgress(AlpineInstallProgress(AlpineInstallStage.EXTRACTING))
                val extracted = installRootfs(artifact, archive, rootfs)
                if (!extracted) {
                    return@withContext AlpineInstallResult.Failed(AlpineInstallStage.EXTRACTING)
                }
            } finally {
                archive.delete()
            }
        }

        coroutineContext.ensureActive()
        onProgress(AlpineInstallProgress(AlpineInstallStage.INSTALLING_TOOLS))
        if (!installCommonTools(rootfs)) {
            return@withContext AlpineInstallResult.Failed(AlpineInstallStage.INSTALLING_TOOLS)
        }
        onProgress(AlpineInstallProgress(AlpineInstallStage.COMPLETE))
        AlpineInstallResult.Installed(artifact.version)
    }

    private suspend fun runPreflight(): InstallerCommandResult {
        val requiredApplets = listOf(
            "ash",
            "chroot",
            "gzip",
            "mount",
            "sha256sum",
            "tar",
            "unshare",
        ).joinToString(" ")
        val command = """
            if [ "${'$'}(id -u)" != 0 ]; then exit $PREFLIGHT_ROOT_UNAVAILABLE; fi
            ${AndroidBusyBox.discoveryScript()}
            if [ -z "${'$'}eta_busybox" ]; then exit $PREFLIGHT_BUSYBOX_UNAVAILABLE; fi
            for eta_applet in $requiredApplets; do
              "${'$'}eta_busybox" --list | "${'$'}eta_busybox" grep -qx "${'$'}eta_applet" || exit $PREFLIGHT_BUSYBOX_INCOMPLETE
            done
            "${'$'}eta_busybox" unshare -m --propagation private \
              "${'$'}eta_busybox" chroot / /system/bin/sh -c ':' || exit $PREFLIGHT_ENVIRONMENT_UNAVAILABLE
        """.trimIndent()
        return InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 15,
            environment = TerminalEnvironment.ANDROID,
        )
    }

    private suspend fun downloadArtifact(
        artifact: AlpineArtifact,
        target: File,
        onProgress: suspend (AlpineInstallProgress) -> Unit,
    ): Boolean {
        target.parentFile?.mkdirs()
        target.delete()
        val request = Request.Builder().url(artifact.url).get().build()
        val valid = try {
            httpClient.newCall(request).execute().use responseUse@ { response ->
                if (!response.isSuccessful) {
                    AndroidAgentLogger.warn(
                        "Alpine environment action=download outcome=failed httpCode=${response.code}",
                    )
                    return@responseUse false
                }
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_ARCHIVE_BYTES) return@responseUse false
                val digest = MessageDigest.getInstance("SHA-256")
                var bytesRead = 0L
                var lastReported = 0L
                var archiveTooLarge = false
                target.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytesRead += count.toLong()
                            if (bytesRead > MAX_ARCHIVE_BYTES) {
                                archiveTooLarge = true
                                break
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (bytesRead - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                lastReported = bytesRead
                                onProgress(
                                    AlpineInstallProgress(
                                        stage = AlpineInstallStage.DOWNLOADING,
                                        downloadedBytes = bytesRead,
                                        totalBytes = artifact.sizeBytes,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (archiveTooLarge) return@responseUse false
                val actualSha256 = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
                val valid = bytesRead == artifact.sizeBytes && actualSha256 == artifact.sha256
                AndroidAgentLogger.info(
                    "Alpine environment action=download outcome=${if (valid) "succeeded" else "rejected"} " +
                        "bytes=$bytesRead",
                )
                valid
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warn(
                "Alpine environment action=download outcome=failed errorType=${throwable.safeLogType()}",
            )
            false
        }
        if (!valid) target.delete()
        return valid
    }

    private suspend fun installRootfs(
        artifact: AlpineArtifact,
        archive: File,
        rootfs: File,
    ): Boolean {
        val parent = rootfs.parentFile ?: return false
        val temporaryRootfs = File(parent, "rootfs.installing")
        val markerBody = "version=${artifact.version}\\nsha256=${artifact.sha256}\\n"
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_parent=${shellQuote(parent.absolutePath)}
            eta_rootfs=${shellQuote(rootfs.absolutePath)}
            eta_temporary=${shellQuote(temporaryRootfs.absolutePath)}
            eta_actual_sha=${'$'}("${'$'}eta_busybox" sha256sum "${'$'}eta_archive" | "${'$'}eta_busybox" awk '{print ${'$'}1}')
            [ "${'$'}eta_actual_sha" = ${shellQuote(artifact.sha256)} ] || exit 65
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_parent" || exit 66
            "${'$'}eta_busybox" rm -rf "${'$'}eta_temporary"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary" || exit 66
            "${'$'}eta_busybox" tar -xzf "${'$'}eta_archive" -C "${'$'}eta_temporary" || exit 67
            [ -x "${'$'}eta_temporary/bin/busybox" ] || exit 68
            "${'$'}eta_busybox" mkdir -p \
              "${'$'}eta_temporary/proc" \
              "${'$'}eta_temporary/sys" \
              "${'$'}eta_temporary/dev" \
              "${'$'}eta_temporary/storage/emulated/0" \
              "${'$'}eta_temporary/data/local/tmp" \
              "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" chmod 1777 "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary/sdcard"
            "${'$'}eta_busybox" ln -s /storage/emulated/0 "${'$'}eta_temporary/sdcard"
            cat > "${'$'}eta_temporary/etc/resolv.conf" <<'ETA_RESOLV_EOF'
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            ETA_RESOLV_EOF
            cat > "${'$'}eta_temporary/etc/apk/repositories" <<'ETA_REPOSITORIES_EOF'
            https://dl-cdn.alpinelinux.org/alpine/v3.24/main
            https://dl-cdn.alpinelinux.org/alpine/v3.24/community
            ETA_REPOSITORIES_EOF
            printf ${shellQuote(markerBody)} > "${'$'}eta_temporary/${AlpineEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" chmod 0644 "${'$'}eta_temporary/${AlpineEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" rm -rf "${'$'}eta_rootfs"
            "${'$'}eta_busybox" mv "${'$'}eta_temporary" "${'$'}eta_rootfs" || exit 69
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 120,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "Alpine environment action=extract outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0 && AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath)
    }

    private suspend fun installCommonTools(rootfs: File): Boolean {
        val packages = COMMON_PACKAGES.joinToString(" ")
        val command = """
            apk update
            apk add --no-cache $packages
            ln -sf /usr/bin/python3 /usr/local/bin/python
            printf '%s\n' ${shellQuote(ALPINE_VERSION)} > /${AlpineEnvironmentPaths.COMMON_TOOLS_MARKER}
            chmod 0644 /${AlpineEnvironmentPaths.COMMON_TOOLS_MARKER}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = COMMON_TOOLS_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Alpine environment action=install_tools " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0 && AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)
    }

    private fun readInstalledVersion(rootfs: File): String? =
        runCatching {
            File(rootfs, AlpineEnvironmentPaths.READY_MARKER)
                .readLines()
                .firstOrNull { line -> line.startsWith("version=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { value -> value.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}")) }
        }.getOrNull()

    companion object {
        private const val ALPINE_VERSION = "3.24.1"
        private const val MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
        private const val COMMON_TOOLS_TIMEOUT_SECONDS = 600L
        private const val PREFLIGHT_ROOT_UNAVAILABLE = 40
        private const val PREFLIGHT_BUSYBOX_UNAVAILABLE = 41
        private const val PREFLIGHT_BUSYBOX_INCOMPLETE = 42
        private const val PREFLIGHT_ENVIRONMENT_UNAVAILABLE = 43

        private val installMutex = Mutex()

        private val COMMON_PACKAGES = listOf(
            "bash",
            "ca-certificates",
            "coreutils",
            "curl",
            "findutils",
            "gawk",
            "git",
            "grep",
            "gzip",
            "jq",
            "nano",
            "openssl",
            "py3-pip",
            "python3",
            "sed",
            "sqlite",
            "tar",
            "unzip",
            "vim",
            "wget",
            "xz",
            "zip",
        )

        internal fun artifactForAbis(abis: List<String>): AlpineArtifact? =
            abis.firstNotNullOfOrNull { abi ->
                when (abi) {
                    "arm64-v8a" -> AlpineArtifact(
                        version = ALPINE_VERSION,
                        fileName = "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/" +
                            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
                        sha256 = "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259",
                        sizeBytes = 4_023_732L,
                    )
                    "x86_64" -> AlpineArtifact(
                        version = ALPINE_VERSION,
                        fileName = "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/" +
                            "alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
                        sha256 = "41f73e3cf5fa919b8aa5ca6b30dc48f0da2720776d7423e2a7748211456fe081",
                        sizeBytes = 3_698_422L,
                    )
                    else -> null
                }
            }

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build()
    }
}

internal data class AlpineArtifact(
    val version: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

private data class InstallerCommandResult(
    val exitCode: Int,
    val output: String,
)

private object InstallerShellRunner {
    private const val MAX_OUTPUT_BYTES = 64 * 1024

    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
        linuxRootfsPath: String? = null,
    ): InstallerCommandResult = runInterruptible(Dispatchers.IO) {
        val supervisor = ShellProcessSupervisor()
        val process = supervisor.startShellProcess(
            identity = "root",
            command = command,
            mergeStderr = true,
            environment = environment,
            linuxRootfsPath = linuxRootfsPath,
        ) ?: return@runInterruptible InstallerCommandResult(exitCode = -1, output = "")
        val output = ByteArrayOutputStream()
        val reader = thread(name = "eta-alpine-installer-output", isDaemon = true) {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = (MAX_OUTPUT_BYTES - output.size()).coerceAtLeast(0)
                            if (remaining > 0) output.write(buffer, 0, count.coerceAtMost(remaining))
                        }
                    }
                }
            }
        }
        runCatching { process.outputStream.close() }
        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                supervisor.terminateProcessTree(process)
                reader.join(1_000)
                InstallerCommandResult(exitCode = -2, output = output.text())
            } else {
                reader.join(1_000)
                InstallerCommandResult(exitCode = process.exitValue(), output = output.text())
            }
        } finally {
            if (process.isAlive) {
                supervisor.terminateAndReap(process)
            } else {
                supervisor.reapProcess(process)
            }
            supervisor.unregisterProcess(process)
        }
    }

    private fun ByteArrayOutputStream.text(): String =
        synchronized(this) { toByteArray().decodeToString().trimEnd() }
}
