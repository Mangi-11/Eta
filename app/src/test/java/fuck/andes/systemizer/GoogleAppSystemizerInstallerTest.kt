package fuck.andes.systemizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAppSystemizerInstallerTest {

    @Test
    fun detectRootManagerUsesSuVersionForKernelSu() {
        val manager = GoogleAppSystemizerInstaller.detectRootManager(
            suVersionProbe = RootProbeResult(exitCode = 0, output = "v3.1.0:KernelSU"),
        )

        assertEquals(RootManager.KERNEL_SU, manager)
    }

    @Test
    fun detectRootManagerUsesSuVersionForMagisk() {
        val manager = GoogleAppSystemizerInstaller.detectRootManager(
            suVersionProbe = RootProbeResult(exitCode = 0, output = "30.7:MAGISKSU"),
        )

        assertEquals(RootManager.MAGISK, manager)
    }

    @Test
    fun detectRootManagerReturnsUnsupportedForUnknownSuVersion() {
        val manager = GoogleAppSystemizerInstaller.detectRootManager(
            suVersionProbe = RootProbeResult(exitCode = 0, output = "unknown"),
        )

        assertEquals(RootManager.UNSUPPORTED, manager)
    }

    @Test
    fun buildInstallCommandUsesOfficialCommandForEachSupportedRootManager() {
        val zipPath = "/data/user/0/fuck.andes/cache/googlequicksearchbox-systemizer.zip"

        assertEquals(
            "magisk --install-module '$zipPath'",
            GoogleAppSystemizerInstaller.buildInstallCommand(RootManager.MAGISK, zipPath),
        )
        assertEquals(
            "ksud module install '$zipPath'",
            GoogleAppSystemizerInstaller.buildInstallCommand(RootManager.KERNEL_SU, zipPath),
        )
    }

    @Test
    fun kernelSuMetamoduleSupportRequiresMetamodulePath() {
        assertTrue(
            GoogleAppSystemizerInstaller.hasKernelSuMetamoduleSupport(
                existingPaths = setOf("/data/adb/metamodule"),
            )
        )
        assertFalse(
            GoogleAppSystemizerInstaller.hasKernelSuMetamoduleSupport(existingPaths = emptySet())
        )
        assertFalse(
            GoogleAppSystemizerInstaller.hasKernelSuMetamoduleSupport(
                existingPaths = setOf("/data/adb/metamodule/metamount.sh"),
            )
        )
    }

    @Test
    fun preflightBlocksKernelSuWithoutMetamoduleSupport() {
        assertEquals(
            InstallPreflight.KERNEL_SU_METAMODULE_MISSING,
            GoogleAppSystemizerInstaller.preflight(RootManager.KERNEL_SU, hasKernelSuMetamoduleSupport = false),
        )
        assertEquals(
            InstallPreflight.READY,
            GoogleAppSystemizerInstaller.preflight(RootManager.KERNEL_SU, hasKernelSuMetamoduleSupport = true),
        )
        assertEquals(
            InstallPreflight.READY,
            GoogleAppSystemizerInstaller.preflight(RootManager.MAGISK, hasKernelSuMetamoduleSupport = false),
        )
    }
}
