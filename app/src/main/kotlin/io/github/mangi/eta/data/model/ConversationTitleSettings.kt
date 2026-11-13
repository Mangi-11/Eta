package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 自动生成对话标题配置。
 *
 * [modelId] 为空表示跟随当前对话使用的模型（或默认模型）；指定具体 [Model.id] 且对应
 * 服务商与模型仍启用时，优先使用该模型生成标题，便于用轻量小模型降低 Token 消耗。
 * [reasoningEffort] 为空表示自动选择最低可用思考等级（优先关闭思考）。
 */
@Serializable
internal data class ConversationTitleSettings(
    val enabled: Boolean = true,
    val modelId: String? = null,
    val reasoningEffort: String? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
