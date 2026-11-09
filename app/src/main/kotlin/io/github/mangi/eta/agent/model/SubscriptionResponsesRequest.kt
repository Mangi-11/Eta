package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import org.json.JSONArray
import org.json.JSONObject

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
        val include = request.optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == "reasoning.encrypted_content" }) {
            include.put("reasoning.encrypted_content")
        }
        request.put("include", include)
    }

    private val CODEX_UNSUPPORTED_FIELDS = setOf("max_output_tokens", "max_tokens", "temperature", "top_p", "background", "conversation")
}
