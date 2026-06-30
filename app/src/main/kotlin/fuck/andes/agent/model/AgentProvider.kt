package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentProviderClient {
    val id: String
    val capabilities: ProviderCapabilities

    fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit = {}
    ): ProviderResponse
}

internal data class ProviderCapabilities(
    val endpoint: EndpointKind,
    val streamingText: Boolean,
    val streamingToolCalls: Boolean,
    val imageInput: Boolean,
    val toolResultImages: Boolean,
    val strictTools: Boolean,
    val parallelToolCalls: Boolean
)

internal enum class EndpointKind {
    CHAT_COMPLETIONS,
    RESPONSES
}

internal data class ProviderRequest(
    val config: AgentModelClient.ModelConfig,
    val messages: JSONArray,
    val tools: JSONArray
)

internal data class ProviderResponse(
    val assistantMessage: JSONObject
)

internal sealed interface ProviderEvent {
    data object RequestStarted : ProviderEvent

    data class ResponseHeaders(
        val httpCode: Int
    ) : ProviderEvent

    data class TextDelta(
        val delta: String
    ) : ProviderEvent

    data class ReasoningDelta(
        val delta: String
    ) : ProviderEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String
    ) : ProviderEvent

    data class Usage(
        val usage: AgentTokenUsage
    ) : ProviderEvent

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
