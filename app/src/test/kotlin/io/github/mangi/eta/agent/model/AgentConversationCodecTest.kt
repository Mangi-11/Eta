package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.archive.AgentLegacyConversationProjection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.openai.OpenAiRequestMessages
import io.github.mangi.eta.agent.model.provider.openai.ResponsesEphemeralState

class AgentConversationCodecTest {
    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "先分析工具参数")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先分析工具参数", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "屏幕观察",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "结果-${"y".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "最终答案"))
        }

        val encoded = AgentLegacyConversationProjection.encode(messages, AgentLegacyConversationProjection.DIRECT_CHARS)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertTrue(decoded.last().content.contains("最终答案"))
    }

    @Test
    fun conversationCheckpointPreservesEveryMessageBeyondLegacyBudget() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "继续处理最新任务"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length > 96_000)
        assertEquals(messages, decoded)
        assertEquals("继续处理最新任务", decoded.last().content)
    }

    @Test
    fun responsesOutputItemsStayInMemoryAndNeverEnterStableTranscript() {
        val source = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            source,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque-secret"),
            ),
        )
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertTrue(ResponsesEphemeralState.outputItems(history) != null)

        val stable = AgentConversationCodec.durableMessage(history)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(listOf(stable))
        assertFalse(encoded.contains("opaque-secret"))
        assertFalse(encoded.contains("_eta_responses_output_items"))
    }

    @Test
    fun assistantHistoryOmitsBlankReasoningContent() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "hello")
            .put("reasoning_content", "")

        val history = AgentConversationCodec.assistantHistoryMessage(assistant, emptyList())

        assertFalse(history.has("reasoning_content"))
    }

    @Test
    fun assistantHistoryOmitsNullReasoningContent() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "hello")
            .put("reasoning_content", JSONObject.NULL)

        val history = AgentConversationCodec.assistantHistoryMessage(assistant, emptyList())

        assertFalse(history.has("reasoning_content"))
    }

    @Test
    fun assistantHistoryPreservesNonBlankReasoningContent() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "hello")
            .put("reasoning_content", "先分析")

        val history = AgentConversationCodec.assistantHistoryMessage(assistant, emptyList())

        assertEquals("先分析", history.getString("reasoning_content"))
    }

    private fun mistralMultiTurnHistory(reasoning: Any?): JSONArray {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "hello")
        if (reasoning != null) assistant.put("reasoning_content", reasoning)
        return JSONArray()
            .put(JSONObject().put("role", "user").put("content", "hi"))
            .put(AgentConversationCodec.assistantHistoryMessage(assistant, emptyList()))
            .put(JSONObject().put("role", "user").put("content", "again"))
    }

    @Test
    fun chatCompletionsRequestOmitsBlankReasoningContent() {
        val request = OpenAiRequestMessages.forChatCompletions(mistralMultiTurnHistory(""))
        assertFalse(request.getJSONObject(1).has("reasoning_content"))
    }

    @Test
    fun chatCompletionsRequestDropsBlankReasoningFromRawHistory() {
        val history = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "hi"))
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "hello")
                    .put("reasoning_content", "")
            )
            .put(JSONObject().put("role", "user").put("content", "again"))

        val request = OpenAiRequestMessages.forChatCompletions(history)

        assertFalse(request.getJSONObject(1).has("reasoning_content"))
    }

    @Test
    fun chatCompletionsRequestPreservesNonBlankReasoningContent() {
        val request = OpenAiRequestMessages.forChatCompletions(mistralMultiTurnHistory("先分析"))
        assertEquals("先分析", request.getJSONObject(1).getString("reasoning_content"))
    }
}
