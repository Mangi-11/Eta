package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.model.provider.openai.OpenAiRequestMessages

class ChatToolCallHistoryTest {
    @Test
    fun repeatedBatchesKeepEveryResultPairedWithoutChangingSource() {
        val source = JSONArray()
            .put(message("system", "规则"))
            .put(assistant("tool_call_0"))
            .put(result("tool_call_0", "第一轮结果"))
            .put(message("user", "继续"))
            .put(assistant("tool_call_0", "unique"))
            .put(result("unique", "第二轮另一个结果"))
            .put(result("tool_call_0", "第二轮结果"))
            .put(assistant("tool_call_0"))
            .put(result("tool_call_0", "第三轮结果"))
        val original = source.toString()

        val projected = OpenAiRequestMessages.forChatCompletions(source)
        val ids = callIds(projected)

        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("tool_call_0", ids[0])
        assertEquals("unique", ids[2])
        assertEquals(ids[0], resultId(projected, "第一轮结果"))
        assertEquals(ids[1], resultId(projected, "第二轮结果"))
        assertEquals(ids[2], resultId(projected, "第二轮另一个结果"))
        assertEquals(ids[3], resultId(projected, "第三轮结果"))
        assertEquals(source.length(), projected.length())
        assertEquals("先执行工具", projected.getJSONObject(4).getString("content"))
        assertEquals("{\"fixture\":true}", projected.getJSONObject(4)
            .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals(original, source.toString())
        assertEquals(projected.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())

        ChatToolCallHistory.repairCompleteBatches(projected)
        assertEquals(ids, callIds(projected))
    }

    @Test
    fun distinctIdsAndUnrelatedMessageFieldsStayUnchanged() {
        val source = JSONArray()
            .put(message("user", "请求"))
            .put(assistant("first").put("reasoning_content", "思考"))
            .put(result("first", "结果"))
            .put(assistant("second"))
            .put(result("second", "另一个结果"))

        assertEquals(source.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun replacementsAvoidLaterCallIdsAndOrphanResultIds() {
        val source = JSONArray()
            .put(assistant("duplicate"))
            .put(result("duplicate", "第一轮结果"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "待修复结果"))
            .put(assistant("call_eta_history_2_0"))
            .put(result("call_eta_history_2_0", "后续结果"))
            .put(message("user", "边界"))
            .put(result("call_eta_history_2_0_1", "孤立结果"))
        val reserved = ChatToolCallHistory.referencedIds(source)

        val projected = OpenAiRequestMessages.forChatCompletions(source)
        val replacement = resultId(projected, "待修复结果")

        assertFalse(replacement in reserved)
        assertEquals(callIds(projected)[1], replacement)
        assertEquals("call_eta_history_2_0", resultId(projected, "后续结果"))
        assertEquals("call_eta_history_2_0_1", resultId(projected, "孤立结果"))
        assertEquals(projected.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun resultsCannotCrossAnyMessageBoundaryEvenWhenSystemMessagesAreMerged() {
        for (role in listOf("user", "system", "assistant", "developer")) {
            val source = JSONArray()
                .put(assistant("duplicate"))
                .put(result("duplicate", "完整结果"))
                .put(assistant("duplicate"))
                .put(message(role, "边界"))
                .put(result("duplicate", "跨边界结果"))
            val original = source.toString()

            val projected = OpenAiRequestMessages.forChatCompletions(source)

            assertEquals(role, listOf("duplicate", "duplicate"), callIds(projected))
            assertEquals(role, "duplicate", resultId(projected, "跨边界结果"))
            assertEquals(role, original, source.toString())
        }
    }

    @Test
    fun nonObjectMessagesKeepTheirPairingBoundaryAndOriginalIndicesDuringRepair() {
        val source = JSONArray()
            .put(assistant("duplicate"))
            .put(result("duplicate", "完整结果"))
            .put(assistant("duplicate"))
            .put(JSONArray().put("invalid-message"))
            .put(result("duplicate", "跨边界结果"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "后续完整结果"))
        val original = source.toString()

        val projected = OpenAiRequestMessages.forChatCompletions(source)

        assertEquals(listOf("duplicate", "duplicate", "call_eta_history_5_0"), callIds(projected))
        assertEquals("duplicate", resultId(projected, "跨边界结果"))
        assertEquals("call_eta_history_5_0", resultId(projected, "后续完整结果"))
        assertEquals(original, source.toString())
    }

    @Test
    fun malformedAndAmbiguousBatchesStayUntouched() {
        val invalidBatches = listOf(
            JSONArray().put(assistant("duplicate", "duplicate"))
                .put(result("duplicate", "结果一")).put(result("duplicate", "结果二")),
            JSONArray().put(assistant("duplicate", "missing"))
                .put(result("duplicate", "缺少一个结果")),
            JSONArray().put(assistant("duplicate")),
            JSONArray().put(assistant("duplicate")).put(result("duplicate", "结果"))
                .put(result("extra", "多余结果")),
            JSONArray().put(assistant("duplicate")).put(result("duplicate", "结果"))
                .put(result("duplicate", "重复结果")),
            JSONArray().put(assistant("duplicate")).put(result("other", "不匹配的结果")),
            JSONArray().put(assistant("duplicate")).put(result("", "空结果 ID")),
            JSONArray().put(assistant("duplicate")).put(result(" ", "空白结果 ID")),
            JSONArray().put(assistant("duplicate")).put(message("tool", "数字结果 ID").put("tool_call_id", 1)),
            JSONArray().put(assistant("duplicate")).put(message("tool", "缺失结果 ID")),
            JSONArray().put(assistant("duplicate")).put("invalid-message").put(result("duplicate", "跨边界结果")),
            JSONArray().put(message("assistant", "无调用")).put(result("duplicate", "孤立结果")),
            JSONArray().put(message("assistant", "畸形调用列表").put("tool_calls", "invalid"))
                .put(result("duplicate", "孤立结果")),
            JSONArray().put(message("assistant", "空调用列表").put("tool_calls", JSONArray()))
                .put(result("duplicate", "孤立结果")),
        ) + listOf("", " ", 1, JSONObject.NULL).map { invalidId ->
            JSONArray().put(message("assistant", "畸形 ID").put("tool_calls", JSONArray()
                .put(call("duplicate")).put(call("unused").put("id", invalidId))))
                .put(result("duplicate", "结果"))
        } + listOf(
            JSONArray().put(message("assistant", "缺少 ID").put("tool_calls", JSONArray()
                .put(call("duplicate")).put(JSONObject().put("type", "function"))))
                .put(result("duplicate", "结果")),
            JSONArray().put(message("assistant", "畸形调用").put("tool_calls", JSONArray()
                .put(call("duplicate")).put("invalid-call")))
                .put(result("duplicate", "结果")),
        )

        invalidBatches.forEachIndexed { index, invalid ->
            val messages = JSONArray().put(assistant("duplicate")).put(result("duplicate", "先前结果"))
            for (itemIndex in 0 until invalid.length()) messages.put(invalid.get(itemIndex))
            val before = messages.toString()

            ChatToolCallHistory.repairCompleteBatches(messages)

            assertEquals("invalid batch $index", before, messages.toString())
        }
    }

    @Test
    fun incompleteEarlierBatchRemainsUntouchedWhileLaterCompletePairGetsDistinctId() {
        val source = JSONArray()
            .put(assistant("duplicate"))
            .put(message("user", "新的请求"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "已完整配对的结果"))

        val projected = OpenAiRequestMessages.forChatCompletions(source)
        val ids = callIds(projected)

        assertEquals("duplicate", ids[0])
        assertNotEquals(ids[0], ids[1])
        assertEquals(ids[1], resultId(projected, "已完整配对的结果"))
        assertEquals(source.length(), projected.length())
    }

    @Test
    fun referencedIdsOnlyIncludesNonBlankStringsInCallAndResultRoles() {
        val source = JSONArray()
            .put(assistant("real", "", " "))
            .put(message("assistant", "畸形调用").put("tool_calls", JSONArray()
                .put(JSONObject().put("id", 123)).put(JSONObject().put("id", JSONObject.NULL)).put("invalid")))
            .put(result("orphan", "结果"))
            .put(result("", "空 ID"))
            .put(message("tool", "非字符串 ID").put("tool_call_id", true))
            .put(message("user", "无关字段").put("tool_call_id", "ignored")
                .put("tool_calls", JSONArray().put(call("also_ignored"))))

        assertEquals(setOf("real", "orphan"), ChatToolCallHistory.referencedIds(source))
    }

    @Test
    fun responsesInstructionsDoNotRepairOrModifyMessages() {
        val source = JSONArray()
            .put(message("system", "规则"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "结果一"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "结果二"))
        val before = source.toString()

        assertEquals("规则", OpenAiRequestMessages.responsesInstructions(source))
        assertEquals(before, source.toString())
        assertTrue(callIds(source).all { it == "duplicate" })
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun assistant(vararg ids: String): JSONObject =
        message("assistant", "先执行工具").put("tool_calls", JSONArray().also { calls ->
            ids.forEach { calls.put(call(it)) }
        })

    private fun call(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "function")
        .put("function", JSONObject().put("name", "fixture_tool").put("arguments", "{\"fixture\":true}"))

    private fun result(id: String, content: String): JSONObject =
        message("tool", content).put("tool_call_id", id)

    private fun callIds(messages: JSONArray): List<String> = buildList {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) add(calls.getJSONObject(callIndex).getString("id"))
        }
    }

    private fun resultId(messages: JSONArray, content: String): String =
        (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "tool" && it.optString("content") == content }
            .getString("tool_call_id")
}
