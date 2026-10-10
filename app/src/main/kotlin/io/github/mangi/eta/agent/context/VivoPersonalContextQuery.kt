package io.github.mangi.eta.agent.context

import android.net.Uri
import io.github.mangi.eta.agent.phone.PhoneOperation
import io.github.mangi.eta.agent.phone.PhoneOperationFailure
import io.github.mangi.eta.agent.phone.PhoneProviderAccess
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/** OriginOS 原始个人数据。URI、字段和保护条件不接受模型输入。 */
internal class VivoPersonalContextQuery(
    private val access: PhoneProviderAccess,
    private val userId: Int,
) {
    fun execute(tool: String, arguments: JSONObject): JSONObject = try {
        val reading = tool == "read_personal_item"
        val source: Source
        val id: String?
        if (reading) {
            PhoneOperation.allowed(arguments, setOf("ref"))
            val ref = PhoneOperation.text(arguments, "ref", true, 160)!!
            val match = REFERENCE.matchEntire(ref)
                ?: PhoneOperation.error("INVALID_ARGUMENT", "需要 vivo 查询返回的详情引用")
            source = sources.getValue(match.groupValues[1])
            id = match.groupValues[2].takeIf { it.toLongOrNull() != null }
                ?: PhoneOperation.error("INVALID_ARGUMENT", "详情引用超出范围")
        } else {
            source = sources.values.firstOrNull { it.tool == tool }
                ?: PhoneOperation.error("DEVICE_UNSUPPORTED", "此个人数据来源尚未适配 vivo")
            id = null
            PhoneOperation.allowed(arguments, SEARCH_FIELDS)
        }
        if (arguments.has("current_only")) PhoneOperation.boolean(arguments, "current_only")
        val limit = if (reading) 1 else PhoneOperation.integer(arguments, "limit", 1, 30, 10).toInt()
        val offset = if (reading) 0 else PhoneOperation.integer(arguments, "offset", 0, 10000, 0).toInt()
        val sort = PhoneOperation.text(arguments, "sort", false, 10) ?: "newest"
        if (sort !in setOf("newest", "oldest")) PhoneOperation.error("INVALID_ARGUMENT", "sort 无效")
        val clauses = mutableListOf(source.protection)
        val values = mutableListOf<String>()
        id?.let { clauses += "_id = ?"; values += it }
        PhoneOperation.text(arguments, "query", false, 200)?.trim()?.takeIf(String::isNotEmpty)?.let { query ->
            val escaped = "%${query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
            clauses += source.search.joinToString(" OR ", "(", ")") { column ->
                values += escaped
                "$column LIKE ? ESCAPE '\\'"
            }
        }
        val start = time(arguments, "start_time")
        val end = time(arguments, "end_time")
        if (start != null && end != null && start >= end) PhoneOperation.error("INVALID_ARGUMENT", "时间范围无效")
        val bounds = PersonalContextTimeUnit.MILLISECONDS.toBounds(start, end)
        bounds.startInclusive?.let { clauses += "${source.time} >= ?"; values += it.toString() }
        bounds.endExclusive?.let { clauses += "${source.time} < ?"; values += it.toString() }
        val uri = Uri.parse("content://$userId@com.provider.notes/${source.resource}")
        // 强制确认全部保护字段；缺列时不能去掉筛选条件重试。
        access.query(uri, source.required.toTypedArray(), "0", null, null)?.use { probe ->
            if (source.required.any { probe.getColumnIndex(it) < 0 })
                PhoneOperation.error("PERSONAL_CONTEXT_SCHEMA_UNSUPPORTED", "vivo 保护字段不兼容")
        } ?: PhoneOperation.error("PERSONAL_DATA_UNAVAILABLE", "vivo 未提供此个人数据来源")
        val direction = if (sort == "oldest") "ASC" else "DESC"
        val cursor = access.query(
            uri, source.columns.values.toTypedArray(), clauses.joinToString(" AND "),
            values.toTypedArray(), "${source.time} $direction, _id $direction LIMIT ${limit + 1} OFFSET $offset",
        ) ?: PhoneOperation.error("PERSONAL_DATA_UNAVAILABLE", "vivo 个人数据来源暂不可读")
        val items = JSONArray()
        var more = false
        var truncated = false
        var budget = 40_000
        cursor.use { c ->
            if (source.columns.values.any { c.getColumnIndex(it) < 0 })
                PhoneOperation.error("PERSONAL_CONTEXT_SCHEMA_UNSUPPORTED", "vivo 数据字段不兼容")
            while (c.moveToNext()) {
                if (items.length() >= limit) { more = true; break }
                val item = JSONObject()
                source.columns.forEach { (logical, physical) ->
                    val column = c.getColumnIndexOrThrow(physical)
                    val value = if (c.isNull(column)) null else c.getString(column)
                    if (value != null && value.length > if (reading) 8000 else 1500) truncated = true
                    item.put(logical, value?.take(if (reading) 8000 else 1500) ?: JSONObject.NULL)
                }
                val itemId = item.optString("id")
                if (!ID.matches(itemId) || itemId.toLongOrNull() == null)
                    PhoneOperation.error("PERSONAL_CONTEXT_OUTPUT_INVALID", "vivo 返回的记录 ID 无效")
                item.put("ref", "eta-vivo:${source.id}:$itemId")
                if (source.id == "notes") item.put("note_id", itemId)
                val size = item.toString().length
                if (size > budget) { truncated = true; more = true; break }
                budget -= size
                items.put(item)
            }
        }
        if (reading && items.length() == 0)
            PhoneOperation.error("PERSONAL_CONTEXT_NOT_FOUND", "记录不存在或已变为私密、加密或回收状态")
        PhoneOperation.ok(tool)
            .put("backend", "vivo_native_provider").put("source", source.id).put("freshness", "live")
            .put("queried_at", Instant.now().toString()).put("time_unit", "milliseconds")
            .put("items", items).put("count", items.length()).put("has_more", more).put("truncated", truncated)
            .put("next_offset", if (more && offset + items.length() <= 10000) offset + items.length() else JSONObject.NULL)
            .put("reference_scope", "vivo 原始只读引用；当前不支持据此修改记录")
    } catch (failure: PhoneOperationFailure) {
        PhoneOperation.failure(failure.code, failure.message ?: "vivo 个人数据查询失败")
    } catch (_: java.time.DateTimeException) {
        PhoneOperation.failure("INVALID_ARGUMENT", "时间必须是带时区偏移的 ISO 8601")
    } catch (_: PersonalContextArgumentException) {
        PhoneOperation.failure("INVALID_ARGUMENT", "时间范围超出支持范围")
    } catch (_: SecurityException) {
        PhoneOperation.failure("PHONE_ACCESS_DENIED", "系统未允许读取此 vivo 个人数据来源")
    } catch (_: Exception) {
        PhoneOperation.failure("PERSONAL_CONTEXT_SCHEMA_UNSUPPORTED", "此 vivo 数据接口或字段不兼容；未移除保护条件")
    }

    private fun time(args: JSONObject, name: String): Instant? =
        PhoneOperation.text(args, name, false, 64)?.let { OffsetDateTime.parse(it).toInstant() }

    private data class Source(
        val id: String,
        val tool: String,
        val resource: String,
        val columns: Map<String, String>,
        val search: List<String>,
        val time: String,
        val protection: String,
        val required: List<String>,
    )

    companion object {
        val tools = setOf("search_notes", "search_todos", "read_personal_item")
        private val ID = Regex("[0-9]{1,19}")
        private val REFERENCE = Regex("eta-vivo:(notes|todos):([0-9]{1,19})")
        private val SEARCH_FIELDS = setOf("query", "limit", "offset", "start_time", "end_time", "sort", "current_only")
        private val sources = listOf(
            Source(
                "notes", "search_notes", "note",
                linkedMapOf("id" to "_id", "title" to "note_title", "text" to "content_no_tag",
                    "time" to "curtimemillis", "created_time" to "createtime"),
                listOf("note_title", "content_no_tag"), "curtimemillis",
                "dirty IN (0,1) AND has_passwd IN (0,1) AND isEncrypted=0 AND move_private=0",
                listOf("_id", "dirty", "has_passwd", "isEncrypted", "move_private"),
            ),
            Source(
                "todos", "search_todos", "todo",
                linkedMapOf("id" to "_id", "text" to "content", "status" to "type", "planned_time" to "notice_time",
                    "time" to "modified_time", "created_time" to "created_time"),
                listOf("content"), "modified_time", "dirty IN (0,1) AND move_private=0",
                listOf("_id", "dirty", "move_private"),
            ),
        ).associateBy { it.id }
    }
}
