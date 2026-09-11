package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID

/** 用完整增量 transcript 构造已完成 run 的后续用户回合。 */
internal object AgentContinuationBuilder {
    fun build(
        request: AgentRuntimeWire.RunRequest,
        response: AgentModelClient.ModelResponse.Text,
        supplement: String,
        newRunId: String = "run-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
    ): AgentRuntimeWire.RunRequest {
        val baseHistory = request.history +
            AgentModelClient.buildUserHistoryMessage(request.prompt, request.images) +
            response.transcript
        val handoff = request.handoff?.let { original ->
            if (original.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                return@let original.copy(id = newRunId)
            }
            val payload = AgentUiHandoffPayload.from(original.payload)
            val nextSupplement = AgentUiHandoffPayload.Supplement(
                index = (payload.supplements.maxOfOrNull { it.index } ?: 0) + 1,
                text = supplement,
                createdAt = createdAt,
            )
            original.copy(
                id = newRunId,
                payload = AgentUiHandoffPayload(
                    conversationId = payload.conversationId,
                    promptSupplement = nextSupplement,
                ).toJson(),
            )
        }
        return request.copy(
            runId = newRunId,
            modelSessionId = request.effectiveModelSessionId,
            prompt = supplement,
            images = emptyList(),
            history = baseHistory,
            handoff = handoff,
        )
    }

}
