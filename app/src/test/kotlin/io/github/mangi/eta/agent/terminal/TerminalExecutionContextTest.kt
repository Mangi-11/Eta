package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.mangi.eta.agent.terminal.linux.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMount

class TerminalExecutionContextTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rootChangesDefaultIdentityWithoutMovingTheWorkspace() {
        val context = TerminalExecutionContext(TerminalEnvironment.DEBIAN, null, "/eta/workspace")
        assertEquals("root", context.identityFor(TerminalEnvironment.ANDROID, true))
        assertEquals("user", context.identityFor(TerminalEnvironment.ANDROID, false))
        assertEquals("/eta/workspace", context.androidWorkspacePath)
        assertTrue(context.description(true).contains("identity=root"))
        assertTrue(context.description(false).contains("identity=user"))
    }

    @Test fun ordinaryAndroidAndProotShareThePrivateWorkspace() {
        val workspace = File(temporary.root, "terminal-user/workspace").path
        val context = TerminalExecutionContext(
            linuxEnvironment = TerminalEnvironment.DEBIAN,
            linuxRootfsPath = File(temporary.root, "terminal-user/proot/debian/rootfs").path,
            userWorkspacePath = workspace,
        )

        assertEquals(workspace, context.androidWorkspacePath)
        assertEquals(LinuxExecutionBackend.PROOT, context.linuxBackend)
        assertEquals("user", context.linuxIdentity)
        assertEquals("/workspace", context.linuxWorkspacePath)
        assertEquals(workspace, context.linuxHostWorkspacePath)
        assertFalse(context.linuxReady)
    }

    @Test fun chrootKeepsTheRootWorkspaceSeparateFromDefaultAndroid() {
        val workspace = File(temporary.root, "terminal-user/workspace").path
        val context = TerminalExecutionContext(
            linuxEnvironment = TerminalEnvironment.ALPINE,
            linuxRootfsPath = File(temporary.root, "terminal/alpine/rootfs").path,
            userWorkspacePath = workspace,
        )

        assertEquals(workspace, context.androidWorkspacePath)
        assertEquals(LinuxExecutionBackend.CHROOT, context.linuxBackend)
        assertEquals("root", context.linuxIdentity)
        assertEquals("/data/local/tmp/eta", context.linuxHostWorkspacePath)
    }

    @Test fun legacyProotKeepsTheExistingPrivateWorkspace() {
        val workspace = File(temporary.root, "terminal/workspace").path
        val context = TerminalExecutionContext(
            linuxEnvironment = TerminalEnvironment.DEBIAN,
            linuxRootfsPath = File(temporary.root, "terminal/proot/debian/rootfs").path,
            userWorkspacePath = workspace,
        )

        assertEquals(workspace, context.androidWorkspacePath)
        assertEquals("user", context.linuxIdentity)
        assertEquals(workspace, context.linuxHostWorkspacePath)
    }

    @Test fun descriptionReportsSelectedEnvironmentAndEscapesPaths() {
        val rootfs = File(temporary.root, "terminal-user/proot/debian/rootfs").apply { mkdirs() }
        File(rootfs, LinuxEnvironmentPaths.READY_MARKER).writeText("ready")
        val workspace = File(temporary.root, "work\n\"space").path
        val context = TerminalExecutionContext(TerminalEnvironment.DEBIAN, rootfs.path, workspace)

        val description = context.description(rootAvailable = false)
        assertTrue(context.linuxReady)
        assertFalse(context.linuxCommonToolsReady)
        assertTrue(context.linuxInstalledProfiles.isEmpty())
        assertTrue(description.contains("debian，backend=proot，identity=user，ready=true，common_tools=false，profiles=none"))
        assertTrue(description.contains(JSONObject.quote(workspace)))
        assertFalse(description.contains(workspace))
        assertFalse(description.contains(rootfs.path))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText("toolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}\n")
        File(rootfs, AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER).writeText("profile=${AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION}\n")
        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText("profile=${AlpineEnvironmentPaths.APK_ANALYSIS_REVISION}\n")
        assertTrue(context.linuxCommonToolsReady)
        assertEquals(listOf("python", "apk_analysis"), context.linuxInstalledProfiles)
        assertTrue(
            context.description(rootAvailable = false)
                .contains("ready=true，common_tools=true，profiles=python/apk_analysis"),
        )
    }

    @Test fun descriptionIncludesConfiguredSharedFolderMappings() {
        val context = TerminalExecutionContext(
            linuxEnvironment = TerminalEnvironment.DEBIAN,
            linuxRootfsPath = File(temporary.root, "terminal-user/proot/debian/rootfs").path,
            userWorkspacePath = "/eta/workspace",
            sharedMounts = listOf(
                SharedFolderMount(name = "Download", sourcePath = "/sdcard/Download"),
                SharedFolderMount(name = "repos", sourcePath = "/sdcard/Projects"),
            ),
        )
        val description = context.description(rootAvailable = false)
        assertTrue(description.contains("\"/workspace/mounts/Download\" <-> \"/sdcard/Download\""))
        assertTrue(description.contains("\"/workspace/mounts/repos\" <-> \"/sdcard/Projects\""))
        assertTrue(description.contains("同一物理文件夹"))
    }
}
