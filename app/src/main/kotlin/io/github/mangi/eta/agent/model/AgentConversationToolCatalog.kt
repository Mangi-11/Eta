package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 只声明当前会话的历史检索，不允许模型指定其他会话身份。 */
internal object AgentConversationToolCatalog {
    const val READ_HISTORY = "conversation_history"

    fun schema(): JSONObject = AgentToolSchema.function(
        name = READ_HISTORY,
        description = "检索或展开当前会话的完整脱敏历史（包含已被上下文压缩归档折叠的早期消息）。" +
            "传 query 时按关键词检索并返回命中片段与 message_index、offset、total_chars；" +
            "省略 query 时按 message_index 和 offset 按需展开指定消息原文或顺序分页读取。沿 next_message_index 和 next_offset 继续，" +
            "续读固定同一份历史，从零开始可刷新；不要把分页片段误认为完整历史。敏感工具原文和瞬时图片不在持久历史中。",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("maxLength", 500))
            .put("message_index", JSONObject().put("type", "integer").put("minimum", 0))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
            .put("max_chars", JSONObject().put("type", "integer").put("minimum", 256).put("maximum", 8000))),
    )
}
