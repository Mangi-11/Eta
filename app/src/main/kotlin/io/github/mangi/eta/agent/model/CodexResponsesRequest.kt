package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONArray
import org.json.JSONObject

/** Codex 使用无服务端存储的 Responses，请求约束在编码边界统一处理。 */
internal object CodexResponsesRequest {
    fun configure(request: JSONObject, config: AgentModelClient.ModelConfig) {
        if (config.authMode != ProviderAuthModes.OPENAI_CHATGPT) return
        val unsupported = UNSUPPORTED_FIELDS.filter(request::has)
        require(unsupported.isEmpty()) { "Codex 不支持请求参数：${unsupported.joinToString()}" }
        val include = request.optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == "reasoning.encrypted_content" }) {
            include.put("reasoning.encrypted_content")
        }
        request.put("include", include)
        if (!request.has("parallel_tool_calls")) request.put("parallel_tool_calls", true)
    }

    private val UNSUPPORTED_FIELDS = setOf("max_output_tokens", "max_tokens", "temperature", "top_p", "background", "conversation")
}
