package io.github.mangi.eta.agent.model

import java.util.Locale
import org.json.JSONObject

/** 文件轨迹只保留操作结果与规模，不把路径、正文或搜索条件带入持久摘要。 */
internal object AgentFileResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? = when (toolName) {
        "write_file" -> summarizeWrite(json)
        "read_file" -> summarizeRead(json)
        "stat_file" -> summarizeStat(json)
        "edit_file" -> json.nonNegativeLong("replacements")?.let { "已替换 $it 处" }
        "list_directory" -> json.optJSONArray("entries")?.let { entries ->
            if (entries.length() == 0) "目录为空"
            else withScope("已列出 ${entries.length()} 项内容", json, "还有更多")
        }
        "glob_files", "grep_files" -> json.optJSONArray("matches")?.let { matches ->
            val count = matches.length()
            val label = when {
                count == 0 && toolName == "glob_files" -> "未找到匹配文件"
                count == 0 -> "未找到匹配内容"
                toolName == "glob_files" -> "找到 $count 个文件"
                else -> "找到 $count 处匹配"
            }
            if (count == 0) {
                if (json.opt("partial") == true) "$label · 扫描范围有限" else label
            } else {
                withScope(label, json, "还有更多")
            }
        }
        else -> null
    }

    private fun summarizeWrite(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_written") ?: return null
        val action = when (json.optString("mode")) {
            "append" -> "已追加"
            "overwrite" -> "已写入"
            else -> return null
        }
        return "$action ${formatBytes(bytes)}"
    }

    private fun summarizeRead(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_read") ?: return null
        if (json.opt("start_line_reached") == false) {
            return when (json.opt("truncated") as? Boolean) {
                true -> "尚未到达目标行 · 可继续读取"
                false -> "未到达目标行 · 已到文件末尾"
                null -> "尚未到达目标行"
            }
        }
        val isTruncated = json.opt("truncated") == true || json.opt("has_more") == true
        val label = if (isTruncated) "已读前 ${formatBytes(bytes)}" else "已读取 ${formatBytes(bytes)}"
        return withScope(label, json, "还有更多")
    }

    private fun summarizeStat(json: JSONObject): String? {
        val file = json.optJSONObject("file") ?: return null
        return when (file.optString("kind")) {
            "file" -> file.nonNegativeLong("size_bytes")?.let { "文件 · ${formatBytes(it)}" }
            "directory" -> "目录"
            "symlink" -> "符号链接"
            "other" -> "其他文件类型"
            else -> null
        }
    }

    private fun withScope(label: String, json: JSONObject, continuation: String): String =
        buildList {
            add(label)
            val hasMore = json.opt("truncated") == true || json.opt("has_more") == true
            if (hasMore) add(continuation)
            if (json.opt("partial") == true) add("扫描范围有限")
        }.joinToString(" · ")

    private fun JSONObject.nonNegativeLong(key: String): Long? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes 字节"
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var size = bytes / 1024.0
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return "${String.format(Locale.ROOT, "%.1f", size).removeSuffix(".0")} ${units[unit]}"
    }
}
