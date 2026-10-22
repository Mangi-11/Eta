package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Codex 账号目录是独立协议，不按公共 API 的预设或 supported_in_api 筛选。 */
internal object CodexModelCatalog {
    fun parse(entries: JsonArray): List<Model> = entries.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: error("Codex 模型目录包含无效模型项")
        when (item.string("visibility")) {
            "list" -> Unit
            "hide", "none" -> return@mapIndexedNotNull null
            else -> error("Codex 模型目录缺少有效的可见性信息")
        }
        val slug = item.string("slug")?.takeIf(String::isNotBlank)
            ?: error("Codex 模型目录缺少模型标识")
        val levels = item["supported_reasoning_levels"]?.let { raw ->
            require(raw is JsonArray) { "Codex 模型目录的推理档位无效" }
            raw.mapNotNull { option -> effort((option as? JsonObject)?.string("effort")) }.distinct()
        }.orEmpty()
        val default = effort(item.string("default_reasoning_level"))?.takeIf { it in levels }
        val reasoning = levels.filter { it != ReasoningEffort.OFF }
        val modalities = item["input_modalities"]?.let { raw ->
            require(raw is JsonArray) { "Codex 模型目录的输入类型无效" }
            raw.map { value ->
                require(value is JsonPrimitive && value.isString) { "Codex 模型目录的输入类型无效" }
                value.content
            }
        } ?: listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY)
        Model(
            id = UUID.randomUUID().toString(),
            modelId = slug,
            displayName = item.string("display_name")?.takeIf(String::isNotBlank) ?: slug,
            source = ModelSource.REMOTE,
            ownedBy = "openai",
            sortOrder = (item["priority"] as? JsonPrimitive)?.intOrNull ?: index,
            contextWindow = item.positiveInt("context_window") ?: item.positiveInt("max_context_window"),
            inputModalities = modalities,
            outputModalities = listOf(Model.TEXT_MODALITY),
            toolCall = true,
            reasoning = reasoning.isNotEmpty(),
            reasoningCapabilities = if (reasoning.isEmpty()) null else ModelReasoningCapabilities(
                supportedEfforts = reasoning,
                defaultEffort = default,
                defaultEnabled = default?.enablesReasoning,
                canDisable = ReasoningEffort.OFF in levels,
                mandatory = ReasoningEffort.OFF !in levels,
            ),
            supportsTemperature = false,
        )
    }.sortedBy(Model::sortOrder).mapIndexed { index, model -> model.copy(sortOrder = index) }

    private fun effort(value: String?): ReasoningEffort? = when (value) {
        "none" -> ReasoningEffort.OFF
        "minimal", "low", "medium", "high", "xhigh", "max" -> ReasoningEffort.fromWireValue(value)
        else -> null
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun JsonObject.positiveInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
}
