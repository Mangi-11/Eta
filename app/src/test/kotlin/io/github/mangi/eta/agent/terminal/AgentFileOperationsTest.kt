package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentFileOperationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    @Test fun bytePaginationNeverSkipsContentHiddenByOutputLimit() {
        val file = temporary.newFile("large.txt").apply { writeText("a".repeat(20_000)) }
        val operations = operations()
        val first = JSONObject(operations.readFile(file.name, maxBytes = 65_536))
        assertTrue(first.toString(), first.getBoolean("truncated"))
        assertEquals(16_000, first.getInt("bytes_read"))
        val second = JSONObject(operations.readFile(file.name, offsetBytes = first.getLong("next_offset_bytes")))
        assertFalse(second.getBoolean("truncated"))
        assertEquals(file.readText(), first.getString("content") + second.getString("content"))
    }

    @Test fun chineseAndSupplementaryCharactersSurviveByteBoundaries() {
        val original = "a".repeat(15_999) + "汉😀字"
        val file = temporary.newFile("utf8.txt").apply { writeText(original) }
        val first = JSONObject(operations().readFile(file.name))
        assertEquals(15_999, first.getInt("bytes_read"))
        val second = JSONObject(operations().readFile(file.name, offsetBytes = first.getLong("next_offset_bytes")))
        assertEquals(original, first.getString("content") + second.getString("content"))
        assertEquals(file.length(), second.getLong("next_offset_bytes"))
    }

    @Test fun invalidUtf8AndBinaryFilesHaveExplicitFailures() {
        val text = temporary.newFile("utf8.txt").apply { writeText("中文") }
        assertEquals("NOT_UTF8_TEXT", JSONObject(operations().readFile(text.name, offsetBytes = 1)).getString("code"))
        val binary = temporary.newFile("binary.dat").apply { writeBytes(byteArrayOf(65, 0, 66)) }
        assertEquals("BINARY_FILE", JSONObject(operations().readFile(binary.name)).getString("code"))
    }

    @Test fun customWorkingDirectoryDoesNotRedefineHome() {
        val cwd = temporary.newFolder("nested")
        File(temporary.root, "config.txt").writeText("home")
        File(cwd, "config.txt").writeText("cwd")
        val operations = AgentFileOperations(LocalFileToolBackend(
            workspace = cwd, home = temporary.root,
        ))
        assertEquals("home", JSONObject(operations.readFile("~/config.txt")).getString("content"))
        assertEquals("cwd", JSONObject(operations.readFile("config.txt")).getString("content"))
    }

    @Test fun lineRangeAndLargeSingleLineReturnRecoverableOffsets() {
        val file = temporary.newFile("lines.txt").apply { writeText("一\n二\n" + "长".repeat(8_000) + "\n四") }
        val second = JSONObject(operations().readFile(file.name, startLine = 2, maxLines = 1))
        assertEquals("二\n", second.getString("content"))
        assertEquals(3, second.getInt("next_line"))
        val longLine = JSONObject(operations().readFile(file.name, startLine = 3))
        assertTrue(longLine.getBoolean("line_truncated"))
        assertEquals(3, longLine.getInt("next_line"))
        val remainder = JSONObject(operations().readFile(file.name, offsetBytes = longLine.getLong("next_offset_bytes")))
        assertEquals("长".repeat(8_000) + "\n四", longLine.getString("content") + remainder.getString("content"))
    }

    @Test fun directoryPagesEnumerateEveryEntryAndRejectChangedDirectory() {
        repeat(241) { File(temporary.root, "entry-$it").writeText("") }
        val operations = operations()
        val names = mutableSetOf<String>()
        var offset = 0
        var revision: String? = null
        do {
            val page = JSONObject(operations.listDirectory("", limit = 80, offset = offset, expectedRevision = revision))
            assertTrue(page.toString(), page.getBoolean("ok"))
            revision = page.getString("revision")
            val entries = page.getJSONArray("entries")
            for (index in 0 until entries.length()) assertTrue(names.add(entries.getJSONObject(index).getString("name")))
            offset = page.getInt("next_offset")
        } while (page.getBoolean("has_more"))
        assertEquals(241, names.size)
        val oldTime = temporary.root.lastModified()
        Files.setLastModifiedTime(temporary.root.toPath(), FileTime.fromMillis(oldTime + 10_000))
        assertEquals("STALE_CURSOR", JSONObject(operations.listDirectory("", offset = offset, expectedRevision = revision)).getString("code"))
    }

    @Test fun deepDirectoryResumesAfterOutputBudgetWithoutLosingOrRepeatingNames() {
        var directory = temporary.root
        repeat(5) { level -> directory = File(directory, "directory-$level-" + "x".repeat(70)).apply { mkdir() } }
        val expected = (0 until 90).map { "文件-" + "x".repeat(80) + "-$it.txt" }.toSet()
        expected.forEach { File(directory, it).writeText("") }
        val operations = operations()
        var offset = 0
        var revision: String? = null
        val actual = mutableListOf<String>()
        var outputLimited = false
        var rounds = 0
        do {
            val raw = operations.listDirectory(directory.path, limit = 200, offset = offset, expectedRevision = revision)
            assertTrue("JSON exceeded output budget: ${raw.length}", raw.length <= 16_000)
            val result = JSONObject(raw)
            assertTrue(result.toString(), result.getBoolean("ok"))
            val entries = result.getJSONArray("entries")
            for (index in 0 until entries.length()) actual += entries.getJSONObject(index).getString("name")
            if (result.getBoolean("has_more")) assertTrue(result.getInt("next_offset") > offset)
            offset = result.getInt("next_offset")
            revision = result.getString("revision")
            outputLimited = outputLimited || result.getString("stop_reason") == "output_limit"
            assertTrue(++rounds < 20)
        } while (result.getBoolean("has_more"))
        assertTrue(outputLimited)
        assertEquals(expected, actual.toSet())
        assertEquals(expected.size, actual.size)
    }

    @Test fun editRequiresUniqueOldTextAndKeepsFileOnFailure() {
        val file = temporary.newFile("edit.txt").apply { writeText("one one") }
        val operations = operations()
        assertEquals("MATCH_NOT_FOUND", JSONObject(operations.editFile(file.name, "missing", "x")).getString("code"))
        assertEquals("AMBIGUOUS_MATCH", JSONObject(operations.editFile(file.name, "one", "x")).getString("code"))
        assertEquals("one one", file.readText())
        val result = JSONObject(operations.editFile(file.name, "one", "二", replaceAll = true))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getBoolean("atomic"))
        assertEquals(2, result.getInt("replacements"))
        assertEquals("二 二", file.readText())
    }

    @Test fun staleRevisionAndOldContentDigestPreventOverwrite() {
        val file = temporary.newFile("edit.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        val initial = backend.stat(file.path)
        file.writeText("different")
        assertEquals("FILE_CHANGED", JSONObject(AgentFileOperations(backend).editFile(file.name, "different", "new", expectedRevision = initial.revision)).getString("code"))
        try {
            backend.write(file.path, "new".toByteArray(), false, expectedSha256 = "0".repeat(64))
            throw AssertionError("digest mismatch accepted")
        } catch (error: FileToolException) { assertEquals("FILE_CHANGED", error.code) }
        assertEquals("different", file.readText())
    }

    @Test fun atomicOverwriteRetainsExistingPosixPermissions() {
        val file = temporary.newFile("permissions.txt").apply { writeText("original") }
        val permissions = PosixFilePermissions.fromString("rwxr-----")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        val result = JSONObject(operations().writeFile(file.name, "updated"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(result.getBoolean("atomic"))
        assertEquals("updated", file.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun symlinksResolveToAccessibleFilesOutsideWorkingDirectory() {
        val workspace = temporary.newFolder("workspace")
        val outside = temporary.newFile("outside.txt").apply { writeText("outside") }
        Files.createSymbolicLink(File(workspace, "escape").toPath(), outside.toPath())
        val result = JSONObject(AgentFileOperations(LocalFileToolBackend(workspace)).readFile("escape"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("outside", result.getString("content"))
        assertEquals(outside.canonicalPath, result.getString("path"))
    }

    @Test fun cancellationPropagatesInsteadOfBecomingFileError() {
        val file = temporary.newFile("cancel.txt").apply { writeText("data") }
        val operations = AgentFileOperations(LocalFileToolBackend(temporary.root, isCancelled = { true }))
        try {
            operations.readFile(file.name)
            throw AssertionError("cancelled read accepted")
        } catch (_: InterruptedException) { }
    }

    @Test fun editAndLimitCheckSurviveDecoderThatBuffersIncompleteTailBytes() {
        val original = "a".repeat(15_999) + "中文结尾"
        val file = temporary.newFile("icu_boundary.txt").apply { writeText(original) }
        val backend = LocalFileToolBackend(temporary.root)
        val reader = FileToolTextReader(backend) { IcuBufferingUtf8Decoder() }
        val stat = backend.stat(file.path)

        val firstChunk = reader.read(stat, offset = 0, maxBytes = 16_000)
        assertEquals("a".repeat(15_999), firstChunk.text)
        assertEquals(15_999, firstChunk.bytesConsumed)
        assertFalse(firstChunk.eof)

        val tiny = runCatching {
            reader.read(stat, offset = 15_999, maxBytes = 1)
        }.exceptionOrNull() as FileToolException
        assertEquals("READ_LIMIT_TOO_SMALL", tiny.code)

        val operations = AgentFileOperations(backend, reader)
        val editResult = JSONObject(operations.editFile(file.name, "中文结尾", "已替换"))
        assertTrue(editResult.toString(), editResult.getBoolean("ok"))
        assertEquals(1, editResult.getInt("replacements"))
        assertEquals("a".repeat(15_999) + "已替换", file.readText())
    }

    /** 模拟 Android ICU 解码器在非 EOF 块尾把半个 UTF-8 字符吞入内部缓冲的行为。 */
    private class IcuBufferingUtf8Decoder : CharsetDecoder(Charsets.UTF_8, 1f, 1f) {
        private val delegate = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        private var bufferedCount = 0

        override fun implReset() {
            delegate.reset()
            bufferedCount = 0
        }

        override fun decodeLoop(input: ByteBuffer, out: CharBuffer): CoderResult {
            val result = delegate.decode(input, out, false)
            if (result.isUnderflow && input.hasRemaining()) {
                val tail = input.remaining()
                if (bufferedCount + tail < 4) {
                    bufferedCount += tail
                    input.position(input.limit())
                }
            }
            return result
        }
    }
}
