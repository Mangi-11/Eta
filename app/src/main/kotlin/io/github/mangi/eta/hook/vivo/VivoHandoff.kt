package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.json.JSONObject

internal object VivoHandoff {
    const val SOURCE = "vivo"

    fun create(
        runId: String,
        traceId: String,
        requestId: String,
        sessionId: String,
        prompt: String,
    ): AgentRuntimeWire.EntryHandoff = AgentRuntimeWire.EntryHandoff(
        id = runId,
        source = SOURCE,
        dismissEntrySurfaceOnForegroundOperation = true,
        payload = AgentExternalArchivePayload(
            userText = prompt,
            conversationKey = sessionId.ifBlank { traceId },
            title = "小 V：${prompt.lineSequence().first().take(20)}",
            adapterPayload = JSONObject()
                .put("traceId", traceId)
                .put("requestId", requestId)
                .put("sessionId", sessionId),
        ).toJson(),
    )
}
