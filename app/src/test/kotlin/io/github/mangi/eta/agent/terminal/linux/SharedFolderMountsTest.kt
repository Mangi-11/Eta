package io.github.mangi.eta.agent.terminal.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.shellQuote

class SharedFolderMountsTest {

    @Test
    fun normalizeSourcePathRejectsNonAbsoluteAndControlChars() {
        assertNull(SharedFolderMounts.normalizeSourcePath(""))
        assertNull(SharedFolderMounts.normalizeSourcePath("sdcard/Download"))
        assertNull(SharedFolderMounts.normalizeSourcePath("/sdcard/evil\npath"))
        assertNull(SharedFolderMounts.normalizeSourcePath("/"))
        assertNull(SharedFolderMounts.normalizeSourcePath("/../escape"))
    }

    @Test
    fun normalizeSourcePathCollapsesDotsAndDuplicateSlashes() {
        assertEquals("/sdcard/Download", SharedFolderMounts.normalizeSourcePath("/sdcard//Download/"))
        assertEquals("/sdcard/Download", SharedFolderMounts.normalizeSourcePath("/sdcard/./Download"))
        assertEquals("/sdcard", SharedFolderMounts.normalizeSourcePath("/sdcard/Download/.."))
    }

    @Test
    fun validateSourceRejectsForbiddenRootsAndSubtrees() {
        val existing = emptyList<SharedFolderMount>()
        assertEquals(
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
            SharedFolderMounts.validateSource("/proc", existing),
        )
        assertEquals(
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
            SharedFolderMounts.validateSource("/data/local/tmp/eta/mounts/x", existing),
        )
        assertEquals(
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
            SharedFolderMounts.validateSource(
                "/data/user/0/app/files/terminal/alpine/rootfs",
                existing,
                extraForbiddenRoots = listOf("/data/user/0/app/files/terminal/alpine/rootfs"),
            ),
        )
        assertNull(SharedFolderMounts.validateSource("/sdcard/Download", existing))
    }

    @Test
    fun validateSourceRejectsDuplicateNormalizedPaths() {
        val existing = listOf(SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"))
        assertEquals(
            SharedFolderMounts.SourceError.DUPLICATE,
            SharedFolderMounts.validateSource("/sdcard//Download/", existing),
        )
    }

    @Test
    fun validateNameRejectsUnsafeNamesAndDuplicates() {
        val existing = listOf(SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("has space", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("a/b", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("中文名", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName(".", existing))
        assertEquals(SharedFolderMounts.NameError.INVALID, SharedFolderMounts.validateName("..", existing))
        assertEquals(
            SharedFolderMounts.NameError.INVALID,
            SharedFolderMounts.validateName("a".repeat(49), existing),
        )
        assertEquals(SharedFolderMounts.NameError.DUPLICATE, SharedFolderMounts.validateName("dl", existing))
        assertNull(SharedFolderMounts.validateName("downloads-2024_v2", existing))
    }

    @Test
    fun defaultNameFallsBackWhenBasenameHasNoSafeChars() {
        assertEquals("Download", SharedFolderMounts.defaultName("/sdcard/Download"))
        assertEquals("share", SharedFolderMounts.defaultName("/sdcard/下载"))
        assertEquals("a.b_c-d", SharedFolderMounts.defaultName("/x/a.b_c-d"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val mounts = listOf(
            SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"),
            SharedFolderMount(name = "app", sourcePath = "/data/data/com.example.app/files"),
        )
        val decoded = SharedFolderMounts.decode(SharedFolderMounts.encode(mounts))
        assertEquals(mounts, decoded)
    }

    @Test
    fun decodeSkipsMalformedEntriesAndCorruptJson() {
        assertEquals(emptyList<SharedFolderMount>(), SharedFolderMounts.decode("not-json"))
        assertEquals(emptyList<SharedFolderMount>(), SharedFolderMounts.decode(""))
        val mixed = """[{"name":"ok","source":"/sdcard/Download"},"broken",{"name":"bad name","source":"/sdcard/x"}]"""
        assertEquals(
            listOf(SharedFolderMount(name = "ok", sourcePath = "/sdcard/Download")),
            SharedFolderMounts.decode(mixed),
        )
    }

    @Test
    fun decodeCapsAtMaxMounts() {
        val mounts = (1..SharedFolderMounts.MAX_MOUNTS + 4).map {
            SharedFolderMount(name = "m$it", sourcePath = "/sdcard/d$it")
        }
        assertEquals(SharedFolderMounts.MAX_MOUNTS, SharedFolderMounts.decode(SharedFolderMounts.encode(mounts)).size)
    }

    @Test
    fun currentWithoutPreferencesReturnsEmptyAndSaveFails() {
        assertEquals(emptyList<SharedFolderMount>(), SharedFolderMounts.current(preferences = null))
        assertFalse(SharedFolderMounts.save(emptyList(), preferences = null))
    }

    @Test
    fun linuxPayloadMountsSharedFoldersIntoWorkspace() {
        val supervisor = ShellProcessSupervisor()
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
            sharedMounts = listOf(
                SharedFolderMount(name = "dl", sourcePath = "/sdcard/Download"),
                SharedFolderMount(name = "app", sourcePath = "/data/data/com.example.app/files"),
            ),
        )

        // 整个 inner script 被 shellQuote 包裹，挂载源路径两侧的单引号会被转义；按无引号片段断言。
        assertTrue(payload.contains("/sdcard/Download"))
        assertTrue(payload.contains("/data/data/com.example.app/files"))
        assertTrue(payload.contains("\$eta_rootfs/workspace/mounts/dl\" bind"))
        assertTrue(payload.contains("\$eta_rootfs/workspace/mounts/app\" bind"))
        // 共享挂载必须在 workspace bind 之后执行，目标路径才落在已挂载的 workspace 上。
        val workspaceBind = payload.indexOf("eta_mount_required /data/local/tmp/eta")
        val sharedMount = payload.indexOf("workspace/mounts/dl")
        assertTrue(workspaceBind >= 0 && sharedMount > workspaceBind)
    }

    @Test
    fun linuxPayloadWithoutSharedMountsHasNoMountsBlock() {
        val supervisor = ShellProcessSupervisor()
        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/app/files/terminal/alpine/rootfs",
            command = "ls",
        )
        assertFalse(payload.contains("workspace/mounts"))
    }
}
