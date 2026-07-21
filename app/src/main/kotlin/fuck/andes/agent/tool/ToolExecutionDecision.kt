package fuck.andes.agent.tool

internal sealed interface ToolExecutionDecision {
    data object Allow : ToolExecutionDecision

    data class Reject(
        val code: String,
        val message: String,
    ) : ToolExecutionDecision
}
