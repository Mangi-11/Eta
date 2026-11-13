package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun searchInLargeMessageReturnsFocusedSnippetAndCanBeExpandedByIndex() {
        val largeContent = "无关日志。".repeat(3_600) + "目标标识 PACKAGE_XYZ_123 与错误码 E42" + "后续日志。".repeat(2_000)
        val message = AgentModelClient.ConversationMessage("tool", largeContent, toolCallId = "call_large")
        val tool = ConversationHistoryTool { listOf(message) }

        val searchResult = JSONObject(
            tool.execute(
                AgentModelClient.ToolCall("search", "conversation_history", """{"query":"PACKAGE_XYZ_123"}"""),
            ).content,
        )
        val entries = searchResult.getJSONArray("entries")
        assertEquals(1, entries.length())
        val entry = entries.getJSONObject(0)
        assertEquals(0, entry.getInt("message_index"))
        assertTrue(entry.getBoolean("snippet"))
        assertFalse(entry.getBoolean("complete"))
        assertTrue(entry.getInt("offset") > 0)
        assertTrue(entry.getString("text").contains("PACKAGE_XYZ_123"))
        assertTrue(entry.getString("text").length <= 1_200)
        assertEquals(AgentConversationCodec.toJsonObject(message).toString().length, entry.getInt("total_chars"))

        val expanded = JSONObject(
            tool.execute(
                AgentModelClient.ToolCall(
                    "expand",
                    "conversation_history",
                    JSONObject().put("message_index", 0).put("offset", entry.getInt("offset")).put("max_chars", 2_000).toString(),
                ),
            ).content,
        )
        assertTrue(expanded.getJSONArray("entries").getJSONObject(0).getString("text").contains("PACKAGE_XYZ_123"))
    }

    @Test fun searchAcrossMultipleLargeMessagesReturnsMultipleMatchingSnippets() {
        val messages = (0 until 3).map { index ->
            AgentModelClient.ConversationMessage(
                role = "tool",
                content = "前缀数据".repeat(2_500) + "HIT_MARKER_$index" + "后缀数据".repeat(500),
                toolCallId = "call_$index",
            )
        }
        val tool = ConversationHistoryTool { messages }
        val result = JSONObject(
            tool.execute(AgentModelClient.ToolCall("s", "conversation_history", """{"query":"HIT_MARKER_"}""")).content,
        )
        val entries = result.getJSONArray("entries")
        assertEquals(3, entries.length())
        assertEquals(listOf(0, 1, 2), (0 until entries.length()).map { entries.getJSONObject(it).getInt("message_index") })
        (0 until entries.length()).forEach { idx ->
            assertTrue(entries.getJSONObject(idx).getString("text").contains("HIT_MARKER_$idx"))
        }
    }
}
