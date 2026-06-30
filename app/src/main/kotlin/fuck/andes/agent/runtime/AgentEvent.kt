package fuck.andes.agent.runtime

internal sealed interface AgentEvent {
    fun toLogLine(): String

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ProviderRequestStarted(
        val round: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantTextDelta(
        val round: Int,
        val deltaChars: Int,
        val delta: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_text_delta round=$round, chars=$deltaChars"
    }

    data class ProviderToolCallDelta(
        val round: Int,
        val index: Int,
        val name: String?,
        val argumentsChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_tool_call_delta round=$round, index=$index, name=$name, args_chars=$argumentsChars"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, tools=$toolNames"
    }

    data class ToolStarted(
        val round: Int,
        val name: String,
        val argsPreview: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=$name, args=$argsPreview"
    }

    data class ToolFinished(
        val round: Int,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=$name, $resultSummary, images=$imageCount, image_bytes=$imageBytes"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=$toolName, images=$imageCount, image_bytes=$imageBytes"
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason=${reason.replace('\n', ' ').replace('\r', ' ')}"
    }
}
