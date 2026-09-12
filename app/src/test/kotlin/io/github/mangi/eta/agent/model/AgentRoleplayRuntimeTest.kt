package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRoleplayRuntimeTest {
    @Test
    fun continuationPromptAndLaterSteeringKeepTheirUiSupplementIdentifiers() {
        val controller = AgentRunController()
        controller.steer("后一条补充")
        val provider = provider { request ->
            val users = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                .filter { it.optString("role") == "user" }
            assertEquals(listOf("user-next-supplement-7", "user-next-supplement-8"),
                users.map { it.getString("_eta_message_id") })
            reply("完成补充")
        }
        val result = AgentModelClient.complete(
            config = config(), prompt = "最初续接", operationId = "next",
            initialUserMessageId = "user-next-supplement-7", initialSupplementIndex = 7,
            runController = controller, provider = provider, toolExecutor = { error("无需工具") },
        )
        assertEquals("user-next-supplement-8", result.transcript.first().messageId)
    }

    @Test
    fun characterReplacesIdentityButKeepsAgentToolsAndRealMemoryReadOnly() {
        val context = roleplay()
        val provider = provider { request ->
            val text = request.messages.toString()
            assertTrue(text.contains("林舟"))
            assertTrue(text.contains("工具合同"))
            assertFalse(text.contains("你是 Eta。"))
            assertFalse(text.contains("PROVIDER_IDENTITY"))
            val names = (0 until request.tools.length()).map {
                request.tools.getJSONObject(it).getJSONObject("function").getString("name")
            }
            assertTrue("observe_screen" in names)
            assertTrue("memory_get" in names)
            assertFalse("memory_write" in names)
            assertTrue(CharacterMemoryTools.WRITE in names)
            reply("我们走吧。")
        }
        val response = AgentModelClient.complete(
            config = config(), prompt = "同行", roleplayContext = context,
            memoryContext = AgentMemoryContextBuilder.empty(null),
            additionalTools = JSONArray().also(CharacterMemoryTools::appendSchemas),
            provider = provider, toolExecutor = { error("本次不应执行工具") },
            sessionId = "conversation", operationId = "run-one",
        )
        assertEquals("assistant-run-one-1", response.transcript.single().messageId)
    }

    @Test
    fun rewriteProducesOnlyTextWithoutReplacingMainContextOrExecutingTools() {
        var snapshots = 0
        val provider = provider { request ->
            assertEquals(ProviderRequestPurpose.REPLY_REWRITE, request.purpose)
            assertEquals(0, request.effectiveTools.length())
            assertFalse(request.effectiveConfig.hostedWebSearchEnabled)
            assertEquals("", request.effectiveConfig.extraBodyJson)
            assertTrue(request.messages.toString().contains("原来的实际结果"))
            reply("改写后的结果")
        }
        val result = AgentModelClient.complete(
            config = config().copy(hostedWebSearchEnabled = true, extraBodyJson = "{\"tools\":[{}]}"),
            prompt = "原来的实际结果", roleplayContext = roleplay(), rewriteReply = true,
            provider = provider, toolExecutor = { error("改写不能执行任何工具") },
            onContextSnapshot = { snapshots++ },
        )
        assertEquals("改写后的结果", result.content)
        assertNull(result.contextSnapshot)
        assertEquals(0, snapshots)
    }

    @Test
    fun unexpectedToolCallDuringRewriteFailsBeforeExecutionOrTranscriptCommit() {
        var executions = 0
        var transcriptCommits = 0
        val provider = provider {
            ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                .put("finish_reason", "tool_calls")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "danger")
                    .put("type", "function").put("function", JSONObject().put("name", "get_current_context")
                        .put("arguments", "{}")))))
        }
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = config(), prompt = "重新措辞", rewriteReply = true,
                provider = provider,
                toolExecutor = { executions++; AgentModelClient.ToolResult("{}") },
                onTranscript = { transcriptCommits++ },
            )
        }
        assertEquals("REPLY_REWRITE_TOOL_CALL", (failure.cause as AgentModelFailure).code)
        assertEquals(0, executions)
        assertEquals(0, transcriptCommits)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun worldbookUsesCurrentDialogueAndDepthNeverSplitsToolExchange() {
        val context = RoleplayRunContext(
            "fixture", CharacterCardCodec.decodeJson("""{
                "name":"林舟","description":"旅人",
                "character_book":{"scan_depth":1,"entries":[
                    {"keys":["钟楼"],"content":"塔顶住着信使","enabled":true}
                ]},"extensions":{"depth_prompt":{"prompt":"深度设定","depth":1,"role":"user"}}
            }"""), "用户", "", contextWindow = 16_000,
        )
        val source = JSONArray().put(context.personaMessage())
            .put(AgentConversationCodec.userTextMessage("在广场等候"))
            .put(JSONObject().put("role", "assistant").put("content", "钟楼")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call").put("content", "钟楼"))
        val original = source.toString()
        val first = context.projectMessages(source)
        assertFalse(first.toString().contains("塔顶住着信使"))
        assertEquals(original, source.toString())
        val toolCallIndex = (0 until first.length()).first { first.getJSONObject(it).has("tool_calls") }
        assertEquals("tool", first.getJSONObject(toolCallIndex + 1).getString("role"))
        source.put(AgentConversationCodec.userTextMessage("我们去钟楼"))
        assertTrue(context.projectMessages(source).toString().contains("塔顶住着信使"))
    }

    @Test
    fun worldbookDoesNotScanImageUrlsOrStructuredContentFieldNames() {
        val context = RoleplayRunContext("fixture", CharacterCardCodec.decodeJson("""{
            "name":"林舟","character_book":{"entries":[
                {"keys":["image_url"],"content":"不该被结构字段触发"},
                {"keys":["tower.png"],"content":"不该被图片链接触发"},
                {"keys":["钟楼"],"content":"正文触发的设定"}
            ]}
        }"""), "用户", "")
        val input = JSONArray().put(context.personaMessage()).put(JSONObject().put("role", "user").put("content",
            JSONArray().put(JSONObject().put("type", "text").put("text", "前往钟楼"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://example.com/tower.png")))))
        val persona = context.projectMessages(input).getJSONObject(0).getString("content")
        assertTrue(persona.contains("正文触发的设定"))
        assertFalse(persona.contains("不该被"))
    }

    @Test
    fun worldbookBudgetUsesRemainingInputAfterToolSchemas() {
        val lore = "世界知识".repeat(1_000)
        val card = CharacterCardCodec.decodeJson(JSONObject().put("name", "林舟")
            .put("character_book", JSONObject().put("entries", JSONArray().put(JSONObject()
                .put("constant", true).put("content", lore)))).toString())
        val context = RoleplayRunContext("fixture", card, "用户", "", contextWindow = 64_000)
        val source = JSONArray().put(context.personaMessage()).put(AgentConversationCodec.userTextMessage("出发"))
        assertTrue(context.projectMessages(source).toString().contains(lore))
        val tools = JSONArray().put(JSONObject().put("description", "工具约束".repeat(20_000)))
        assertFalse(context.projectMessages(source, tools).toString().contains(lore))
    }

    @Test
    fun providerProjectionKeepsDialogueDepthAndSystemTextWithoutInternalIdentifiers() {
        listOf("user", "assistant", "system").forEach { depthRole ->
            val card = CharacterCardCodec.decodeJson("""{
                "name":"林舟","extensions":{"depth_prompt":{"prompt":"深度设定","depth":1,"role":"$depthRole"}}
            }""")
            val context = RoleplayRunContext("fixture", card, "用户", "")
            val source = JSONArray().put(context.personaMessage())
                .put(AgentConversationCodec.userTextMessage("第一句").put("_eta_message_id", "first-id"))
                .put(JSONObject().put("role", "assistant").put("content", "第二句"))
            val projected = context.projectMessages(source)
            val chat = OpenAiRequestMessages.forChatCompletions(projected)
            val responses = ResponsesRequestBuilder.build(config(), projected, JSONArray())
            assertTrue(chat.toString().contains("深度设定"))
            assertTrue(responses.toString().contains("深度设定"))
            assertFalse(chat.toString().contains("_eta_message_id"))
            assertFalse(responses.toString().contains("_eta_message_id"))
            if (depthRole != "system") {
                val chatTexts = (0 until chat.length()).map { chat.getJSONObject(it).optString("content") }
                assertTrue(chatTexts.indexOf("第一句") < chatTexts.indexOf("深度设定"))
                assertTrue(chatTexts.indexOf("深度设定") < chatTexts.indexOf("第二句"))
                val input = responses.getJSONArray("input").toString()
                assertTrue(input.indexOf("第一句") < input.indexOf("深度设定"))
                assertTrue(input.indexOf("深度设定") < input.indexOf("第二句"))
            }
        }
    }

    @Test
    fun roleplaySummarySeparatesStoryFromRealExecution() {
        val provider = provider { request ->
            assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
            assertTrue(request.messages.toString().contains("虚构剧情与真实设备操作分开记录"))
            reply("剧情：二人在塔下相识。现实：尚未执行设备操作。")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "固定规则"))
        repeat(8) { index ->
            messages.put(AgentConversationCodec.userTextMessage("第$index 幕".repeat(300)))
            messages.put(JSONObject().put("role", "assistant").put("content", "剧情内容".repeat(300)))
        }
        val result = AgentContextCompactor(config(), provider, AgentRunController(), roleplay = true)
            .compact(messages, 1, emptySet(), force = true)
        assertTrue(result.toString().contains("剧情：二人在塔下相识"))
    }

    private fun roleplay() = RoleplayRunContext(
        "fixture", CharacterCardCodec.create("林舟").withEdits(description = "一位旅人"), "旅伴", "",
    )

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        systemPrompt = "PROVIDER_IDENTITY", browserTools = false,
    )

    private fun reply(text: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", "stop"))

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            block(request)
    }
}
