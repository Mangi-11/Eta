package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlpineEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesFirstSupportedAbiWithPinnedIntegrityMetadata() {
        val artifact = AlpineEnvironmentInstaller.artifactForAbis(
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(artifact)
        assertEquals("3.24.1", artifact.version)
        assertTrue(artifact.fileName.endsWith("-aarch64.tar.gz"))
        assertTrue(artifact.url.startsWith("https://dl-cdn.alpinelinux.org/alpine/v3.24/"))
        assertEquals(
            listOf(
                "https://mirrors.aliyun.com/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz",
            ),
            artifact.preferredUrls,
        )
        assertEquals(64, artifact.sha256.length)
        assertEquals(4_023_732L, artifact.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        assertNull(AlpineEnvironmentInstaller.artifactForAbis(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun readinessUsesInstallerMarkerAndTracksCommonToolsSeparately() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val ready = File(rootfs, AlpineEnvironmentPaths.READY_MARKER)

        assertFalse(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        ready.writeText("version=3.24.1\n")
        assertTrue(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        assertFalse(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText("3.24.1\n")
        assertFalse(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText(
            "alpine=3.24.1\ntoolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}\nprofiles=agent\n",
        )
        assertTrue(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))
    }

    @Test
    fun baseToolsetContainsAgentEssentialsWithoutPythonOrInteractiveEditors() {
        val packages = AlpineEnvironmentInstaller.AGENT_PACKAGES

        assertTrue(packages.containsAll(listOf("ripgrep", "fd", "diffutils", "patch", "rsync")))
        assertFalse(packages.contains("python3"))
        assertFalse(packages.contains("uv"))
        assertFalse(packages.contains("nodejs"))
        assertFalse(packages.contains("npm"))
        assertFalse(packages.contains("vim"))
        assertFalse(packages.contains("nano"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun apkMirrorsKeepDomesticCandidatesBeforeOfficialFallback() {
        assertEquals(
            listOf(
                "https://mirrors.aliyun.com/alpine",
                "https://dl-cdn.alpinelinux.org/alpine",
            ),
            AlpineEnvironmentInstaller.APK_MIRROR_BASE_URLS,
        )
        val script = AlpineEnvironmentInstaller.apkMirrorScript()
        assertTrue(script.indexOf("mirrors.aliyun.com") < script.indexOf("dl-cdn.alpinelinux.org"))
        assertFalse(script.contains("mirrors.ustc.edu.cn"))
        assertFalse(script.contains("mirrors.tuna.tsinghua.edu.cn"))
        assertTrue(script.contains("apk update && apk add --no-cache"))
    }

    @Test
    fun apkMirrorScriptIsValidPosixShell() {
        val process = ProcessBuilder("sh", "-n").start()
        process.outputStream.use { output ->
            output.write(AlpineEnvironmentInstaller.apkMirrorScript().toByteArray())
        }
        assertEquals(0, process.waitFor())
    }

    @Test
    fun packageProfilesCoverExpectedToolchains() {
        val alpinePython = LinuxPackageProfiles.PYTHON.spec(LinuxDistribution.ALPINE)
        val debianPython = LinuxPackageProfiles.PYTHON.spec(LinuxDistribution.DEBIAN)
        assertTrue(alpinePython.packages.isEmpty())
        assertEquals(ManagedLinuxTool.UV, alpinePython.managedTool)
        assertTrue(alpinePython.setupScript.orEmpty().contains("uv python install --default --force"))
        assertTrue(debianPython.packages.isEmpty())
        assertEquals(ManagedLinuxTool.UV, debianPython.managedTool)
        assertTrue(debianPython.setupScript.orEmpty().contains("UV_PYTHON_BIN_DIR=/usr/local/bin"))
        assertTrue(
            LinuxPackageProfiles.NODE.spec(LinuxDistribution.ALPINE).packages
                .containsAll(listOf("nodejs-current", "npm")),
        )
        assertEquals(
            ManagedLinuxTool.NODE,
            LinuxPackageProfiles.NODE.spec(LinuxDistribution.DEBIAN).managedTool,
        )
        // Node 官方 arm64 二进制依赖 libatomic.so.1，Debian 规格必须补装 libatomic1。
        assertTrue(
            LinuxPackageProfiles.NODE.spec(LinuxDistribution.DEBIAN).packages.contains("libatomic1"),
        )
        LinuxDistribution.entries.forEach { distribution ->
            assertTrue(LinuxPackageProfiles.SSH.spec(distribution).packages.isNotEmpty())
        }
        LinuxPackageProfiles.ALL.forEach { profile ->
            assertTrue(profile.markerName.startsWith(".eta-"))
            profile.specs.values.forEach { spec ->
                assertEquals(spec.packages.distinct(), spec.packages)
            }
        }
        assertEquals(LinuxPackageProfiles.ALL.map { it.id }.distinct(), LinuxPackageProfiles.ALL.map { it.id })
    }

    @Test
    fun packageProfileReadinessUsesItsOwnMarker() {
        val rootfs = temporaryFolder.newFolder("profile-rootfs")
        val profile = LinuxPackageProfiles.PYTHON

        assertFalse(linuxPackageProfileReady(rootfs, profile))
        File(rootfs, profile.markerName).writeText("profile=0\n")
        assertFalse(linuxPackageProfileReady(rootfs, profile))
        File(rootfs, profile.markerName).writeText("profile=${profile.revision}\n")
        assertTrue(linuxPackageProfileReady(rootfs, profile))
    }

    @Test
    fun pinnedUvAndNodeArtifactsUseLatestStableReleaseMetadata() {
        val alpineUv = PinnedLinuxToolArtifacts.artifactFor(
            ManagedLinuxTool.UV,
            LinuxDistribution.ALPINE,
            listOf("arm64-v8a"),
        )
        val debianUv = PinnedLinuxToolArtifacts.artifactFor(
            ManagedLinuxTool.UV,
            LinuxDistribution.DEBIAN,
            listOf("arm64-v8a"),
        )
        val debianNode = PinnedLinuxToolArtifacts.artifactFor(
            ManagedLinuxTool.NODE,
            LinuxDistribution.DEBIAN,
            listOf("arm64-v8a"),
        )

        requireNotNull(alpineUv)
        requireNotNull(debianUv)
        requireNotNull(debianNode)
        assertEquals("0.12.7", alpineUv.version)
        assertTrue(alpineUv.fileName.contains("musl"))
        assertEquals("0.12.7", debianUv.version)
        assertTrue(debianUv.fileName.contains("gnu"))
        assertEquals("26.8.1", debianNode.version)
        assertTrue(debianNode.url.startsWith("https://nodejs.org/"))
        assertTrue(debianNode.preferredUrls.single().startsWith("https://cdn.npmmirror.com/"))
        assertNull(
            PinnedLinuxToolArtifacts.artifactFor(
                ManagedLinuxTool.NODE,
                LinuxDistribution.ALPINE,
                listOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun apkAnalysisReadinessUsesCurrentMarker() {
        val rootfs = temporaryFolder.newFolder("analysis-rootfs")

        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText("profile=0\n")
        assertFalse(linuxApkAnalysisReady(rootfs))

        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText(
            "profile=${AlpineEnvironmentPaths.APK_ANALYSIS_REVISION}\n",
        )
        assertTrue(linuxApkAnalysisReady(rootfs))
    }
}
