package io.github.mangi.eta.agent.terminal.files

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class AgentFileOperations(
    private val backend: FileToolBackend,
    private val textReader: FileToolTextReader = FileToolTextReader(backend),
) {

    fun statFile(path: String): String = fileToolOperation("stat_file") { base("stat_file").put("file", backend.stat(backend.resolve(path)).toJson()) }

    fun readFile(
        path: String,
        offsetBytes: Long = 0,
        maxBytes: Int = 16_000,
        startLine: Int? = null,
        maxLines: Int = 200,
        expectedRevision: String? = null,
    ): String = fileToolOperation("read_file") {
        require(offsetBytes >= 0 && maxBytes > 0 && maxLines > 0)
        require(startLine == null || (startLine > 0 && offsetBytes == 0L))
        val stat = regularFile(path, expectedRevision)
        var offset = offsetBytes
        var line: Int? = if (offset == 0L) 1 else null
        if (startLine != null && startLine > 1) {
            var scanBytes = 0
            while (line!! < startLine && offset < stat.sizeBytes && MAX_LINE_SCAN_BYTES - scanBytes >= 4) {
                val chunk = textReader.read(stat, offset, minOf(16_000, MAX_LINE_SCAN_BYTES - scanBytes))
                var consumedChars = 0
                while (consumedChars < chunk.text.length && line < startLine) {
                    val newline = chunk.text.indexOf('\n', consumedChars)
                    if (newline < 0) { consumedChars = chunk.text.length; break }
                    consumedChars = newline + 1
                    line++
                }
                val consumed = chunk.text.substring(0, consumedChars).toByteArray(Charsets.UTF_8).size
                offset += consumed
                scanBytes += consumed
            }
            if (line < startLine && offset < stat.sizeBytes) {
                verifyUnchanged(stat)
                return@fileToolOperation base("read_file").put("path", stat.path).put("revision", stat.revision)
                    .put("content", "").put("bytes_read", 0).put("offset_bytes", offsetBytes)
                    .put("next_offset_bytes", offset).put("next_line", line)
                    .put("start_line_reached", false).put("truncated", true).put("stop_reason", "scan_limit")
            }
        }
        val chunk = textReader.read(stat, offset, maxBytes.coerceAtMost(16_000))
        var end = chunk.text.length
        var newlineCount = 0
        for (index in chunk.text.indices) {
            if (chunk.text[index] == '\n') {
                newlineCount++
                if (newlineCount == maxLines.coerceAtMost(2_000)) { end = index + 1; break }
            }
        }
        val content = chunk.text.substring(0, end)
        val consumed = content.toByteArray(Charsets.UTF_8).size
        val nextOffset = offset + consumed
        val truncated = nextOffset < stat.sizeBytes
        val newlines = content.count { it == '\n' }
        verifyUnchanged(stat)
        base("read_file").put("path", stat.path).put("revision", stat.revision)
            .put("size_bytes", stat.sizeBytes).put("offset_bytes", offset).put("bytes_read", consumed)
            .put("next_offset_bytes", nextOffset).put("content", content)
            .put("start_line", line ?: JSONObject.NULL).put("next_line", line?.plus(newlines) ?: JSONObject.NULL)
            .put("line_count", newlines + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0)
            .put("line_truncated", truncated && content.isNotEmpty() && !content.endsWith('\n'))
            .put("start_line_reached", startLine == null || line!! >= startLine)
            .put("truncated", truncated).put("stop_reason", if (!truncated) "eof" else if (end < chunk.text.length) "line_limit" else "byte_limit")
    }

    fun listDirectory(
        path: String,
        showHidden: Boolean = false,
        limit: Int = 80,
        offset: Int = 0,
        expectedRevision: String? = null,
    ): String = fileToolOperation("list_directory") {
        require(limit > 0 && offset >= 0)
        val resolved = backend.resolve(path)
        val page = backend.list(resolved, offset, limit.coerceAtMost(200), expectedRevision)
        val entries = JSONArray()
        val result = base("list_directory").put("path", resolved).put("revision", page.revision)
            .put("entries", entries).put("offset", offset).put("next_offset", page.nextOffset)
            .put("truncated", false).put("has_more", false).put("stop_reason", "output_limit")
        // 先保留最长状态字段的空间，分页必须停在尚未发布的条目前，不能截断完整 JSON。
        var outputChars = result.toString().length
        if (outputChars > MAX_DIRECTORY_JSON_CHARS) throw FileToolException("FILE_OUTPUT_LIMIT", "目录路径和元数据超过输出预算")
        var consumed = 0
        var outputLimited = false
        for (entry in page.entries) {
            backend.checkActive()
            if (!showHidden && entry.name.startsWith('.')) { consumed++; continue }
            val item = entry.toJson()
            val cost = item.toString().length + if (entries.length() == 0) 0 else 1
            if (outputChars + cost > MAX_DIRECTORY_JSON_CHARS) {
                if (entries.length() == 0) throw FileToolException("FILE_OUTPUT_LIMIT", "单个目录条目超过输出预算")
                outputLimited = true
                break
            }
            entries.put(item)
            outputChars += cost
            consumed++
        }
        val hasMore = page.hasMore || consumed < page.entries.size
        val nextOffset = if (consumed == page.entries.size) page.nextOffset else offset + consumed
        result.put("next_offset", nextOffset).put("truncated", hasMore).put("has_more", hasMore)
            .put("stop_reason", when { !hasMore -> "eof"; outputLimited -> "output_limit"; else -> "entry_limit" })
    }

    fun writeFile(path: String, content: String, append: Boolean = false, expectedRevision: String? = null): String =
        fileToolOperation("write_file") {
            val bytes = encodeBounded(content)
            val result = backend.write(backend.resolve(path), bytes, append, expectedRevision)
            base("write_file").put("path", result.stat.path).put("revision", result.stat.revision)
                .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
                .put("size_bytes", result.stat.sizeBytes).put("atomic", result.atomic)
        }

    fun editFile(
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean = false,
        expectedRevision: String? = null,
    ): String = fileToolOperation("edit_file") {
        if (oldText.isEmpty()) throw FileToolException("EMPTY_MATCH", "old_text 不能为空；创建文件请使用 write_file")
        encodeBounded(oldText)
        encodeBounded(newText)
        val stat = regularFile(path, expectedRevision)
        if (stat.sizeBytes > MAX_EDIT_BYTES) throw FileToolException("FILE_TOO_LARGE", "精确编辑仅支持不超过 512 KiB 的文本文件")
        val buffer = ByteArrayOutputStream(stat.sizeBytes.toInt())
        var offset = 0L
        while (offset < stat.sizeBytes) {
            val chunk = textReader.read(stat, offset, minOf(16_000L, stat.sizeBytes - offset).toInt())
            val bytes = chunk.text.toByteArray(Charsets.UTF_8)
            buffer.write(bytes)
            offset += chunk.bytesConsumed
        }
        val originalBytes = buffer.toByteArray()
        val original = originalBytes.toString(Charsets.UTF_8)
        var count = 0
        var position = 0
        while (true) {
            backend.checkActive()
            val found = original.indexOf(oldText, position)
            if (found < 0) break
            count++
            if (!replaceAll && count > 1) throw FileToolException("AMBIGUOUS_MATCH", "old_text 命中多处，请提供更多上下文或明确 replace_all=true")
            position = found + oldText.length
        }
        if (count == 0) throw FileToolException("MATCH_NOT_FOUND", "未找到 old_text，文件未修改；请重新读取并使用原文")
        val estimatedChars = original.length.toLong() + count.toLong() * (newText.length - oldText.length)
        if (estimatedChars > MAX_EDIT_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "替换后的文本超过编辑大小限制")
        val replacement = encodeBounded(original.replace(oldText, newText))
        verifyUnchanged(stat)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(originalBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val result = backend.write(stat.path, replacement, append = false, expectedRevision = stat.revision, expectedSha256 = sha256)
        base("edit_file").put("path", result.stat.path).put("revision", result.stat.revision)
            .put("replacements", count).put("bytes_written", replacement.size).put("atomic", result.atomic)
    }

    fun globFiles(path: String, pattern: String, limit: Int = 80, cursor: String? = null, includeHidden: Boolean = false): String =
        FileToolSearch(backend).glob(path, pattern, limit, cursor, includeHidden)

    fun grepFiles(
        path: String,
        query: String,
        glob: String = "**/*",
        limit: Int = 50,
        cursor: String? = null,
        includeHidden: Boolean = false,
        caseSensitive: Boolean = true,
    ): String = FileToolSearch(backend).grep(path, query, glob, limit, cursor, includeHidden, caseSensitive)

    private fun regularFile(path: String, expectedRevision: String?): FileToolStat {
        val stat = backend.stat(backend.resolve(path))
        if (stat.kind != FileToolKind.FILE) throw FileToolException("NOT_REGULAR_FILE", "目标不是普通文件")
        if (expectedRevision != null && stat.revision != expectedRevision) throw FileToolException("FILE_CHANGED", "文件已变化，请重新读取")
        return stat
    }

    private fun verifyUnchanged(stat: FileToolStat) {
        if (backend.stat(stat.path).revision != stat.revision) throw FileToolException("FILE_CHANGED", "文件在操作期间已变化，请重试")
    }

    private fun encodeBounded(content: String): ByteArray {
        if (content.length > MAX_EDIT_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "文本超过 512 KiB 大小限制")
        if (!Charsets.UTF_8.newEncoder().canEncode(content)) throw FileToolException("NOT_UTF8_TEXT", "文本包含无效 Unicode 字符")
        return content.toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_EDIT_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "文本超过 512 KiB 大小限制")
        }
    }

    private fun base(tool: String): JSONObject = JSONObject().put("ok", true).put("tool", tool)
        .put("environment", backend.environment).put("identity", backend.identity)

    private companion object {
        const val MAX_EDIT_BYTES = 512 * 1024
        const val MAX_LINE_SCAN_BYTES = 256 * 1024
        const val MAX_DIRECTORY_JSON_CHARS = 16_000
    }
}

