package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.util.Locale
import org.json.JSONObject

/** 文件轨迹只保留操作结果与规模，不把路径、正文或搜索条件带入持久摘要。 */
internal object AgentFileResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? = when (toolName) {
        "write_file" -> summarizeWrite(json)
        "read_file" -> summarizeRead(json)
        "stat_file" -> summarizeStat(json)
        "edit_file" -> json.nonNegativeLong("replacements")?.let {
            AppStrings.quantity(
                R.plurals.trace_res_replacements,
                it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "已替换 %1\$d 处",
                "已替换 %1\$d 处",
                it,
            )
        }
        "list_directory" -> json.optJSONArray("entries")?.let { entries ->
            val count = entries.length()
            if (count == 0) AppStrings.get(R.string.trace_file_dir_empty, "目录为空")
            else withScope(
                AppStrings.quantity(
                    R.plurals.trace_file_dir_listed,
                    count,
                    "已列出 %1\$d 项内容",
                    "已列出 %1\$d 项内容",
                    count,
                ),
                json,
                AppStrings.get(R.string.trace_res_has_more, "还有更多"),
            )
        }
        "glob_files", "grep_files" -> json.optJSONArray("matches")?.let { matches ->
            val count = matches.length()
            val label = when {
                count == 0 && toolName == "glob_files" ->
                    AppStrings.get(R.string.trace_file_no_files, "未找到匹配文件")
                count == 0 -> AppStrings.get(R.string.trace_res_no_matches, "未找到匹配内容")
                toolName == "glob_files" -> AppStrings.quantity(
                    R.plurals.trace_file_found_files,
                    count,
                    "找到 %1\$d 个文件",
                    "找到 %1\$d 个文件",
                    count,
                )
                else -> AppStrings.quantity(
                    R.plurals.trace_res_found_matches,
                    count,
                    "找到 %1\$d 处匹配",
                    "找到 %1\$d 处匹配",
                    count,
                )
            }
            if (count == 0) {
                if (json.opt("partial") == true) {
                    "$label · ${AppStrings.get(R.string.trace_file_scan_limited, "扫描范围有限")}"
                } else {
                    label
                }
            } else {
                withScope(label, json, AppStrings.get(R.string.trace_res_has_more, "还有更多"))
            }
        }
        else -> null
    }

    private fun summarizeWrite(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_written") ?: return null
        val formatted = formatBytes(bytes)
        return when (json.optString("mode")) {
            "append" -> AppStrings.get(R.string.trace_file_appended, "已追加 %1\$s", formatted)
            "overwrite" -> AppStrings.get(R.string.trace_file_written, "已写入 %1\$s", formatted)
            else -> null
        }
    }

    private fun summarizeRead(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_read") ?: return null
        if (json.opt("start_line_reached") == false) {
            return when (json.opt("truncated") as? Boolean) {
                true -> AppStrings.get(
                    R.string.trace_file_line_not_reached_more,
                    "尚未到达目标行 · 可继续读取",
                )
                false -> AppStrings.get(
                    R.string.trace_file_line_not_reached_eof,
                    "未到达目标行 · 已到文件末尾",
                )
                null -> AppStrings.get(R.string.trace_file_line_not_reached, "尚未到达目标行")
            }
        }
        val isTruncated = json.opt("truncated") == true || json.opt("has_more") == true
        val formatted = formatBytes(bytes)
        val label = if (isTruncated) {
            AppStrings.get(R.string.trace_res_read_prefix, "已读前 %1\$s", formatted)
        } else {
            AppStrings.get(R.string.trace_file_read, "已读取 %1\$s", formatted)
        }
        return withScope(label, json, AppStrings.get(R.string.trace_res_has_more, "还有更多"))
    }

    private fun summarizeStat(json: JSONObject): String? {
        val file = json.optJSONObject("file") ?: return null
        return when (file.optString("kind")) {
            "file" -> file.nonNegativeLong("size_bytes")?.let {
                AppStrings.get(R.string.trace_file_kind_file_size, "文件 · %1\$s", formatBytes(it))
            }
            "directory" -> AppStrings.get(R.string.trace_file_kind_dir, "目录")
            "symlink" -> AppStrings.get(R.string.trace_file_kind_symlink, "符号链接")
            "other" -> AppStrings.get(R.string.trace_file_kind_other, "其他文件类型")
            else -> null
        }
    }

    private fun withScope(label: String, json: JSONObject, continuation: String): String =
        buildList {
            add(label)
            val hasMore = json.opt("truncated") == true || json.opt("has_more") == true
            if (hasMore) add(continuation)
            if (json.opt("partial") == true) {
                add(AppStrings.get(R.string.trace_file_scan_limited, "扫描范围有限"))
            }
        }.joinToString(" · ")

    private fun JSONObject.nonNegativeLong(key: String): Long? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) {
            return AppStrings.quantity(
                R.plurals.trace_unit_bytes,
                bytes.toInt(),
                "%1\$d 字节",
                "%1\$d 字节",
                bytes,
            )
        }
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
