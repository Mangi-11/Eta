package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 中断记录中的未知结果显式标记，避免恢复时重放已执行工具或提交悬空 tool call。 */
internal object AgentToolBatchRecovery {
    fun completeInterrupted(messages: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> {
        val open = linkedSetOf<String>()
        val recovered = mutableListOf<AgentModelClient.ConversationMessage>()
        fun closeBatch() {
            open.forEach { id ->
                recovered += AgentModelClient.ConversationMessage(role = "tool", toolCallId = id,
                    content = JSONObject().put("ok", false).put("code", "TOOL_INTERRUPTED")
                        .put("message", "工具批次已中断，未取得该调用的完整结果；执行状态未知，请先核实，不要自动重放。").toString())
            }
            open.clear()
        }
        messages.forEach { message ->
            if (message.role != "tool") closeBatch()
            recovered += message
            if (message.toolCallsJson.isNotBlank()) {
                val calls = JSONArray(message.toolCallsJson)
                for (index in 0 until calls.length()) open += calls.getJSONObject(index).getString("id")
            }
            if (message.role == "tool") open.remove(message.toolCallId)
        }
        closeBatch()
        return recovered
    }
}
