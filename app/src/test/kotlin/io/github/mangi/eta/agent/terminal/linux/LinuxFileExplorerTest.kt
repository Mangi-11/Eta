package io.github.mangi.eta.agent.terminal.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxFileExplorerTest {

    private val rootfsDir = File("/data/user/0/io.github.mangi.eta/files/terminal/alpine/rootfs")

    @Test
    fun resolveHostPathNormalizesRootAndBlank() {
        assertEquals(rootfsDir.path, LinuxFileExplorer.resolveHostPath(rootfsDir, "/"))
        assertEquals(rootfsDir.path, LinuxFileExplorer.resolveHostPath(rootfsDir, ""))
        assertEquals(rootfsDir.path, LinuxFileExplorer.resolveHostPath(rootfsDir, "   "))
    }

    @Test
    fun resolveHostPathRejectsEscapeAboveRoot() {
        assertNull(LinuxFileExplorer.resolveHostPath(rootfsDir, "/../escape"))
        assertNull(LinuxFileExplorer.resolveHostPath(rootfsDir, "/a/../../b"))
    }

    @Test
    fun resolveHostPathRejectsRelativeAndControlChars() {
        assertNull(LinuxFileExplorer.resolveHostPath(rootfsDir, "etc/passwd"))
        assertNull(LinuxFileExplorer.resolveHostPath(rootfsDir, "etc"))
        assertNull(LinuxFileExplorer.resolveHostPath(rootfsDir, "/etc/evil\npath"))
    }

    @Test
    fun resolveHostPathCollapsesDotsAndDuplicateSlashes() {
        assertEquals(
            File(rootfsDir, "/etc/passwd").path,
            LinuxFileExplorer.resolveHostPath(rootfsDir, "/etc//passwd"),
        )
        assertEquals(
            File(rootfsDir, "/etc/passwd").path,
            LinuxFileExplorer.resolveHostPath(rootfsDir, "/etc/./passwd"),
        )
        assertEquals(
            File(rootfsDir, "/a/c").path,
            LinuxFileExplorer.resolveHostPath(rootfsDir, "/a/b/../c"),
        )
    }

    @Test
    fun parseStatOutputReadsTypesSizesAndNames() {
        val entries = LinuxFileExplorer.parseStatOutput(
            """
            directory|4096|1700000000|etc
            regular file|123|1700000001|hello world.txt
            regular file|5|1700000002|中文名
            """.trimIndent(),
        )
        assertEquals(3, entries.size)
        val dir = entries.first { it.name == "etc" }
        assertTrue(dir.isDir)
        assertEquals(4096L, dir.sizeBytes)
        assertEquals(1700000000L, dir.mtimeEpochSeconds)
        val spaced = entries.first { it.name == "hello world.txt" }
        assertFalse(spaced.isDir)
        assertEquals(123L, spaced.sizeBytes)
        assertTrue(entries.any { it.name == "中文名" })
    }

    @Test
    fun parseStatOutputToleratesPipesInFileName() {
        val entries = LinuxFileExplorer.parseStatOutput("regular file|5|1700000003|weird|name")
        assertEquals(1, entries.size)
        assertEquals("weird|name", entries[0].name)
    }

    @Test
    fun parseStatOutputSkipsMalformedAndGlobResidueLines() {
        val entries = LinuxFileExplorer.parseStatOutput(
            """
            garbage
            directory|abc|1700000000|bad-size
            regular file|5|1700000000|*
            regular file|5|1700000000|.[!.]*
            regular file|5|1700000000|ok
            """.trimIndent(),
        )
        assertEquals(listOf("ok"), entries.map { it.name })
    }

    @Test
    fun parseStatOutputSortsDirectoriesBeforeFilesByName() {
        val entries = LinuxFileExplorer.parseStatOutput(
            """
            regular file|1|1700000000|aaa
            directory|4096|1700000000|zzz
            directory|4096|1700000000|abc
            regular file|1|1700000000|bbb
            """.trimIndent(),
        )
        assertEquals(listOf("abc", "zzz", "aaa", "bbb"), entries.map { it.name })
    }
}
