package io.github.mangi.eta.agent.terminal.files

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** 游标保留有限深度的目录栈和当前文本位置；每轮只扫描有界条目与字节。 */
internal class FileToolSearch(
    private val backend: FileToolBackend,
    private val nanoTime: () -> Long = System::nanoTime,
    private val softTimeLimitNanos: Long = 5_000_000_000L,
) {
    fun glob(path: String, pattern: String, limit: Int, cursor: String?, includeHidden: Boolean): String =
        search(path, pattern, null, limit, cursor, includeHidden, true)

    fun grep(path: String, query: String, glob: String, limit: Int, cursor: String?, includeHidden: Boolean, caseSensitive: Boolean): String =
        search(path, glob, query, limit, cursor, includeHidden, caseSensitive)

    private fun search(
        path: String,
        pattern: String,
        query: String?,
        limit: Int,
        cursor: String?,
        includeHidden: Boolean,
        caseSensitive: Boolean,
    ): String = fileToolOperation(if (query == null) "glob_files" else "grep_files") {
        require(limit > 0)
        if (query != null && (query.isEmpty() || query.length > 512 || query.contains('\n') || query.contains('\r'))) {
            throw FileToolException("INVALID_QUERY", "搜索词必须为 1 到 512 个字符的单行普通文本")
        }
        val root = backend.resolve(path)
        val rootStat = backend.stat(root)
        if (rootStat.kind != FileToolKind.DIRECTORY) throw FileToolException("NOT_DIRECTORY", "搜索范围必须是目录")
        val matcher = FileToolGlob(pattern)
        val signature = digest(JSONObject().put("tool", if (query == null) "glob_files" else "grep_files")
            .put("root", root).put("pattern", pattern).put("query", query ?: JSONObject.NULL)
            .put("include_hidden", includeHidden).put("case_sensitive", caseSensitive)
            .put("environment", backend.environment).put("identity", backend.identity).toString())
        val state = if (cursor.isNullOrBlank()) State(mutableListOf(Frame("", 0, rootStat.revision))) else decodeCursor(cursor, signature)
        state.frames.forEach { frame ->
            if (backend.stat(join(root, frame.relative)).revision != frame.revision) {
                throw FileToolException("STALE_CURSOR", "搜索目录已变化，请重新开始搜索")
            }
        }
        val startedAt = nanoTime()
        val timeExpired = { nanoTime() - startedAt >= softTimeLimitNanos }
        val matches = JSONArray()
        val skipped = JSONArray()
        val skippedBefore = state.skipped
        var entryBudget = MAX_ENTRIES
        var byteBudget = MAX_BYTES
        var outputChars = 0
        val maxResults = limit.coerceAtMost(200)
        var stopReason = "complete"
        val reader = FileToolTextReader(backend)
        val directoryPages = mutableMapOf<String, CachedPage>()
        while (matches.length() < maxResults && outputChars < MAX_MATCH_CHARS) {
            backend.checkActive()
            if (timeExpired() && (entryBudget < MAX_ENTRIES || byteBudget < MAX_BYTES)) { stopReason = "time_limit"; break }
            if (state.pending == null) {
                if (entryBudget <= 0) { stopReason = "entry_limit"; break }
                val next = nextFile(root, state, matcher, includeHidden, entryBudget, skipped, directoryPages, timeExpired)
                entryBudget -= next.visited
                state.pending = next.file
                if (next.file == null) {
                    if (state.frames.isNotEmpty()) stopReason = if (next.timeLimited) "time_limit" else "entry_limit"
                    break
                }
            }
            val pending = state.pending!!
            if (query == null) {
                val item = JSONObject().put("path", join(root, pending.relative)).put("relative_path", pending.relative)
                val cost = item.toString().length
                if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) { stopReason = "output_limit"; break }
                matches.put(item)
                outputChars += cost
                state.pending = null
                continue
            }
            if (timeExpired() && entryBudget < MAX_ENTRIES) { stopReason = "time_limit"; break }
            if (byteBudget < 4) { stopReason = "byte_limit"; break }
            val filePath = join(root, pending.relative)
            try {
                val stat = backend.stat(filePath)
                if (stat.revision != pending.revision) throw FileToolException("STALE_CURSOR", "搜索中的文件已变化，请重新开始搜索")
                val chunk = reader.read(stat, pending.offset, minOf(16_000, byteBudget))
                if (chunk.text.isEmpty()) { state.pending = null; continue }
                var position = 0
                var paused = false
                while (position < chunk.text.length) {
                    backend.checkActive()
                    if (position > 0 && timeExpired()) { stopReason = "time_limit"; paused = true; break }
                    val newline = chunk.text.indexOf('\n', position)
                    val end = if (newline >= 0) newline else chunk.text.length
                    val segment = chunk.text.substring(position, end)
                    val combined = pending.tail + segment
                    val found = if (pending.lineMatched) -1 else combined.indexOf(query, ignoreCase = !caseSensitive)
                    if (found >= 0) {
                        val snippetStart = (found - 100).coerceAtLeast(0)
                        val snippetEnd = (found + query.length + 100).coerceAtMost(combined.length)
                        val item = JSONObject().put("path", filePath).put("relative_path", pending.relative)
                            .put("line", pending.line).put("text", safeSubstring(combined, snippetStart, snippetEnd))
                            .put("match_offset_bytes", pending.offset - pending.tail.toByteArray(Charsets.UTF_8).size + combined.substring(0, found).toByteArray(Charsets.UTF_8).size)
                            .put("line_truncated", pending.tail.isNotEmpty() || snippetStart > 0 || snippetEnd < combined.length || (newline < 0 && !chunk.eof))
                        val cost = item.toString().length
                        if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) {
                            stopReason = "output_limit"
                            paused = true
                            break
                        }
                        matches.put(item)
                        outputChars += cost
                        pending.lineMatched = true
                    }
                    val consumedEnd = if (newline >= 0) newline + 1 else end
                    val consumedBytes = chunk.text.substring(position, consumedEnd).toByteArray(Charsets.UTF_8).size
                    pending.offset += consumedBytes
                    byteBudget -= consumedBytes
                    position = consumedEnd
                    if (newline >= 0) {
                        pending.line++
                        pending.tail = ""
                        pending.lineMatched = false
                    } else {
                        pending.tail = safeSuffix(combined, (query.length - 1).coerceAtLeast(0))
                    }
                    if (matches.length() >= maxResults) { stopReason = "result_limit"; paused = true; break }
                }
                if (backend.stat(filePath).revision != stat.revision) throw FileToolException("STALE_CURSOR", "搜索期间文件已变化，请重新开始搜索")
                if (pending.offset >= stat.sizeBytes) state.pending = null
                if (paused) break
            } catch (error: FileToolException) {
                backend.checkActive()
                if (error.isGlobalFailure()) throw error
                skip(state, skipped, pending.relative, error.code)
                state.pending = null
            } catch (_: IOException) {
                backend.checkActive()
                skip(state, skipped, pending.relative, "FILE_UNREADABLE")
                state.pending = null
            } catch (_: SecurityException) {
                backend.checkActive()
                skip(state, skipped, pending.relative, "FILE_ACCESS_DENIED")
                state.pending = null
            }
        }
        val hasMore = state.pending != null || state.frames.isNotEmpty()
        if (hasMore && stopReason == "complete") stopReason = "result_limit"
        JSONObject().put("ok", true).put("tool", if (query == null) "glob_files" else "grep_files")
            .put("environment", backend.environment).put("identity", backend.identity).put("path", root)
            .put("matches", matches).put("count", matches.length()).put("truncated", hasMore)
            .put("partial", hasMore || state.skipped > 0)
            .put("complete", !hasMore && state.skipped == 0).put("stop_reason", stopReason)
            .put("entries_scanned", MAX_ENTRIES - entryBudget).put("bytes_scanned", MAX_BYTES - byteBudget)
            .put("skipped_files", state.skipped - skippedBefore).put("total_skipped", state.skipped).put("skipped", skipped)
            .put("next_cursor", if (hasMore) encodeCursor(state, signature) else JSONObject.NULL)
    }

    private fun nextFile(root: String, state: State, matcher: FileToolGlob, includeHidden: Boolean, budget: Int, skipped: JSONArray, pages: MutableMap<String, CachedPage>, timeExpired: () -> Boolean): NextFile {
        var visited = 0
        while (state.frames.isNotEmpty() && visited < budget) {
            backend.checkActive()
            // 一次列表返回后至少消费一个条目，避免慢文件系统让同一游标反复停在原地。
            if (visited > 0 && timeExpired()) return NextFile(null, visited, timeLimited = true)
            val frame = state.frames.last()
            val cached = pages[frame.relative]
            if (cached != null && frame.offset == cached.offset + cached.page.entries.size && !cached.page.hasMore) {
                pages.remove(frame.relative)
                state.frames.removeAt(state.frames.lastIndex)
                continue
            }
            val page = try {
                if (cached != null && frame.offset >= cached.offset && frame.offset < cached.offset + cached.page.entries.size) cached
                else CachedPage(frame.offset, backend.list(join(root, frame.relative), frame.offset, minOf(64, budget - visited), frame.revision))
                    .also { pages[frame.relative] = it }
            } catch (error: FileToolException) {
                backend.checkActive()
                if (error.isGlobalFailure()) throw error
                skip(state, skipped, frame.relative, error.code)
                state.frames.removeAt(state.frames.lastIndex)
                continue
            } catch (_: IOException) {
                backend.checkActive()
                skip(state, skipped, frame.relative, "DIRECTORY_UNREADABLE")
                state.frames.removeAt(state.frames.lastIndex)
                continue
            }
            if (page.page.entries.isEmpty()) { state.frames.removeAt(state.frames.lastIndex); continue }
            val entry = page.page.entries[frame.offset - page.offset]
            frame.offset++
            visited++
            if (!includeHidden && entry.name.startsWith('.')) continue
            val relative = if (frame.relative.isEmpty()) entry.name else "${frame.relative}/${entry.name}"
            validateRelative(relative)
            if (entry.kind == FileToolKind.DIRECTORY) {
                if (state.frames.size >= MAX_DEPTH) { skip(state, skipped, relative, "DEPTH_LIMIT"); continue }
                state.frames += Frame(relative, 0, entry.revision)
            } else if (entry.kind == FileToolKind.FILE && matcher.matches(relative)) {
                return NextFile(Pending(relative, 0, 1, entry.revision), visited)
            } else if (entry.kind == FileToolKind.SYMLINK) {
                skip(state, skipped, relative, "SYMLINK_NOT_FOLLOWED")
            }
        }
        return NextFile(null, visited)
    }

    private fun skip(state: State, skipped: JSONArray, relative: String, reason: String) {
        state.skipped++
        if (skipped.length() < 10) skipped.put(JSONObject().put("relative_path", relative).put("reason", reason))
    }

    private fun encodeCursor(state: State, signature: String): String {
        val frames = JSONArray()
        state.frames.forEach { frames.put(JSONObject().put("path", it.relative).put("offset", it.offset).put("revision", it.revision)) }
        val json = JSONObject().put("version", 1).put("signature", signature).put("frames", frames).put("skipped", state.skipped)
        state.pending?.let { json.put("pending", JSONObject().put("path", it.relative).put("offset", it.offset).put("line", it.line)
            .put("revision", it.revision).put("tail", it.tail).put("matched", it.lineMatched)) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        if (encoded.length > MAX_CURSOR_CHARS) throw FileToolException("CURSOR_LIMIT", "搜索路径过深或过长，请选择更小的搜索目录")
        return encoded
    }

    private fun decodeCursor(cursor: String, signature: String): State = try {
        require(cursor.length <= MAX_CURSOR_CHARS)
        val json = JSONObject(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("signature") == signature)
        val array = json.getJSONArray("frames")
        require(array.length() <= MAX_DEPTH)
        val frames = mutableListOf<Frame>()
        for (index in 0 until array.length()) {
            val frame = array.getJSONObject(index)
            val relative = frame.getString("path").also(::validateRelative)
            val offset = frame.getInt("offset").also { require(it >= 0) }
            frames += Frame(relative, offset, frame.getString("revision"))
        }
        val pending = json.optJSONObject("pending")?.let {
            Pending(it.getString("path").also(::validateRelative), it.getLong("offset").also { offset -> require(offset >= 0) },
                it.getLong("line").also { line -> require(line > 0) }, it.getString("revision"),
                it.getString("tail").also { tail -> require(tail.length <= 512) }, it.getBoolean("matched"))
        }
        State(frames, pending, json.getInt("skipped").also { require(it >= 0) })
    } catch (_: IllegalArgumentException) {
        throw FileToolException("INVALID_CURSOR", "搜索游标无效或不属于当前搜索条件")
    } catch (_: org.json.JSONException) {
        throw FileToolException("INVALID_CURSOR", "搜索游标无效")
    }

    private fun validateRelative(value: String) {
        require(!value.startsWith('/') && value.indexOf('\u0000') < 0 && value.split('/').none { it == ".." || it == "." })
    }

    private fun join(root: String, relative: String): String = if (relative.isEmpty()) root else "${root.trimEnd('/')}/$relative"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun safeSubstring(text: String, start: Int, end: Int): String {
        val safeStart = if (start > 0 && text[start].isLowSurrogate()) start - 1 else start
        val safeEnd = if (end < text.length && end > safeStart && text[end - 1].isHighSurrogate()) end - 1 else end
        return text.substring(safeStart, safeEnd)
    }
    private fun safeSuffix(text: String, count: Int): String = if (count == 0) "" else safeSubstring(text, (text.length - count).coerceAtLeast(0), text.length)
    private fun FileToolException.isGlobalFailure(): Boolean = code in setOf(
        "STALE_CURSOR", "FILE_CHANGED", "ROOT_REQUIRED", "CANCELLED", "TERMINAL_CLOSED",
        "LINUX_ENVIRONMENT_NOT_READY", "LINUX_ENVIRONMENT_REQUIRES_ROOT", "INVALID_IDENTITY",
        "FILE_TIMEOUT", "FILE_PROCESS_FAILED",
    )

    private data class Frame(val relative: String, var offset: Int, val revision: String)
    private data class Pending(val relative: String, var offset: Long, var line: Long, val revision: String, var tail: String = "", var lineMatched: Boolean = false)
    private data class State(val frames: MutableList<Frame>, var pending: Pending? = null, var skipped: Int = 0)
    private data class NextFile(val file: Pending?, val visited: Int, val timeLimited: Boolean = false)
    private data class CachedPage(val offset: Int, val page: FileToolDirectoryPage)

    companion object {
        const val MAX_ENTRIES = 2_000
        const val MAX_BYTES = 256 * 1024
        const val MAX_MATCH_CHARS = 12_000
        const val MAX_CURSOR_CHARS = 16_384
        const val MAX_DEPTH = 24
    }
}
