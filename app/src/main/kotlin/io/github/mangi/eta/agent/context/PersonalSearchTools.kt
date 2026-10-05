package io.github.mangi.eta.agent.context

import org.json.JSONArray
import org.json.JSONObject

/** 模型面对领域名称；索引资源与协议仅在这里映射，不成为公开工具参数。 */
internal object PersonalSearchTools {
    data class Search(
        val name: String,
        val source: String,
        val title: String,
        val description: String,
    )

    val searches =
        listOf(
            Search("search_bills", "bills", "查询账单", "按商户、内容和时间查找账单记录。"),
            Search("search_todos", "todos", "查询待办线索", "ColorOS 查询系统提取的待办线索；vivo 查询原子笔记的待办，时间筛选使用最后修改时间，planned_time 是计划提醒时间。"),
            Search("search_calendar_todos", "calendar_todos", "查询日历待办", "查询日历中的待办及其计划时间。"),
            Search("search_memory_collections", "collections", "查询记忆合集", "查找已保存的记忆合集，不支持业务时间筛选。"),
            Search("search_daily_events", "events", "查询生活事件", "查找系统记录或推断的生活事件，不把推断当成确认事实。"),
            Search("search_flights", "flights", "查询航班行程", "按航班、机场和计划起飞时间检索行程。"),
            Search("search_hotels", "hotels", "查询酒店预订", "按酒店、地址、订单和入住时间检索预订。"),
            Search("search_trains", "trains", "查询火车行程", "按车次、车站和出发时间检索行程。"),
        )
    val names =
        searches.mapTo(linkedSetOf()) { it.name } + setOf("read_personal_item", "summarize_bills")
    val aliases =
        mapOf(
            "search_coloros_notes" to "search_notes",
            "search_coloros_memories" to "search_system_memories",
        )

    fun canonical(name: String): String = aliases[name] ?: name

    fun isIndexedSearch(name: String): Boolean = searches.any { it.name == name }
}

internal class IndexedPersonalSearch(private val service: PersonalContextQueryService) {
    fun execute(name: String, arguments: JSONObject, fallback: Boolean = false): JSONObject? {
        val request = JSONObject(arguments.toString())
        val source =
            when (name) {
                "search_media" ->
                    if (request.optString("match", "name") == "content") "photos" else return null
                "search_files" ->
                    if (request.optString("match", "name") == "content") "files" else return null
                "search_notes" -> if (fallback) "notes" else return null
                "search_system_memories" ->
                    if (hasExtendedFilter(request)) "memories" else return null
                "summarize_bills" -> "bills"
                "read_personal_item" -> {
                    val reference = request.opt("ref") as? String ?: return invalid()
                    val parts = reference.split(':')
                    if (
                        parts.size != 3 ||
                            parts[0] != "eta-index" ||
                            PersonalContextSources.find(parts[1]) == null ||
                            !Regex("[0-9]{1,19}").matches(parts[2]) ||
                            parts[2].toLongOrNull() == null ||
                            request.length() != 1
                    )
                        return invalid()
                    request.remove("ref")
                    request.put("id", parts[2])
                    parts[1]
                }
                else ->
                    PersonalSearchTools.searches.firstOrNull { it.name == name }?.source
                        ?: return null
            }
        request.remove("match")
        request.remove("current_only")
        request
            .put("source", source)
            .put(
                "action",
                when (name) {
                    "read_personal_item" -> "read"
                    "summarize_bills" -> "bill_summary"
                    else -> "search"
                },
            )
        return service.execute(request).put("tool", name).also { result ->
            val items = result.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item
                    .optString("id")
                    .takeIf { it.toLongOrNull() != null }
                    ?.let {
                        item.put("ref", "eta-index:$source:$it")
                    }
            }
            result.put("reference_scope", "索引引用仅用于读取；修改原始应用记录必须重新核对原始对象 ID")
        }
    }

    private fun hasExtendedFilter(args: JSONObject) =
        listOf("start_time", "end_time", "offset", "sort").any(args::has)

    private fun invalid() =
        JSONObject()
            .put("ok", false)
            .put("code", "INVALID_ARGUMENT")
            .put("message", "必须提供查询结果返回的完整 ref")
}
