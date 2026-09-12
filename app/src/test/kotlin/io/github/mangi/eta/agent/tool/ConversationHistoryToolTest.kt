package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryToolTest {
    @Test fun pagesReconstructEveryCharacterOfLargeMessage() {
        val message = AgentModelClient.ConversationMessage("assistant", "事实😀".repeat(15_000))
        var loads = 0
        val tool = ConversationHistoryTool { loads++; listOf(message) }
        val rebuilt = StringBuilder()
        var index = 0
        var offset = 0
        do {
            val result = JSONObject(tool.execute(AgentModelClient.ToolCall("call", "conversation_history",
                JSONObject().put("message_index", index).put("offset", offset).put("max_chars", 8000).toString())).content)
            val entries = result.getJSONArray("entries")
            for (i in 0 until entries.length()) rebuilt.append(entries.getJSONObject(i).getString("text"))
            index = result.getInt("next_message_index")
            offset = result.getInt("next_offset")
        } while (result.getBoolean("has_more"))
        assertEquals(AgentConversationCodec.toJsonObject(message).toString(), rebuilt.toString())
        assertEquals(1, loads)
    }

    @Test fun searchReturnsOnlyMatchingCurrentSessionMessages() {
        val tool = ConversationHistoryTool { listOf(
            AgentModelClient.ConversationMessage("user", "旧请求"),
            AgentModelClient.ConversationMessage("tool", "关键标识 FileABC"),
        ) }
        val result = JSONObject(tool.execute(AgentModelClient.ToolCall("c", "conversation_history", """{"query":"fileabc"}""")).content)
        assertEquals(1, result.getJSONArray("entries").length())
        assertEquals(1, result.getJSONArray("entries").getJSONObject(0).getInt("message_index"))
    }
}
