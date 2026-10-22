package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ProviderAuthModes

internal object AgentRuntimeRequestConfigResolver {
    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        usesDefaultRuntimeConfig(request) || ProviderAuthModes.isOAuth(request.config.authMode)

    fun usesDefaultRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

    /**
     * 只用 Runtime 侧的模型与凭据替换入口配置；工具开关保留入口请求的值，
     * 随后仍由 AgentRuntimePolicy 与本地权限取交集。模型配置本身不携带工具开关，
     * 直接整体替换会让默认关闭的终端、敏感读取与敏感操作工具在语音入口始终不可用。
     */
    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        runtimeConfig: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        val entry = request.config
        val preserveReasoningChoice = !usesDefaultRuntimeConfig(request)
        if (preserveReasoningChoice) {
            require(
                runtimeConfig.providerId == entry.providerId &&
                    runtimeConfig.authMode == entry.authMode && runtimeConfig.model == entry.model
            ) { "账号登录配置已变更，请重新选择服务商和模型" }
        }
        val effort = if (preserveReasoningChoice) {
            runtimeConfig.reasoningCapabilities?.normalize(entry.effectiveReasoningEffort)
                ?: runtimeConfig.effectiveReasoningEffort
        } else runtimeConfig.effectiveReasoningEffort
        val config = runtimeConfig.copy(
            terminalTools = entry.terminalTools,
            browserTools = entry.browserTools,
            deviceDirectTools = entry.deviceDirectTools,
            deviceSensitiveReadTools = entry.deviceSensitiveReadTools,
            deviceSensitiveActionTools = entry.deviceSensitiveActionTools,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = runtimeConfig.autoCompactionEnabled && entry.autoCompactionEnabled,
        )
        val handoff = request.handoff ?: return request.copy(config = config)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }
}
