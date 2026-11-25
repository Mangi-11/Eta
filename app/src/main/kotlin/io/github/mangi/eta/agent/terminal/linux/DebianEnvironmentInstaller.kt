package io.github.mangi.eta.agent.terminal.linux

import android.content.Context
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.shellQuote

internal enum class DebianEnvironmentState {
    NOT_INSTALLED,
    BASE_READY,
    READY,
}

internal data class DebianEnvironmentStatus(
    val state: DebianEnvironmentState,
    val version: String? = null,
)

internal data class DebianAptMirror(
    val id: String,
    val archiveBaseUrl: String,
    val securityBaseUrl: String,
)

internal enum class DebianInstallStage {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING_TOOLS,
    COMPLETE,
}

internal data class DebianInstallProgress(
    val stage: DebianInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface DebianInstallResult {
    data object AlreadyReady : DebianInstallResult
    data class BaseInstalled(val version: String) : DebianInstallResult
    data class ToolsInstalled(val version: String) : DebianInstallResult
    data object BaseNotInstalled : DebianInstallResult
    data class UnsupportedAbi(val abi: String) : DebianInstallResult
    data object RootUnavailable : DebianInstallResult
    data object BusyBoxUnavailable : DebianInstallResult
    data object EnvironmentUnavailable : DebianInstallResult
    data class Failed(val stage: DebianInstallStage, val code: String? = null, val message: String? = null) : DebianInstallResult
}

/** 下载固定版本的 Debian glibc rootfs；Android 内核、挂载和会话仍由 Eta 复用。 */
internal class DebianEnvironmentInstaller(
    private val context: Context,
    httpClient: OkHttpClient = VerifiedArtifactDownloader.defaultHttpClient(),
) {
    private val artifactDownloader = VerifiedArtifactDownloader(httpClient)

    fun status(): DebianEnvironmentStatus {
        val rootfs = rootfsDir()
        val version = readInstalledVersion(rootfs)
        val state = when {
            commonToolsReady(rootfs) -> DebianEnvironmentState.READY
            baseRootfsReady(rootfs) -> DebianEnvironmentState.BASE_READY
            else -> DebianEnvironmentState.NOT_INSTALLED
        }
        return DebianEnvironmentStatus(state, version)
    }

    suspend fun installBase(
        onProgress: suspend (DebianInstallProgress) -> Unit = {},
    ): DebianInstallResult {
        installMutex.lock()
        return try {
            installBaseLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    suspend fun installTools(
        onProgress: suspend (DebianInstallProgress) -> Unit = {},
    ): DebianInstallResult {
        installMutex.lock()
        return try {
            installToolsLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installBaseLocked(
        onProgress: suspend (DebianInstallProgress) -> Unit,
    ): DebianInstallResult = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir()
        if (baseRootfsReady(rootfs)) {
            return@withContext DebianInstallResult.AlreadyReady
        }
        io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository.selectBackend(
            LinuxDistribution.DEBIAN, LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
        )
        val artifact = artifactForAbis(Build.SUPPORTED_ABIS.toList())
            ?: return@withContext DebianInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            )

        onProgress(DebianInstallProgress(DebianInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }

        val archive = File(context.cacheDir, artifact.fileName + ".download")
        try {
            onProgress(DebianInstallProgress(DebianInstallStage.DOWNLOADING))
            val downloaded = artifactDownloader.download(artifact, archive) { downloadedBytes, totalBytes ->
                onProgress(DebianInstallProgress(DebianInstallStage.DOWNLOADING, downloadedBytes, totalBytes))
            }
            if (!downloaded) return@withContext DebianInstallResult.Failed(DebianInstallStage.DOWNLOADING)
            coroutineContext.ensureActive()
            onProgress(DebianInstallProgress(DebianInstallStage.EXTRACTING))
            if (!installRootfs(artifact, archive, rootfs)) {
                return@withContext DebianInstallResult.Failed(DebianInstallStage.EXTRACTING)
            }
        } catch (failure: RootlessInstallFailure) {
            return@withContext DebianInstallResult.Failed(DebianInstallStage.EXTRACTING, failure.code, failure.message)
        } catch (_: java.io.IOException) {
            return@withContext DebianInstallResult.Failed(DebianInstallStage.EXTRACTING, "INSTALL_IO_FAILED", "安装文件无法读写，请检查内部存储空间并重试")
        } catch (_: IllegalArgumentException) {
            return@withContext DebianInstallResult.Failed(DebianInstallStage.EXTRACTING, "INVALID_ARCHIVE", "环境归档无效或包含不安全路径，请重新下载后重试")
        } finally {
            archive.delete()
        }

        onProgress(DebianInstallProgress(DebianInstallStage.COMPLETE))
        DebianInstallResult.BaseInstalled(artifact.version)
    }

    private suspend fun installToolsLocked(
        onProgress: suspend (DebianInstallProgress) -> Unit,
    ): DebianInstallResult = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir()
        if (!baseRootfsReady(rootfs)) return@withContext DebianInstallResult.BaseNotInstalled
        if (commonToolsReady(rootfs)) return@withContext DebianInstallResult.AlreadyReady
        onProgress(DebianInstallProgress(DebianInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }
        onProgress(DebianInstallProgress(DebianInstallStage.INSTALLING_TOOLS))
        val result = installCommonTools(rootfs)
        if (result.exitCode != 0) {
            val (code, messageRes) = when (result.exitCode) {
                APT_REPAIR_FAILED_EXIT_CODE -> "APT_REPAIR_FAILED" to R.string.linux_debian_repair_failed
                -2 -> "INSTALL_TIMEOUT" to R.string.linux_debian_install_timeout
                else -> "APT_INSTALL_FAILED" to R.string.linux_debian_install_failed
            }
            return@withContext DebianInstallResult.Failed(
                DebianInstallStage.INSTALLING_TOOLS, code, context.getString(messageRes),
            )
        }
        onProgress(DebianInstallProgress(DebianInstallStage.COMPLETE))
        DebianInstallResult.ToolsInstalled(readInstalledVersion(rootfs) ?: DEBIAN_VERSION)
    }

    private suspend fun preflightFailure(): DebianInstallResult? = when (runPreflight().exitCode) {
        0 -> null
        PREFLIGHT_ROOT_UNAVAILABLE -> DebianInstallResult.RootUnavailable
        PREFLIGHT_BUSYBOX_UNAVAILABLE, PREFLIGHT_BUSYBOX_INCOMPLETE ->
            DebianInstallResult.BusyBoxUnavailable
        PREFLIGHT_ENVIRONMENT_UNAVAILABLE -> DebianInstallResult.EnvironmentUnavailable
        else -> DebianInstallResult.Failed(DebianInstallStage.CHECKING)
    }

    private suspend fun runPreflight(): InstallerCommandResult {
        if (LinuxEnvironmentPaths.backendOf(rootfsDir().absolutePath) == LinuxExecutionBackend.PROOT) {
            return InstallerCommandResult(if (ProotCommandBuilder.available()) 0 else PREFLIGHT_ENVIRONMENT_UNAVAILABLE, "")
        }
        if (!TerminalRuntime.rootAvailable) return InstallerCommandResult(PREFLIGHT_ROOT_UNAVAILABLE, "")
        val requiredApplets = listOf(
            "ash", "chroot", "grep", "gzip", "mount", "sha256sum", "tar", "unshare", "xz",
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
        return InstallerShellRunner.run(command, 15, TerminalEnvironment.ANDROID)
    }

    private suspend fun installRootfs(artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return RootlessLinuxInstaller.installBase(artifact, archive, rootfs, LinuxDistribution.DEBIAN)
        }
        val parent = rootfs.parentFile ?: return false
        val temporaryRootfs = File(parent, "rootfs.installing")
        val markerBody = "version=${artifact.version}\\ndistribution=debian\\nsha256=${artifact.sha256}\\n"
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
            "${'$'}eta_busybox" tar -xJf "${'$'}eta_archive" -C "${'$'}eta_temporary" --strip-components=1 || exit 67
            "${'$'}eta_busybox" mkdir -p \
              "${'$'}eta_temporary/proc" \
              "${'$'}eta_temporary/sys" \
              "${'$'}eta_temporary/dev" \
              "${'$'}eta_temporary/workspace" \
              "${'$'}eta_temporary/storage/emulated/0" \
              "${'$'}eta_temporary/data/local/tmp" \
              "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" chmod 1777 "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary/sdcard"
            "${'$'}eta_busybox" ln -s /storage/emulated/0 "${'$'}eta_temporary/sdcard"
            cat > "${'$'}eta_temporary/etc/resolv.conf" <<'ETA_RESOLV_EOF'
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 1.1.1.1
            ETA_RESOLV_EOF
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary/etc/apt/apt.conf.d" "${'$'}eta_temporary/usr/local/bin"
            cat > "${'$'}eta_temporary/etc/apt/apt.conf.d/99eta-network" <<'ETA_APT_CONFIG_EOF'
            Acquire::Retries "2";
            Acquire::http::Pipeline-Depth "0";
            Acquire::https::Pipeline-Depth "0";
            ETA_APT_CONFIG_EOF
            printf '%s\n' \
              ${shellQuote("deb ${APT_MIRRORS.first().archiveBaseUrl} trixie main")} \
              ${shellQuote("deb ${APT_MIRRORS.first().archiveBaseUrl} trixie-updates main")} \
              ${shellQuote("deb ${APT_MIRRORS.first().securityBaseUrl} trixie-security main")} > "${'$'}eta_temporary/etc/apt/sources.list"
            printf '%s\n' ${aptMirrorScriptLines()} > "${'$'}eta_temporary/usr/local/bin/eta-apt" || exit 67
            "${'$'}eta_busybox" chmod 0755 "${'$'}eta_temporary/usr/local/bin/eta-apt"
            printf ${shellQuote(markerBody)} > "${'$'}eta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" chmod 0644 "${'$'}eta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" rm -rf "${'$'}eta_rootfs"
            "${'$'}eta_busybox" mv "${'$'}eta_temporary" "${'$'}eta_rootfs" || exit 69
        """.trimIndent()
        val result = InstallerShellRunner.run(command, 180, TerminalEnvironment.ANDROID)
        AndroidAgentLogger.info(
            "Debian environment action=extract outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun installCommonTools(rootfs: File): InstallerCommandResult {
        val packages = AGENT_PACKAGES.joinToString(" ")
        val command = """
            export DEBIAN_FRONTEND=noninteractive
            mkdir -p /usr/local/bin
            printf '%s\n' ${aptMirrorScriptLines()} > /usr/local/bin/eta-apt || exit 70
            chmod 0755 /usr/local/bin/eta-apt || exit 70
            /usr/local/bin/eta-apt install $packages || exit ${'$'}?
            if command -v fdfind >/dev/null 2>&1; then ln -sf /usr/bin/fdfind /usr/local/bin/fd; fi
            cat > /${COMMON_TOOLS_MARKER} <<'ETA_TOOLSET_EOF'
            debian=$DEBIAN_VERSION
            toolset=$TOOLSET_REVISION
            profiles=agent
            ETA_TOOLSET_EOF
            chmod 0644 /${COMMON_TOOLS_MARKER}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command,
            COMMON_TOOLS_TIMEOUT_SECONDS,
            TerminalEnvironment.DEBIAN,
            rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Debian environment action=install_tools outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result
    }

    private fun rootfsDir(): File = LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.DEBIAN)

    private fun commonToolsReady(rootfs: File): Boolean {
        val marker = File(rootfs, COMMON_TOOLS_MARKER)
        if (!baseRootfsReady(rootfs) || !marker.isFile) return false
        return runCatching {
            marker.useLines { lines -> lines.any { it.trim() == "toolset=$TOOLSET_REVISION" } }
        }.getOrDefault(false)
    }

    private fun readInstalledVersion(rootfs: File): String? = runCatching {
        File(rootfs, LinuxEnvironmentPaths.READY_MARKER).readLines()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }
    }.getOrNull()

    companion object {
        private const val DEBIAN_VERSION = "13"
        private const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
        private const val TOOLSET_REVISION = 1
        private const val COMMON_TOOLS_TIMEOUT_SECONDS = 900L
        internal const val APT_REPAIR_FAILED_EXIT_CODE = 72
        private const val PREFLIGHT_ROOT_UNAVAILABLE = 40
        private const val PREFLIGHT_BUSYBOX_UNAVAILABLE = 41
        private const val PREFLIGHT_BUSYBOX_INCOMPLETE = 42
        private const val PREFLIGHT_ENVIRONMENT_UNAVAILABLE = 43
        private val installMutex = Mutex()

        internal fun baseRootfsReady(rootfs: File): Boolean =
            LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)

        internal val AGENT_PACKAGES = listOf(
            "bash", "ca-certificates", "coreutils", "curl", "diffutils", "file", "findutils",
            "gawk", "git", "grep", "gzip", "jq", "less", "openssl", "openssh-client",
            "patch", "procps", "ripgrep", "rsync", "sed", "sqlite3", "tar", "unzip", "util-linux", "wget",
            "xz-utils", "zip", "zstd", "fd-find",
        )

        /** 真机链路只保留一个国内镜像和官方源，避免慢镜像串行拖长安装。 */
        internal val APT_MIRRORS = listOf(
            DebianAptMirror(
                id = "tuna",
                archiveBaseUrl = "https://mirrors.tuna.tsinghua.edu.cn/debian",
                securityBaseUrl = "https://security.debian.org/debian-security",
            ),
            DebianAptMirror(
                id = "official",
                archiveBaseUrl = "https://deb.debian.org/debian",
                securityBaseUrl = "https://security.debian.org/debian-security",
            ),
        )

        /** 逐个尝试镜像并把成功者写回 sources.list，后续 apt 操作复用它。 */
        internal fun aptMirrorScript(): String = "#!/bin/sh\n${aptMirrorScriptBody()}"

        private fun aptMirrorScriptLines(): String =
            aptMirrorScript().trimEnd().lineSequence().joinToString(" ", transform = ::shellQuote)

        private fun aptMirrorScriptBody(): String {
            val mirrorCases = APT_MIRRORS.joinToString("\n") { mirror ->
                "${mirror.id}) eta_apt_archive=${shellQuote(mirror.archiveBaseUrl)}; " +
                    "eta_apt_security=${shellQuote(mirror.securityBaseUrl)} ;;"
            }
            return """
                set -u
                export LC_ALL=C DEBIAN_FRONTEND=noninteractive
                eta_apt() {
                  apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 "${'$'}@"
                }
                eta_apt_write_sources() {
                  case "${'$'}1" in
                    $mirrorCases
                    *) return 64 ;;
                  esac
                  printf '%s\n' "deb ${'$'}eta_apt_archive trixie main" "deb ${'$'}eta_apt_archive trixie-updates main" "deb ${'$'}eta_apt_security trixie-security main" > /etc/apt/sources.list
                }
                eta_apt_healthy() {
                  eta_audit=${'$'}(dpkg --audit) || return 1
                  if [ -n "${'$'}eta_audit" ]; then
                    printf '%s\n' "${'$'}eta_audit" >&2
                    return 1
                  fi
                  eta_apt check
                }
                eta_apt_repair() {
                  eta_apt_healthy && return 0
                  # 配置可能被缺失依赖阻塞，交给 APT 修复后必须再次配置并验证。
                  if dpkg --configure -a && eta_apt_healthy; then return 0; fi
                  eta_apt --fix-broken --no-remove install -y --no-install-recommends || return 1
                  dpkg --configure -a && eta_apt_healthy
                }
                case "${'$'}{1:-}" in
                  install) eta_action=install; shift; [ "${'$'}#" -gt 0 ] || exit 64 ;;
                  update) eta_action=update ;;
                  *) echo 'usage: eta-apt install PACKAGE... | update' >&2; exit 64 ;;
                esac
                for eta_apt_mirror in ${APT_MIRRORS.joinToString(" ") { it.id }}; do
                  eta_apt_write_sources "${'$'}eta_apt_mirror" || exit 65
                  eta_apt --error-on=any update || continue
                  [ "${'$'}eta_action" != update ] || exit 0
                  eta_apt_repair || exit $APT_REPAIR_FAILED_EXIT_CODE
                  if eta_apt install -y --no-install-recommends "${'$'}@"; then
                    eta_apt_healthy || exit $APT_REPAIR_FAILED_EXIT_CODE
                    exit 0
                  fi
                  eta_apt_healthy || exit $APT_REPAIR_FAILED_EXIT_CODE
                done
                exit 1
            """.trimIndent()
        }

        internal fun artifactForAbis(abis: List<String>): VerifiedArtifact? =
            abis.firstNotNullOfOrNull { abi ->
                when (abi) {
                    "arm64-v8a" -> debianArtifact(
                        id = "debian-trixie-aarch64-pd-v4.29.0",
                        fileName = "debian-trixie-aarch64-pd-v4.29.0.tar.xz",
                        sha256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
                        sizeBytes = 35_409_704L,
                    )
                    "x86_64" -> debianArtifact(
                        id = "debian-trixie-x86_64-pd-v4.29.0",
                        fileName = "debian-trixie-x86_64-pd-v4.29.0.tar.xz",
                        sha256 = "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
                        sizeBytes = 36_728_936L,
                    )
                    else -> null
                }
            }

        private fun debianArtifact(
            id: String,
            fileName: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact {
            val officialUrl = "https://github.com/termux/proot-distro/releases/download/v4.29.0/$fileName"
            return VerifiedArtifact(
                id = id,
                version = DEBIAN_VERSION,
                fileName = fileName,
                url = officialUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                preferredUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
            )
        }

        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://gh-proxy.com/",
        )
    }
}
