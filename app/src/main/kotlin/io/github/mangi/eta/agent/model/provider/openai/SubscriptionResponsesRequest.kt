package io.github.mangi.eta.agent.model.provider.openai

import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.provider.AgentModelClient

/** 订阅连接在无服务端存储的 Responses 中显式携带推理状态。 */
internal object SubscriptionResponsesRequest {
    fun configure(request: JSONObject, config: AgentModelClient.ModelConfig) {
        val profile = ProviderConnectionProfile.forAuthMode(config.authMode) ?: return
        if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
            val unsupported = CODEX_UNSUPPORTED_FIELDS.filter(request::has)
            require(unsupported.isEmpty()) { "Codex 不支持请求参数：${unsupported.joinToString()}" }
            if (!request.has("parallel_tool_calls")) request.put("parallel_tool_calls", true)
        }
        if (profile == ProviderConnectionProfile.XAI_GROK) {
            request.optJSONArray("tools")?.let { tools ->
                for (index in 0 until tools.length()) {
                    val tool = tools.optJSONObject(index) ?: continue
                    if (tool.optString("type") != "function") continue
                    val parameters = tool.optJSONObject("parameters") ?: continue
                    tool.put("parameters", GrokToolSchema.project(parameters))
                }
            }
        }
        if (profile == ProviderConnectionProfile.CHATGPT_PLAN) {
            PLAN_UNSUPPORTED_FIELDS.forEach(request::remove)
            val tools = request.optJSONArray("tools")
            if (tools != null) {
                val functions = JSONArray()
                val grouped = JSONArray()
                for (index in 0 until tools.length()) {
                    val tool = tools.getJSONObject(index)
                    if (tool.optString("type") == "function") functions.put(tool) else grouped.put(tool)
                }
                if (functions.length() > 0) {
                    grouped.put(JSONObject()
                        .put("type", "namespace")
                        .put("name", ETA_NAMESPACE)
                        .put("description", "Tools executed locally by Eta")
                        .put("tools", functions))
                }
                request.put("tools", grouped)
            }
            val input = request.getJSONArray("input")
            for (index in 0 until input.length()) {
                val item = input.optJSONObject(index) ?: continue
                if (item.optString("type") == "function_call" && !item.has("namespace")) {
                    item.put("namespace", ETA_NAMESPACE)
                }
            }
        }
        val include = request.optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == "reasoning.encrypted_content" }) {
            include.put("reasoning.encrypted_content")
        }
        request.put("include", include)
    }

    private const val ETA_NAMESPACE = "eta"
    private val PLAN_UNSUPPORTED_FIELDS = setOf(
        "background", "conversation", "max_output_tokens", "max_tokens", "max_tool_calls", "metadata",
        "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier",
        "temperature", "top_logprobs", "top_p", "truncation", "user",
    )
    private val CODEX_UNSUPPORTED_FIELDS = setOf("max_output_tokens", "max_tokens", "temperature", "top_p", "background", "conversation")
}
