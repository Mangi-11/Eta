package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 只声明当前会话的历史检索，不允许模型指定其他会话身份。 */
internal object AgentConversationToolCatalog {
    const val READ_HISTORY = "conversation_history"

    fun schema(): JSONObject = AgentToolSchema.function(
        name = READ_HISTORY,
        description = "读取当前会话的完整脱敏历史。上下文摘要有损，需要核对旧指令、操作细节或工具结果时使用。" +
            "query 可搜索文本；省略 query 则按 message_index、offset 分页读取。沿 next_message_index 和 next_offset 继续，" +
            "续读固定同一份历史，从零开始可刷新；不要把分页内容误认为完整历史。敏感工具原文和瞬时图片不在持久历史中。",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("maxLength", 500))
            .put("message_index", JSONObject().put("type", "integer").put("minimum", 0))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
            .put("max_chars", JSONObject().put("type", "integer").put("minimum", 256).put("maximum", 8000))),
    )
}
