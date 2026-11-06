package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFileResultFormatterTest {
    @Test
    fun appendReportsOnlyTheWrittenAmount() {
        val summary = summarize("write_file", """{"mode":"append","bytes_written":1536,"size_bytes":8192}""")
        assertEquals("已追加 1.5 KiB", summary)
        assertEquals("已写入 0 字节", summarize("write_file", """{"mode":"overwrite","bytes_written":0}"""))
    }

    @Test
    fun statDoesNotPresentDirectoryMetadataAsItsContentSize() {
        assertEquals("目录", summarize("stat_file", """{"file":{"kind":"directory","size_bytes":4096}}"""))
        assertEquals("文件 · 2 MiB", summarize("stat_file", """{"file":{"kind":"file","size_bytes":2097152}}"""))
        assertEquals("符号链接", summarize("stat_file", """{"file":{"kind":"symlink","size_bytes":42}}"""))
    }

    @Test
    fun readDistinguishesPaginationFromScanningBeforeTheRequestedLine() {
        assertEquals("已读前 1 KiB · 还有更多", summarize("read_file", """{"bytes_read":1024,"truncated":true,"start_line_reached":true}"""))
        assertEquals("尚未到达目标行 · 可继续读取", summarize("read_file", """{"bytes_read":0,"truncated":true,"start_line_reached":false,"stop_reason":"scan_limit"}"""))
        assertEquals("未到达目标行 · 已到文件末尾", summarize("read_file", """{"bytes_read":0,"truncated":false,"start_line_reached":false,"stop_reason":"eof"}"""))
    }

    @Test
    fun listingAndSearchCountOnlyReturnedResultsAndPreservePartialCoverage() {
        assertEquals("已列出 1 项内容 · 还有更多", summarize("list_directory", """{"entries":[{}],"has_more":true}"""))
        assertEquals("目录为空", summarize("list_directory", """{"entries":[]}"""))
        assertEquals("找到 2 个文件 · 还有更多 · 扫描范围有限", summarize("glob_files", """{"matches":[{},{}],"truncated":true,"partial":true}"""))
        assertEquals("找到 1 处匹配 · 还有更多 · 扫描范围有限", summarize("grep_files", """{"matches":[{}],"truncated":true,"partial":true,"total_skipped":2,"next_cursor":"next-page"}"""))
        assertEquals("未找到匹配内容 · 扫描范围有限", summarize("grep_files", """{"matches":[],"truncated":false,"partial":true,"total_skipped":3}"""))
    }

    @Test
    fun editDoesNotPresentTheWholeRewrittenFileAsChangedBytes() {
        assertEquals("已替换 2 处", summarize("edit_file", """{"replacements":2,"bytes_written":90000}"""))
    }

    @Test
    fun summariesNeverExposePathsContentOrSearchConditions() {
        val privateFields = JSONObject()
            .put("path", "/private/path-marker")
            .put("content", "content-marker")
            .put("pattern", "pattern-marker")
            .put("query", "query-marker")
        val results = listOf(
            "write_file" to """{"mode":"append","bytes_written":12}""",
            "read_file" to """{"bytes_read":12}""",
            "stat_file" to """{"file":{"kind":"file","size_bytes":12,"name":"name-marker","path":"nested-path-marker"}}""",
            "edit_file" to """{"replacements":1}""",
            "list_directory" to """{"entries":[{"name":"name-marker","path":"nested-path-marker"}]}""",
            "glob_files" to """{"matches":[{"path":"nested-path-marker"}]}""",
            "grep_files" to """{"matches":[{"path":"nested-path-marker","text":"matched-text-marker"}]}""",
        )
        results.forEach { (tool, raw) ->
            val json = JSONObject(raw)
            privateFields.keys().forEach { key -> json.put(key, privateFields.get(key)) }
            val summary = AgentFileResultFormatter.summarize(tool, json)!!
            assertFalse(summary, summary.contains("marker"))
        }
    }

    @Test
    fun unknownOrIncompleteSchemasFallBackWithoutInventingValues() {
        assertNull(summarize("write_file", """{"mode":"append"}"""))
        assertNull(summarize("write_file", """{"mode":"unexpected","bytes_written":1}"""))
        assertNull(summarize("read_file", """{"bytes_read":null}"""))
        assertNull(summarize("read_file", """{"bytes_read":-1}"""))
        assertNull(summarize("stat_file", """{"file":{"kind":"file"}}"""))
        assertNull(summarize("grep_files", """{"count":8}"""))
        assertNull(summarize("unrelated_tool", """{"entries":[]}"""))
    }

    private fun summarize(toolName: String, raw: String): String? =
        AgentFileResultFormatter.summarize(toolName, JSONObject(raw))
}
