package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.model.provider.AgentModelFailure
import io.github.mangi.eta.agent.model.provider.AnthropicEphemeralState
import io.github.mangi.eta.agent.model.provider.gemini.GeminiEphemeralState

/** 签名工具链完成前保持请求前缀，避免压缩或目录刷新破坏下一轮回传。 */
internal object SignedToolContext {
    fun hasPendingResponse(messages: JSONArray): Boolean =
        AnthropicEphemeralState.hasPendingToolResponse(messages) ||
            GeminiEphemeralState.hasPendingToolResponse(messages)

    fun compactionFailure(messages: JSONArray): AgentModelFailure =
        if (GeminiEphemeralState.hasPendingToolResponse(messages)) {
            AgentModelFailure(
                "GEMINI_THINKING_CONTEXT_LOCKED",
                false,
                "当前 Gemini 工具链尚未完成，提交工具结果前无法压缩带签名的上下文。",
            )
        } else {
            AgentModelFailure(
                "ANTHROPIC_THINKING_CONTEXT_LOCKED",
                false,
                "当前 Anthropic 工具回合的思考签名绑定原始上下文，提交工具结果前无法压缩上下文。",
            )
        }
}
