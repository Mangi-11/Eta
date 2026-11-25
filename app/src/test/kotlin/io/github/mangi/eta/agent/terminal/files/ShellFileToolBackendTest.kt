package io.github.mangi.eta.agent.terminal.files

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.mangi.eta.agent.terminal.OneShotShellResult
import io.github.mangi.eta.agent.terminal.SHELL_STDIN_FAILED_EXIT_CODE
import io.github.mangi.eta.agent.terminal.shellQuote

class ShellFileToolBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvePreservesQuotedPathsAndUsesEnvironmentHome() {
        val backend = newBackend { command, _, _ ->
            runShell("readlink() { printf '%s\\n' \"\$3\"; }; $command")
        }
        listOf(
            "/workspace/plain", "/workspace/a b", "/workspace/a'b", "/workspace/line\nend\n",
            "/workspace/\$(printf injected)", "/workspace/`printf injected`", "/workspace/*?[x]",
        ).forEach { path -> assertEquals(path, backend.resolve(path)) }
        assertEquals("/workspace/-leading-option", backend.resolve("-leading-option"))
        assertEquals("/guest-home", backend.resolve("~"))
        assertEquals("/guest-home/a'b", backend.resolve("~/a'b"))
    }

    @Test
    fun resolveDoesNotCollapseDotDotBeforeFollowingRemoteSymlink() {
        var requested = ""
        val backend = newBackend { command, _, _ ->
            requested = command
            result("/elsewhere/file\n")
        }
        assertEquals("/elsewhere/file", backend.resolve("link/../file"))
        assertTrue(requested, requested.contains("'/workspace/link/../file'"))
        val missing = newBackend { _, _, _ -> result(exit = 1) }
        assertEquals("/workspace/link/../new/file", missing.resolve("link/../new/file"))
        assertFailure("FILE_COMMAND_FAILED") { newBackend { _, _, _ -> result(exit = 127) }.resolve("file") }
        assertFailure("INVALID_PATH") { backend.resolve("bad\u0000path") }
    }

    @Test
    fun statPassesRealNewlineFormatAndPathAsSeparateLiteralArguments() {
        val file = temporaryFolder.newFile("-a'b\n\$(printf injected)`printf injected`")
        val arguments = File(temporaryFolder.root, "stat-arguments")
        val backend = newBackend { command, _, _ ->
            val stub = "stat() { printf '%s\\000' \"\$@\" > ${shellQuote(arguments.absolutePath)}; " +
                "printf '7\\n1:2:7:10:11:modified:changed\\n'; }; "
            runShell(stub + command)
        }
        val stat = backend.stat(file.absolutePath)
        assertEquals(FileToolKind.FILE, stat.kind)
        assertEquals(7L, stat.sizeBytes)
        val args = arguments.readText().split('\u0000').dropLast(1)
        assertEquals(listOf("-c", "%s\n%d:%i:%s:%Y:%Z:%y:%z", "--", file.absolutePath), args)
    }

    @Test
    fun statRejectsInvalidOrTruncatedMetadata() {
        listOf(
            "unknown\n7\n1:2:7:10:11:modified:changed\n",
            "file\n-1\n1:2:-1:10:11:modified:changed\n",
            "file\n7\\n1:2:7:10:11:modified:changed\n",
            "file\n7\n",
            "file\n7\n1:2:8:10:11:modified:changed\n",
            "file\n7\n?:2:7:10:11:modified:changed\n",
        ).forEach { output ->
            assertFailure("FILE_METADATA_INVALID") { newBackend { _, _, _ -> result(output) }.stat("/workspace/a") }
        }
        assertFailure("UNSUPPORTED_ENCODING") {
            newBackend { _, _, _ -> OneShotShellResult(0, byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf()) }.stat("/workspace/a")
        }
        assertFailure("FILE_OUTPUT_LIMIT") {
            newBackend { _, _, _ -> result(metadata(), truncated = true) }.stat("/workspace/a")
        }
    }

    @Test
    fun blockReadsReturnExactUnalignedByteRangeAndHandleEof() {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val backend = newBackend { command, input, maxBytes ->
            assertEquals(null, input)
            assertTrue(command, command.contains("dd if='/workspace/a' bs=4096"))
            val blockOffset = Regex("skip=([0-9]+)").find(command)!!.groupValues[1].toLong()
            val count = Regex("count=([0-9]+)").find(command)!!.groupValues[1].toInt()
            assertEquals(count * 4096, maxBytes)
            val start = (blockOffset * 4096).coerceAtMost(bytes.size.toLong()).toInt()
            val end = (start + maxBytes).coerceAtMost(bytes.size)
            OneShotShellResult(0, bytes.copyOfRange(start, end), byteArrayOf())
        }
        listOf(0L to 4, 4095L to 6, 4096L to 4096, 8193L to 7000, 19_998L to 8, 30_000L to 8).forEach { (offset, limit) ->
            val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
            assertArrayEquals(bytes.copyOfRange(start, (start + limit).coerceAtMost(bytes.size)), backend.readBytes("/workspace/a", offset, limit))
        }
        assertFailure("INVALID_ARGUMENT") { backend.readBytes("/workspace/a", -1, 2) }
        assertFailure("INVALID_ARGUMENT") { backend.readBytes("/workspace/a", 0, 0) }
    }

    @Test
    fun listKeepsNulDelimitedNamesAndUsesStablePagination() {
        val paths = listOf("/workspace/z", "/workspace/a\n'b", "/workspace/-first")
        var batch = 0
        val backend = newBackend { command, input, _ ->
            when {
                "find " in command -> result(paths.joinToString("\u0000", postfix = "\u0000"))
                command == "sh -s" -> {
                    val selected = if (batch++ == 0) paths.sorted().take(2) else listOf("/workspace/z")
                    selected.forEach { assertTrue(input!!.decodeToString().contains(shellQuote(it))) }
                    result(selected.joinToString("") { metadata() + '\u0000' })
                }
                "stat -c" in command && "if [ -L '/workspace' ]" in command -> result(metadata("directory"))
                else -> throw AssertionError("目录条目没有使用批量查询")
            }
        }
        val first = backend.list("/workspace", 0, 2)
        assertEquals(paths.sorted().take(2), first.entries.map { it.path })
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        val last = backend.list("/workspace", first.nextOffset, 2, first.revision)
        assertEquals(listOf("/workspace/z"), last.entries.map { it.path })
        assertFalse(last.hasMore)
        assertEquals(3, last.nextOffset)
    }

    @Test
    fun fullDirectoryPageUsesFourExecutionsAndStdinForLongPaths() {
        val root = "/" + List(6) { "directory".repeat(20) }.joinToString("/")
        val paths = List(200) { "$root/entry-${it.toString().padStart(3, '0')}\n'\$(printf literal)" }
        var calls = 0
        var batches = 0
        val backend = newBackend { command, input, _ ->
            calls++
            when {
                "find " in command -> result(paths.joinToString("\u0000", postfix = "\u0000"))
                command == "sh -s" -> {
                    batches++
                    assertTrue(input!!.size > 131_072)
                    val script = input.decodeToString()
                    paths.forEach { assertTrue(script.contains(shellQuote(it))) }
                    result(paths.joinToString("") { metadata() + '\u0000' })
                }
                else -> result(metadata("directory"))
            }
        }
        val page = backend.list(root, 0, 200)
        assertEquals(paths, page.entries.map { it.path })
        assertEquals(4, calls)
        assertEquals(1, batches)
        assertFalse(page.hasMore)
    }

    @Test
    fun batchedStatScriptKeepsNewlinesAndQuotesOutOfRecordFraming() {
        val files = listOf("line\n'a", "\$(printf injected)", "-option").map { temporaryFolder.newFile(it) }
        val paths = files.map { it.absolutePath }.sorted()
        val backend = newBackend { command, input, _ ->
            when {
                "find " in command -> result(paths.joinToString("\u0000", postfix = "\u0000"))
                command == "sh -s" -> {
                    val stub = "stat() { printf '7\\n1:2:7:10:11:modified:changed\\n'; };\n"
                    runShell("sh -s", stub.toByteArray() + input!!)
                }
                else -> result(metadata("directory"))
            }
        }
        assertEquals(paths, backend.list(temporaryFolder.root.absolutePath, 0, 200).entries.map { it.path })
    }

    @Test
    fun batchRejectsMissingExtraAndUnterminatedMetadataRecords() {
        listOf(metadata(), metadata() + '\u0000', (metadata() + '\u0000').repeat(3)).forEach { batchOutput ->
            val backend = newBackend { command, _, _ ->
                when {
                    "find " in command -> result("/workspace/a\u0000/workspace/b\u0000")
                    command == "sh -s" -> result(batchOutput)
                    else -> result(metadata("directory"))
                }
            }
            assertFailure("FILE_METADATA_INVALID") { backend.list("/workspace", 0, 2) }
        }
    }

    @Test
    fun listDoesNotTreatTruncatedOrChangedEnumerationAsComplete() {
        listOf(result("/workspace/a"), result("/workspace/a\u0000", truncated = true)).forEach { listing ->
            val backend = newBackend { command, _, _ ->
                if ("find " in command) listing else result(metadata("directory"))
            }
            assertFailure(if (listing.outputTruncated) "FILE_OUTPUT_LIMIT" else "DIRECTORY_TOO_LARGE") {
                backend.list("/workspace", 0, 2)
            }
        }
        var stats = 0
        val changing = newBackend { command, _, _ ->
            if ("find " in command) result()
            else result(metadata("directory", version = (++stats).toString()))
        }
        assertFailure("STALE_CURSOR") { changing.list("/workspace", 0, 2) }
        var calls = 0
        val stale = newBackend { _, _, _ -> calls++; result(metadata("directory")) }
        assertFailure("STALE_CURSOR") { stale.list("/workspace", 0, 2, "old-revision") }
        assertEquals(1, calls)
    }

    @Test
    fun writesCheckHashBeforeRedirectionAndKeepReplacementOutOfScript() {
        val file = temporaryFolder.newFile("-a'b\n\$(printf injected)").apply { writeText("original") }
        val expectedHash = "a".repeat(64)
        val replacement = "replacement\n\$(printf should_stay_literal)".toByteArray()
        var match = false
        var writeCommand = ""
        val backend = newBackend { command, input, _ ->
            if (input == null) result(metadata(size = file.length()))
            else {
                writeCommand = command
                assertArrayEquals(replacement, input)
                assertFalse(command.contains("should_stay_literal"))
                val actualHash = if (match) expectedHash else "b".repeat(64)
                val stub = "sha256sum() { cat >/dev/null; printf '%s  -\\n' '$actualHash'; }; "
                runShell(stub + command, input)
            }
        }
        assertFailure("FILE_CHANGED") { backend.write(file.absolutePath, replacement, false, expectedSha256 = expectedHash) }
        assertEquals("original", file.readText())
        assertTrue(writeCommand.indexOf("sha256sum") < writeCommand.indexOf("cat >"))
        match = true
        val written = backend.write(file.absolutePath, replacement, false, expectedSha256 = expectedHash)
        assertFalse(written.atomic)
        assertArrayEquals(replacement, file.readBytes())
        backend.write(file.absolutePath, replacement, true)
        assertArrayEquals(replacement + replacement, file.readBytes())
    }

    @Test
    fun staleRevisionAndInvalidHashNeverSubmitWrite() {
        var calls = 0
        val backend = newBackend { _, input, _ ->
            calls++
            assertEquals(null, input)
            result(metadata())
        }
        assertFailure("FILE_CHANGED") { backend.write("/workspace/a", byteArrayOf(1), false, expectedRevision = "stale") }
        assertEquals(1, calls)
        assertFailure("INVALID_ARGUMENT") { backend.write("/workspace/a", byteArrayOf(1), false, expectedSha256 = "invalid") }
        assertEquals(1, calls)
    }

    @Test
    fun cancellationAndAuthorizationChangesPropagateBeforeAndAfterExecution() {
        var cancellation: String? = "CANCELLED"
        var calls = 0
        val backend = ShellFileToolBackend("linux", "user", "/workspace", "/guest-home", ensureActive = {
            cancellation?.let { throw FileToolException(it, "操作不可继续") }
        }) { _, _, _ ->
            calls++
            cancellation = "ROOT_REQUIRED"
            result("secret")
        }
        assertFailure("CANCELLED") { backend.readBytes("/workspace/a", 0, 3) }
        assertEquals(0, calls)
        cancellation = null
        assertFailure("ROOT_REQUIRED") { backend.readBytes("/workspace/a", 0, 3) }
        assertEquals(1, calls)
        assertFailure("CANCELLED") {
            newBackend { _, _, _ -> result(exit = -3, truncated = true) }.readBytes("/workspace/a", 0, 3)
        }
        assertFailure("FILE_TIMEOUT") { newBackend { _, _, _ -> result(exit = -2) }.readBytes("/workspace/a", 0, 3) }
        assertFailure("FILE_INPUT_FAILED") {
            newBackend { _, _, _ -> result(exit = SHELL_STDIN_FAILED_EXIT_CODE) }
                .write("/workspace/a", byteArrayOf(1), false)
        }
        assertFailure("FILE_ACCESS_DENIED") { newBackend { _, _, _ -> result(exit = 43) }.stat("/workspace/a") }
        assertFailure("FILE_HASH_UNAVAILABLE") {
            newBackend { _, _, _ -> result(exit = 45) }.write("/workspace/a", byteArrayOf(1), false, expectedSha256 = "a".repeat(64))
        }
    }

    private fun newBackend(execute: (String, ByteArray?, Int) -> OneShotShellResult) =
        ShellFileToolBackend("linux", "user", "/workspace", "/guest-home", execute = execute)

    private fun metadata(kind: String = "file", size: Long = 7, version: String = "1") =
        "$kind\n$size\n1:2:$size:10:11:modified$version:changed$version\n"

    private fun result(output: String = "", exit: Int = 0, truncated: Boolean = false) =
        OneShotShellResult(exit, output.toByteArray(), byteArrayOf(), outputTruncated = truncated)

    private fun assertFailure(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (failure: FileToolException) {
            assertEquals(code, failure.code)
        }
    }

    private fun runShell(command: String, input: ByteArray? = null): OneShotShellResult {
        // 这里验证 Shell 脚本及文件保护合同；用文件供给输入，避免提前退出与父进程写管道竞争。
        val inputFile = temporaryFolder.newFile().apply { writeBytes(input ?: byteArrayOf()) }
        val process = ProcessBuilder("sh", "-c", command)
            .directory(temporaryFolder.root)
            .redirectInput(inputFile)
            .start()
        val output = process.inputStream.readBytes()
        val error = process.errorStream.readBytes()
        assertTrue("测试 Shell 没有正常结束", process.waitFor(5, TimeUnit.SECONDS))
        return OneShotShellResult(process.exitValue(), output, error)
    }
}
