package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.context.PersonalSearchTools
import org.json.JSONArray
import org.json.JSONObject

internal object AgentPersonalSearchToolCatalog {
    fun appendTo(tools: JSONArray) {
        PersonalSearchTools.searches.forEach { spec ->
            tools.put(
                function(
                    spec.name,
                    spec.description + "请以结果的 backend 和 freshness 判断原始来源或历史索引；返回 ref 可读取详情。",
                    searchProperties(),
                )
            )
        }
        tools.put(
            function(
                "read_personal_item",
                "使用个人数据查询返回的 ref 读取详情；支持 ColorOS 索引与 vivo 原始只读引用，不能用该引用直接修改记录。",
                JSONObject().put("ref", string("查询结果中的 ref", 160)),
                "ref",
            )
        )
        val summary =
            searchProperties().also {
                it.remove("limit")
                it.remove("offset")
                it.remove("sort")
            }
        tools.put(
            function(
                "summarize_bills",
                "精确汇总匹配的账单索引，区分收入、支出、转账和未知类型；最多完整汇总1000条，范围过大时失败而不返回部分总额。",
                summary,
            )
        )
    }

    fun searchProperties(): JSONObject =
        JSONObject()
            .put("query", string("可选关键词", 200))
            .put("start_time", string("包含的起始时间，带时区偏移的 ISO 8601", 64))
            .put("end_time", string("不包含的结束时间，带时区偏移的 ISO 8601", 64))
            .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30))
            .put(
                "offset",
                JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 10000),
            )
            .put(
                "sort",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("newest", "oldest"))),
            )

    fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name,
            description,
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required.toList()))
                .put("additionalProperties", false),
        )

    private fun string(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)
}
