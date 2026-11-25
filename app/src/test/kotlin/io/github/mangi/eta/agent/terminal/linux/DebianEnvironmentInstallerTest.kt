package io.github.mangi.eta.agent.terminal.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebianEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesPinnedTrixieIntegrityMetadata() {
        val artifact = DebianEnvironmentInstaller.artifactForAbis(
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(artifact)
        assertEquals("13", artifact.version)
        assertEquals("debian-trixie-aarch64-pd-v4.29.0.tar.xz", artifact.fileName)
        assertTrue(artifact.url.contains("termux/proot-distro/releases/download/v4.29.0"))
        assertEquals(1, artifact.preferredUrls.size)
        assertTrue(artifact.preferredUrls.single().startsWith("https://gh-proxy.com/"))
        assertTrue(artifact.preferredUrls.all { it.contains(artifact.fileName) })
        assertEquals(64, artifact.sha256.length)
        assertEquals(35_409_704L, artifact.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        assertNull(DebianEnvironmentInstaller.artifactForAbis(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun readinessUsesInstallerMarkerOnly() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val marker = File(rootfs, LinuxEnvironmentPaths.READY_MARKER)

        assertFalse(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        marker.writeText("version=13\n")
        assertTrue(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
    }

    @Test
    fun baseToolsetContainsGlibcAndAgentEssentials() {
        val packages = DebianEnvironmentInstaller.AGENT_PACKAGES

        assertTrue(packages.containsAll(listOf("bash", "coreutils", "git", "jq", "ripgrep", "sqlite3", "xz-utils")))
        assertFalse(packages.contains("nodejs"))
        assertFalse(packages.contains("npm"))
        assertFalse(packages.contains("python3"))
        assertFalse(packages.contains("python3-pip"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun aptMirrorsKeepDomesticCandidatesBeforeOfficialFallback() {
        assertEquals(listOf("tuna", "official"), DebianEnvironmentInstaller.APT_MIRRORS.map { it.id })
        assertTrue(DebianEnvironmentInstaller.APT_MIRRORS.dropLast(1).all { it.archiveBaseUrl.startsWith("https://mirrors.") })
        assertEquals(
            "https://security.debian.org/debian-security",
            DebianEnvironmentInstaller.APT_MIRRORS.first().securityBaseUrl,
        )
        val script = DebianEnvironmentInstaller.aptMirrorScript()
        assertTrue(script.contains("apt-get -o Acquire::Retries=2"))
        assertFalse(script.contains("mirrors.ustc.edu.cn"))
        assertFalse(script.contains("mirrors.aliyun.com"))
    }

    @Test
    fun aptMirrorScriptIsValidPosixShell() {
        val process = ProcessBuilder("sh", "-n").start()
        process.outputStream.use { output ->
            output.write(DebianEnvironmentInstaller.aptMirrorScript().toByteArray())
        }
        assertEquals(0, process.waitFor())
    }
}
