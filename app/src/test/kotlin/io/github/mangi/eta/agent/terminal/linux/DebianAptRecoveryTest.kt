package io.github.mangi.eta.agent.terminal.linux

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebianAptRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun healthyPackagesDoNotNeedRecovery() {
        val fixture = fixture()
        fixture.install().assertSuccess()
        assertFalse(fixture.calls.any { it.contains("--configure") || it.contains("--fix-broken") })
    }

    @Test
    fun brokenDependenciesAreDetectedEvenWithAnEmptyAudit() {
        val fixture = fixture()
        fixture.flag("broken")
        fixture.install().assertSuccess()
        assertFalse(fixture.file("broken").exists())
        assertTrue(fixture.calls.any { it.contains("--fix-broken --no-remove") })
    }

    @Test
    fun pendingConfigurationCanCompleteWithoutDependencyRepair() {
        val fixture = fixture()
        fixture.flag("unconfigured")
        fixture.install().assertSuccess()
        assertFalse(fixture.file("unconfigured").exists())
        assertFalse(fixture.calls.any { it.contains("--fix-broken") })
    }

    @Test
    fun interruptedInstallIsRecoverableOnRetry() {
        val fixture = fixture()
        fixture.flag("interrupt-once")
        assertNotEquals(0, fixture.install().exitCode)
        assertTrue(fixture.file("broken").exists())
        fixture.install().assertSuccess()
        assertFalse(fixture.file("broken").exists())
        assertFalse(fixture.file("unconfigured").exists())
        assertEquals(2, fixture.calls.count { it == "dpkg --configure -a" })
    }

    @Test
    fun failedRepairStopsBeforeInstallingRequestedPackagesOrSwitchingMirrors() {
        val fixture = fixture()
        fixture.flag("broken")
        fixture.flag("repair-fails")
        assertEquals(DebianEnvironmentInstaller.APT_REPAIR_FAILED_EXIT_CODE, fixture.install().exitCode)
        assertEquals(1, fixture.calls.count { it.endsWith(" update") })
        assertFalse(fixture.calls.any { it.endsWith(" curl") })
        assertTrue(fixture.file("broken").exists())
    }

    @Test
    fun inconsistentStateAfterInstallIsNotReportedAsSuccessOrRetriedWithAnotherMirror() {
        val fixture = fixture()
        fixture.flag("post-install-unconfigured")
        assertEquals(DebianEnvironmentInstaller.APT_REPAIR_FAILED_EXIT_CODE, fixture.install().exitCode)
        assertEquals(1, fixture.calls.count { it.endsWith(" update") })
    }

    @Test
    fun downloadFailureWithHealthyPackagesStillUsesTheOfficialFallback() {
        val fixture = fixture()
        fixture.flag("domestic-download-fails")
        fixture.install().assertSuccess()
        assertEquals(2, fixture.calls.count { it.endsWith(" update") })
        assertTrue(fixture.file("sources.list").readText().contains("https://deb.debian.org/debian"))
    }

    private fun fixture() = AptFixture(temporaryFolder.newFolder())
    private fun InstallerCommandResult.assertSuccess() = assertEquals(output, 0, exitCode)

    private class AptFixture(private val root: File) {
        val calls: List<String> get() = file("calls").takeIf { it.exists() }?.readLines().orEmpty()

        init {
            file("bin").mkdirs()
            executable("dpkg", """
                printf 'dpkg %s\n' "${'$'}*" >> "${'$'}ETA_FIXTURE/calls"
                case "${'$'}1" in
                  --audit)
                    if [ -e "${'$'}ETA_FIXTURE/unconfigured" ]; then echo 'package is unpacked but not configured'; fi ;;
                  --configure)
                    if [ -e "${'$'}ETA_FIXTURE/broken" ] && [ -e "${'$'}ETA_FIXTURE/unconfigured" ]; then
                      echo 'dpkg: dependency problems prevent configuration' >&2; exit 1
                    fi
                    rm -f "${'$'}ETA_FIXTURE/unconfigured" ;;
                  *) exit 99 ;;
                esac
            """)
            executable("apt-get", """
                printf 'apt %s\n' "${'$'}*" >> "${'$'}ETA_FIXTURE/calls"
                case " ${'$'}* " in
                  *' update '*) ;;
                  *' check '*)
                    if [ -e "${'$'}ETA_FIXTURE/broken" ]; then echo 'E: Unmet dependencies' >&2; exit 100; fi ;;
                  *' --fix-broken '*)
                    case " ${'$'}* " in *' --no-remove '*) ;; *) exit 99 ;; esac
                    [ ! -e "${'$'}ETA_FIXTURE/repair-fails" ] || exit 100
                    rm -f "${'$'}ETA_FIXTURE/broken" ;;
                  *' install '*)
                    [ ! -e "${'$'}ETA_FIXTURE/broken" ] || exit 100
                    if [ -e "${'$'}ETA_FIXTURE/interrupt-once" ]; then
                      rm -f "${'$'}ETA_FIXTURE/interrupt-once"
                      touch "${'$'}ETA_FIXTURE/broken" "${'$'}ETA_FIXTURE/unconfigured"
                      kill -TERM "${'$'}PPID"; exit 143
                    fi
                    if [ -e "${'$'}ETA_FIXTURE/domestic-download-fails" ]; then
                      case "${'$'}(cat "${'$'}ETA_FIXTURE/sources.list")" in *mirrors.tuna*) exit 100 ;; esac
                    fi
                    if [ -e "${'$'}ETA_FIXTURE/post-install-unconfigured" ]; then touch "${'$'}ETA_FIXTURE/unconfigured"; fi ;;
                  *) exit 99 ;;
                esac
            """)
        }

        fun file(path: String): File = File(root, path)
        fun flag(path: String) = file(path).writeText("")

        fun install(): InstallerCommandResult {
            val script = file("eta-apt").apply {
                writeText(DebianEnvironmentInstaller.aptMirrorScript().replace("/etc/apt/sources.list", file("sources.list").absolutePath))
            }
            val output = file("output")
            val process = ProcessBuilder("sh", script.absolutePath, "install", "curl")
                .redirectErrorStream(true).redirectOutput(output)
                .apply {
                    environment()["PATH"] = "${file("bin").absolutePath}:${System.getenv("PATH")}"
                    environment()["ETA_FIXTURE"] = root.absolutePath
                }.start()
            try {
                assertTrue("package script did not finish", process.waitFor(10, TimeUnit.SECONDS))
                return InstallerCommandResult(process.exitValue(), output.readText())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

        private fun executable(name: String, body: String) {
            file("bin/$name").apply {
                writeText("#!/bin/sh\nset -eu\n" + body.trimIndent() + "\n")
                check(setExecutable(true))
            }
        }
    }
}
