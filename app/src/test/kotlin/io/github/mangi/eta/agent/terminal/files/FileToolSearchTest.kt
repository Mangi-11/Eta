package io.github.mangi.eta.agent.terminal.files

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileToolSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    @Test fun globContinuesWithoutDuplicatesAndCanIncludeHiddenFiles() {
        temporary.newFile("a.kt")
        temporary.newFile(".hidden.kt")
        temporary.newFolder("nested").also { File(it, "b.kt").writeText("") }
        temporary.newFile("unrelated.txt")
        val operations = operations()
        var cursor: String? = null
        val paths = mutableListOf<String>()
        var rounds = 0
        do {
            val result = JSONObject(operations.globFiles("", "**/*.kt", limit = 1, cursor = cursor))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) paths += matches.getJSONObject(index).getString("relative_path")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 10)
        } while (cursor != null)
        assertEquals(setOf("a.kt", "nested/b.kt"), paths.toSet())
        assertEquals(2, paths.size)
        val withHidden = JSONObject(operations.globFiles("", "**/*.kt", includeHidden = true))
        assertEquals(3, withHidden.getInt("count"))
    }

    @Test fun grepFindsLiteralAcrossUtf8ChunksWithoutRepeatingLongLine() {
        temporary.newFile("text.txt").writeText("x".repeat(15_998) + "中文needle" + "x".repeat(20_000) + "needle\nneedle\n")
        val result = JSONObject(operations().grepFiles("", "中文needle"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(1, result.getInt("count"))
        assertEquals(15_998, result.getJSONArray("matches").getJSONObject(0).getInt("match_offset_bytes"))
        val all = JSONObject(operations().grepFiles("", "needle"))
        assertEquals(2, all.getInt("count"))
    }

    @Test fun byteBudgetCursorResumesInLargeFileWithoutRescanningItsPrefix() {
        temporary.newFile("large.txt").writeText("x".repeat(300_000) + "target\n")
        val first = JSONObject(operations().grepFiles("", "target"))
        assertEquals(0, first.getInt("count"))
        assertEquals("byte_limit", first.getString("stop_reason"))
        assertTrue(first.getBoolean("truncated"))
        val second = JSONObject(operations().grepFiles("", "target", cursor = first.getString("next_cursor")))
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertEquals(1, second.getInt("count"))
        assertEquals(300_000, second.getJSONArray("matches").getJSONObject(0).getInt("match_offset_bytes"))
        assertTrue(second.getInt("bytes_scanned") < 50_000)
    }

    @Test fun resultLimitCanContinueWithinOneFile() {
        temporary.newFile("lines.txt").writeText("hit one\nhit two\nhit three\n")
        val lines = mutableListOf<Long>()
        var cursor: String? = null
        var rounds = 0
        do {
            val result = JSONObject(operations().grepFiles("", "hit", limit = 1, cursor = cursor))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) lines += matches.getJSONObject(index).getLong("line")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 8)
        } while (cursor != null)
        assertEquals(listOf(1L, 2L, 3L), lines)
    }

    @Test fun skippedBinaryAndSymlinkAreReportedAsIncompleteCoverage() {
        temporary.newFile("binary.dat").writeBytes(byteArrayOf(1, 0, 2))
        val file = temporary.newFile("text.txt").apply { writeText("needle") }
        Files.createSymbolicLink(File(temporary.root, "link.txt").toPath(), file.toPath())
        val result = JSONObject(operations().grepFiles("", "needle"))
        assertEquals(1, result.getInt("count"))
        assertEquals(2, result.getInt("total_skipped"))
        assertFalse(result.getBoolean("complete"))
        assertTrue(result.isNull("next_cursor"))
    }

    @Test fun cursorRejectsChangedDirectoryAndDifferentQuery() {
        temporary.newFile("a.txt").writeText("hit")
        temporary.newFile("b.txt").writeText("hit")
        val first = JSONObject(operations().grepFiles("", "hit", limit = 1))
        val cursor = first.getString("next_cursor")
        assertEquals("INVALID_CURSOR", JSONObject(operations().grepFiles("", "other", cursor = cursor)).getString("code"))
        Files.setLastModifiedTime(temporary.root.toPath(), FileTime.fromMillis(temporary.root.lastModified() + 10_000))
        assertEquals("STALE_CURSOR", JSONObject(operations().grepFiles("", "hit", cursor = cursor)).getString("code"))
    }

    @Test fun globalBackendFailureIsNotReportedAsSuccessfulPartialSearch() {
        temporary.newFile("text.txt").writeText("needle")
        val local = LocalFileToolBackend(temporary.root)
        for (code in listOf("ROOT_REQUIRED", "FILE_TIMEOUT", "FILE_PROCESS_FAILED", "CANCELLED")) {
            val backend = object : FileToolBackend by local {
                override fun readBytes(path: String, offset: Long, limit: Int): ByteArray =
                    throw FileToolException(code, "文件操作不可用")
            }
            val result = JSONObject(AgentFileOperations(backend).grepFiles("", "needle"))
            assertFalse(result.getBoolean("ok"))
            assertEquals(code, result.getString("code"))
        }
    }

    @Test fun globCursorCannotBeReusedForLiteralNullGrepQuery() {
        temporary.newFile("a.txt").writeText("null")
        temporary.newFile("b.txt").writeText("null")
        val first = JSONObject(operations().globFiles("", "**/*", limit = 1))
        val cursor = first.getString("next_cursor")
        val second = JSONObject(operations().grepFiles("", "null", cursor = cursor))
        assertFalse(second.getBoolean("ok"))
        assertEquals("INVALID_CURSOR", second.getString("code"))
    }

    @Test fun softTimeLimitKeepsProgressAndAUsableCursorAcrossSmallFiles() {
        repeat(5) { temporary.newFile("entry-$it.txt").writeText("hit") }
        val local = LocalFileToolBackend(temporary.root)
        var time = 0L
        val backend = object : FileToolBackend by local {
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
                time += 3_000_000_000L
                return local.readBytes(path, offset, limit)
            }
        }
        val search = FileToolSearch(backend, nanoTime = { time })
        val paths = mutableListOf<String>()
        var cursor: String? = null
        var timedPage = false
        var rounds = 0
        do {
            val result = JSONObject(search.grep("", "hit", "**/*", 200, cursor, false, true))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) paths += matches.getJSONObject(index).getString("relative_path")
            if (result.getString("stop_reason") == "time_limit") {
                timedPage = true
                assertTrue(result.getBoolean("partial"))
                assertTrue(result.getInt("bytes_scanned") > 0)
            }
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 10)
        } while (cursor != null)
        assertTrue(timedPage)
        assertEquals((0 until 5).map { "entry-$it.txt" }.toSet(), paths.toSet())
        assertEquals(5, paths.size)
    }

    @Test(timeout = 1_000) fun globWildcardsDoNotEnterRegexBacktrackingAndQuestionMatchesOneCodePoint() {
        assertFalse(FileToolGlob("**/".repeat(100) + "missing").matches("part/".repeat(100) + "file"))
        assertTrue(FileToolGlob("?.txt").matches("😀.txt"))
        assertFalse(FileToolGlob("*.txt").matches("nested/a.txt"))
        assertTrue(FileToolGlob("**/*.txt").matches("a.txt"))
        assertTrue(FileToolGlob("**/*.txt").matches("nested/a.txt"))
    }
}