internal fun FileToolStat.toJson(): JSONObject = JSONObject().put("path", path).put("name", name)
    .put("kind", kind.wireName).put("size_bytes", sizeBytes).put("revision", revision)

internal inline fun fileToolOperation(tool: String, block: () -> JSONObject): String = try {
    block().toString()
} catch (error: FileToolException) {
    JSONObject().put("ok", false).put("tool", tool).put("code", error.code).put("message", error.message).toString()
} catch (_: NoSuchFileException) {
    JSONObject().put("ok", false).put("tool", tool).put("code", "FILE_NOT_FOUND").put("message", "文件或目录不存在").toString()
} catch (_: AccessDeniedException) {
    JSONObject().put("ok", false).put("tool", tool).put("code", "FILE_ACCESS_DENIED").put("message", "文件访问未授权").toString()
} catch (_: SecurityException) {
    JSONObject().put("ok", false).put("tool", tool).put("code", "FILE_ACCESS_DENIED").put("message", "文件访问未授权").toString()
} catch (_: IllegalArgumentException) {
    JSONObject().put("ok", false).put("tool", tool).put("code", "INVALID_ARGUMENT").put("message", "文件工具参数无效").toString()
} catch (_: IOException) {
    JSONObject().put("ok", false).put("tool", tool).put("code", "FILE_IO_ERROR").put("message", "文件读写失败，请检查当前授权和文件状态").toString()
}
