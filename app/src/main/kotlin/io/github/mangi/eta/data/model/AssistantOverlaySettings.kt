package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 全局对话浮窗助手（电源键 / 系统助手唤起）的模型与思考等级配置。
 *
 * [modelId] 为空表示跟随当前主模型；指定具体 [Model.id] 且对应服务商与模型仍启用时，
 * 浮窗助手优先使用该模型运行。[reasoningEffort] 为空表示跟随默认思考配置。
 */
@Serializable
internal data class AssistantOverlaySettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
