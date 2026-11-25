package io.github.mangi.eta.agent.tool.personal

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal fun quoteColorOsMemoryIdentifier(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""

/** 只接受查询调用，兼容宿主的加密数据库连接和普通 SQLite 快照。 */
internal fun interface ColorOsMemoryReadDatabase {
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor
}

/** Runtime 快照和小布进程 Hook 共用固定查询，不暴露任意 SQL 入口。 */
internal object ColorOsMemoryDatabaseQuery {
    fun execute(database: SQLiteDatabase, operation: String, args: JSONObject): String =
        execute(ColorOsMemoryReadDatabase(database::rawQuery), operation, args)

    fun execute(database: ColorOsMemoryReadDatabase, operation: String, args: JSONObject): String = when (operation) {
        ColorOsMemoryBridgeProtocol.OPERATION_SEARCH -> searchMemories(database, args)
        ColorOsMemoryBridgeProtocol.OPERATION_ORDERS -> searchMemories(
            database = database,
            args = args,
            ordersOnly = true,
            toolName = "search_personal_orders",
        )
        ColorOsMemoryBridgeProtocol.OPERATION_PLACES -> searchPlaces(database, args)
        else -> error("COLOROS_MEMORY_OPERATION_UNSUPPORTED", "不支持的 ColorOS 系统记忆查询")
    }

    private fun searchMemories(
        database: ColorOsMemoryReadDatabase,
        args: JSONObject,
        ordersOnly: Boolean = false,
        toolName: String = "search_coloros_memories",
    ): String {
        val memoryColumns = database.tableColumns(MEMORIES_TABLE)
        if (memoryColumns.isEmpty() || "memory_id" !in memoryColumns) {
            return error("COLOROS_MEMORY_SCHEMA_UNSUPPORTED", "当前系统记忆数据库结构暂不受支持")
        }
        val projection = CORE_COLUMNS.filter(memoryColumns::contains)
        val keyword = args.optString("query").trim()
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if ("deleted" in memoryColumns) selectionParts += "${"deleted".sqlIdentifier()}=0"
        if ("recycle_time" in memoryColumns) selectionParts += "${"recycle_time".sqlIdentifier()}=0"
        if (ordersOnly) {
            val orderPredicates = mutableListOf<String>()
            if ("package_name" in memoryColumns) {
                orderPredicates +=
                    "${"package_name".sqlIdentifier()} IN (${ORDER_PACKAGES.joinToString { "?" }})"
                selectionArgs += ORDER_PACKAGES
            }
            val orderColumns = ORDER_SEARCH_COLUMNS.filter(memoryColumns::contains)
            if (orderColumns.isNotEmpty()) {
                ORDER_KEYWORDS.forEach { keywordValue ->
                    val pattern = "%${keywordValue.escapeLikePattern()}%"
                    orderPredicates += orderColumns.joinToString(" OR ", prefix = "(", postfix = ")") { column ->
                        "${column.sqlIdentifier()} LIKE ? ESCAPE '\\' COLLATE NOCASE"
                    }
                    repeat(orderColumns.size) { selectionArgs += pattern }
                }
            }
            listOf("bills", "pickup_codes", "shipments").forEach { table ->
                val columns = database.tableColumns(table)
                if ("associate_memory_id" in columns) {
                    orderPredicates +=
                        "EXISTS (SELECT 1 FROM ${table.sqlIdentifier()} WHERE " +
                            "${"associate_memory_id".sqlIdentifier()}=" +
                            "${MEMORIES_TABLE.sqlIdentifier()}.${"memory_id".sqlIdentifier()})"
                }
            }
            if (orderPredicates.isEmpty()) {
                return error("COLOROS_ORDER_SCHEMA_UNSUPPORTED", "当前系统记忆没有可用的订单字段")
            }
            selectionParts += orderPredicates.joinToString(" OR ", prefix = "(", postfix = ")")
        }
        if (keyword.isNotBlank()) {
            val pattern = "%${keyword.escapeLikePattern()}%"
            val searchPredicates = mutableListOf<String>()
            val searchColumns = SEARCH_COLUMNS.filter(memoryColumns::contains)
            if (searchColumns.isNotEmpty()) {
                searchPredicates += searchColumns.map { column ->
                    "${column.sqlIdentifier()} LIKE ? ESCAPE '\\' COLLATE NOCASE"
                }
                repeat(searchColumns.size) { selectionArgs += pattern }
            }
            DETAIL_SPECS.forEach { spec ->
                val columns = database.tableColumns(spec.table)
                if (spec.linkColumn !in columns) return@forEach
                val searchable = spec.searchColumns.filter(columns::contains)
                if (searchable.isEmpty()) return@forEach
                searchPredicates += searchable.joinToString(
                    separator = " OR ",
                    prefix = "EXISTS (SELECT 1 FROM ${spec.table.sqlIdentifier()} WHERE " +
                        "${spec.linkColumn.sqlIdentifier()}=" +
                        "${MEMORIES_TABLE.sqlIdentifier()}.${"memory_id".sqlIdentifier()} AND (",
                    postfix = "))",
                ) { column -> "${column.sqlIdentifier()} LIKE ? ESCAPE '\\' COLLATE NOCASE" }
                repeat(searchable.size) { selectionArgs += pattern }
            }
            if (searchPredicates.isNotEmpty()) {
                selectionParts += searchPredicates.joinToString(" OR ", prefix = "(", postfix = ")")
            }
        }
        val cursor = database.query(
            MEMORIES_TABLE,
            projection.sqlProjection(),
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            if ("created_time" in memoryColumns) "${"created_time".sqlIdentifier()} DESC" else null,
            limit.toString(),
        )
        val items = JSONArray()
        var resultBytes = 0
        var resultTruncated = false
        cursor.use {
            while (it.moveToNext()) {
                val item = it.currentRow()
                val memoryId = item.optString("memory_id")
                if (memoryId.isNotBlank()) {
                    val details = relatedDetails(database, memoryId)
                    if (details.length() > 0) item.put("details", details)
                }
                var serializedBytes = item.toString().toByteArray(StandardCharsets.UTF_8).size
                if (resultBytes + serializedBytes > MAX_RESULT_BYTES && item.has("details")) {
                    item.remove("details")
                    item.put("details_truncated", true)
                    serializedBytes = item.toString().toByteArray(StandardCharsets.UTF_8).size
                }
                if (resultBytes + serializedBytes > MAX_RESULT_BYTES) {
                    resultTruncated = true
                    break
                }
                items.put(item)
                resultBytes += serializedBytes
            }
            if (!it.isAfterLast) resultTruncated = true
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", toolName)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", resultTruncated || items.length() == limit)
            .toString()
    }

    private fun searchPlaces(database: ColorOsMemoryReadDatabase, args: JSONObject): String {
        val columns = database.tableColumns("memory_address")
        if ("memory_id" !in columns) {
            return error("COLOROS_PLACE_SCHEMA_UNSUPPORTED", "当前系统记忆没有可用的地点字段")
        }
        val projection = PLACE_COLUMNS.filter(columns::contains)
        val keyword = args.optString("query").trim()
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val searchable = PLACE_SEARCH_COLUMNS.filter(columns::contains)
        val selection = if (keyword.isNotBlank() && searchable.isNotEmpty()) {
            searchable.joinToString(" OR ", prefix = "(", postfix = ")") {
                "${it.sqlIdentifier()} LIKE ? ESCAPE '\\' COLLATE NOCASE"
            }
        } else {
            null
        }
        val selectionArgs = if (selection != null) {
            Array(searchable.size) { "%${keyword.escapeLikePattern()}%" }
        } else {
            null
        }
        val items = JSONArray()
        database.query(
            "memory_address",
            projection.sqlProjection(),
            selection,
            selectionArgs,
            null,
            null,
            if ("create_time" in columns) "${"create_time".sqlIdentifier()} DESC" else null,
            limit.toString(),
        ).use { cursor -> while (cursor.moveToNext()) items.put(cursor.currentRow()) }
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_saved_places")
            .put("items", items)
            .put("count", items.length())
            .put("truncated", items.length() == limit)
            .toString()
    }

    private fun relatedDetails(database: ColorOsMemoryReadDatabase, memoryId: String): JSONObject =
        JSONObject().also { details ->
            DETAIL_SPECS.forEach { spec ->
                val columns = database.tableColumns(spec.table)
                if (spec.linkColumn !in columns) return@forEach
                val projection = spec.columns.filter(columns::contains)
                if (projection.isEmpty()) return@forEach
                val selection = buildString {
                    append(spec.linkColumn.sqlIdentifier()).append("=?")
                    spec.activeColumn
                        ?.takeIf(columns::contains)
                        ?.let { append(" AND ").append(it.sqlIdentifier()).append("=0") }
                }
                val rows = database.queryRows(
                    table = spec.table,
                    projection = projection,
                    selection = selection,
                    selectionArgs = arrayOf(memoryId),
                    limit = RELATED_LIMIT,
                )
                if (rows.length() > 0) details.put(spec.outputName, rows)
            }
        }

    private fun ColorOsMemoryReadDatabase.queryRows(
        table: String,
        projection: List<String>,
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): JSONArray = JSONArray().also { rows ->
        query(
            table,
            projection.sqlProjection(),
            selection,
            selectionArgs,
            null,
            null,
            null,
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) rows.put(cursor.currentRow())
        }
    }

    private fun ColorOsMemoryReadDatabase.query(
        table: String,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        limit: String,
    ): Cursor = rawQuery(
        SQLiteQueryBuilder.buildQueryString(
            false, table.sqlIdentifier(), projection, selection, groupBy, having, orderBy, limit,
        ),
        selectionArgs,
    )

    private fun ColorOsMemoryReadDatabase.tableColumns(table: String): Set<String> =
        rawQuery("PRAGMA table_info(${table.sqlIdentifier()})", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun Cursor.currentRow(): JSONObject = JSONObject().also { row ->
        for (index in 0 until columnCount) {
            if (isNull(index)) continue
            val value: Any = when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index).bounded()
                Cursor.FIELD_TYPE_BLOB -> continue
                else -> continue
            }
            row.put(getColumnName(index), value)
        }
    }

    private fun String.bounded(): String =
        if (length <= MAX_FIELD_CHARS) this else take(MAX_FIELD_CHARS) + "…"

    private fun String.escapeLikePattern(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String.sqlIdentifier(): String = quoteColorOsMemoryIdentifier(this)

    private fun List<String>.sqlProjection(): Array<String> =
        map { it.sqlIdentifier() }.toTypedArray()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private data class DetailSpec(
        val outputName: String,
        val table: String,
        val linkColumn: String = "associate_memory_id",
        val activeColumn: String? = null,
        val columns: List<String>,
        val searchColumns: List<String>,
    )

    private const val MEMORIES_TABLE = "memories"
    private const val DEFAULT_LIMIT = 10
    private const val MAX_LIMIT = 30
    private const val RELATED_LIMIT = 3
    private const val MAX_FIELD_CHARS = 4_000
    private const val MAX_RESULT_BYTES = 240 * 1024

    private val CORE_COLUMNS = listOf(
        "memory_id", "data_source", "data_text", "package_name", "app_name", "activity_name",
        "screenshot", "audio_file", "deeplink", "data_category", "data_entity", "ocr_entity",
        "trigger_type", "memory_type", "scene_name", "scene_type", "data_abstract", "extra_data",
        "sub_scene_data", "created_time", "update_time", "notes", "image_count", "classify",
        "data_text_cleanup",
    )
    private val SEARCH_COLUMNS = listOf(
        "data_text", "data_text_cleanup", "data_abstract", "notes", "app_name", "package_name",
        "data_category", "data_entity", "ocr_entity", "scene_name", "classify",
    )
    private val ORDER_SEARCH_COLUMNS = listOf(
        "data_text", "data_text_cleanup", "data_abstract", "notes", "app_name", "scene_name", "classify",
    )
    private val ORDER_KEYWORDS = listOf(
        "订单", "外卖", "取餐", "配送", "骑手", "快递", "车票", "机票", "酒店", "电影票",
    )
    private val ORDER_PACKAGES = listOf(
        "com.sankuai.meituan", "me.ele", "com.ss.android.ugc.lifeservices",
        "com.jingdong.app.mall", "com.taobao.taobao", "com.xunmeng.pinduoduo",
        "com.taobao.trip", "com.sdu.didi.psnger",
    )
    private val PLACE_COLUMNS = listOf(
        "memory_id", "category", "sub_type", "name", "address", "full_address", "country",
        "province", "city", "district", "location", "longitude", "latitude", "reason", "insight",
        "deepLink", "shopHours",
    )
    private val PLACE_SEARCH_COLUMNS = listOf(
        "category", "sub_type", "name", "address", "full_address", "country", "province", "city",
        "district", "location", "reason", "insight",
    )
    private val DETAIL_SPECS = listOf(
        DetailSpec(
            outputName = "bills",
            table = "bills",
            activeColumn = "status",
            columns = listOf(
                "transaction_type", "amount", "primary_amount", "currency", "transaction_time",
                "payment_source", "payment_method", "category", "purpose_info", "merchant_name",
                "product_name", "transaction_status", "remarks", "detail",
            ),
            searchColumns = listOf(
                "payment_source", "payment_method", "category", "purpose_info", "merchant_name",
                "product_name", "transaction_status", "remarks", "detail",
            ),
        ),
        DetailSpec(
            outputName = "schedules",
            table = "schedule_todos",
            columns = listOf(
                "type", "sub_type", "time", "content", "status", "start_time", "end_time",
                "address", "remark",
            ),
            searchColumns = listOf("type", "sub_type", "time", "content", "address", "remark"),
        ),
        DetailSpec(
            outputName = "pickup_codes",
            table = "pickup_codes",
            columns = listOf(
                "type", "pickup_code", "brand", "product_name", "merchant_name", "order_status",
                "order_time", "wait_time", "create_time",
            ),
            searchColumns = listOf(
                "type", "pickup_code", "brand", "product_name", "merchant_name", "order_status",
                "order_time",
            ),
        ),
        DetailSpec(
            outputName = "shipments",
            table = "shipments",
            columns = listOf(
                "type", "code", "address", "courier_code", "order", "status", "save_time",
                "create_time", "update_time",
            ),
            searchColumns = listOf(
                "type", "code", "address", "courier_code", "order", "status", "save_time",
            ),
        ),
        DetailSpec(
            outputName = "personal_info",
            table = "personal_infos",
            columns = listOf("type", "sub_type", "details", "extra", "not_reminder"),
            searchColumns = listOf("type", "sub_type", "details", "extra"),
        ),
        DetailSpec(
            outputName = "places",
            table = "memory_address",
            linkColumn = "memory_id",
            columns = listOf(
                "category", "sub_type", "name", "address", "full_address", "country", "province",
                "city", "district", "location", "longitude", "latitude", "reason", "insight",
            ),
            searchColumns = listOf(
                "category", "sub_type", "name", "address", "full_address", "country", "province",
                "city", "district", "location", "reason", "insight",
            ),
        ),
        DetailSpec(
            outputName = "attachments",
            table = "attachments",
            columns = listOf(
                "attachment_id", "media_type", "path", "uri", "width", "height", "text",
                "ocr_text", "caption",
            ),
            searchColumns = listOf("path", "uri", "text", "ocr_text", "caption"),
        ),
    )
}
