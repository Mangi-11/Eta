package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Grok 目录中的展示标识、推理标识和协议声明独立于通用 OpenAI 模型目录。 */
internal object GrokModelCatalog {
    fun parse(
        entries: JsonArray,
        decode: (JsonObject) -> Model,
        onUnsupportedProtocols: (Int) -> Unit = {},
    ): List<Model> {
        var unsupported = 0
        var visible = 0
        val models = entries.mapIndexedNotNull { index, element ->
            val item = element.jsonObjectOrNull() ?: error("Grok 模型目录包含无效模型项")
            val meta = item["_meta"]?.jsonObjectOrNull()
            if ((item.boolean("hidden") ?: meta?.boolean("hidden")) == true) return@mapIndexedNotNull null
            visible++
            val backend = item["apiBackend"] ?: item["api_backend"]
            if (backend != null && backend != JsonNull &&
                (backend as? JsonPrimitive)?.takeIf { it.isString }?.content != "chat_completions"
            ) {
                unsupported++
                return@mapIndexedNotNull null
            }
            val id = item.nonBlankString("model", "modelId", "id")
                ?: meta?.nonBlankString("model", "modelId")
                ?: error("Grok 模型目录缺少模型标识")
            val normalized = JsonObject(item + ("id" to JsonPrimitive(id)))
            val base = decode(normalized)
            val reasoning = item.grokReasoningCapabilities(meta)
            base.copy(
                displayName = item.nonBlankString("name") ?: base.displayName,
                contextWindow = item.positiveInt("contextWindow", "context_window")
                    ?: meta?.positiveInt("contextWindow", "totalContextTokens") ?: base.contextWindow,
                reasoning = reasoning?.let { true } ?: base.reasoning,
                reasoningCapabilities = reasoning ?: base.reasoningCapabilities,
                sortOrder = index,
            )
        }
        if (unsupported > 0) onUnsupportedProtocols(unsupported)
        require(visible == 0 || unsupported != visible) {
            "当前订阅连接不使用目录指定的模型协议，无法导入这些模型"
        }
        return models
    }

    private fun JsonObject.grokReasoningCapabilities(meta: JsonObject?): ModelReasoningCapabilities? {
        val explicit = (this["reasoningEfforts"] ?: this["reasoning_efforts"] ?: meta?.get("reasoningEfforts"))
            ?.jsonArrayOrNull()?.mapNotNull { element ->
                val option = element.jsonObjectOrNull()
                val value = option?.nonBlankString("value") ?: (element as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                val effort = grokEffort(value) ?: return@mapNotNull null
                effort to (option?.boolean("default") == true)
            }.orEmpty()
        val capabilities = this["capabilities"]?.jsonObjectOrNull()
        val levels = if (explicit.isNotEmpty()) explicit.map { it.first } else {
            capabilities?.stringList("reasoning_effort").orEmpty().mapNotNull(::grokEffort)
        }.distinct()
        val declared = boolean("supportsReasoningEffort", "supports_reasoning_effort")
            ?: meta?.boolean("supportsReasoningEffort")
        if (levels.isEmpty() && declared != true) return null
        val default = if (explicit.isNotEmpty()) {
            explicit.firstOrNull { it.second }?.first
        } else {
            grokEffort(capabilities?.nonBlankString("default_reasoning_effort"))?.takeIf { it in levels }
        }
        val canDisable = ReasoningEffort.OFF in levels
        return ModelReasoningCapabilities(
            supportedEfforts = levels.filter { it != ReasoningEffort.OFF },
            defaultEffort = default,
            defaultEnabled = default?.enablesReasoning,
            mandatory = levels.isNotEmpty() && !canDisable,
            canDisable = canDisable,
        )
    }

    private fun grokEffort(value: String?): ReasoningEffort? = when (value) {
        "none" -> ReasoningEffort.OFF
        "minimal", "low", "medium", "high", "xhigh", "max" -> ReasoningEffort.fromWireValue(value)
        else -> null
    }

    private fun JsonObject.nonBlankString(vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotBlank)
        }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonObject.boolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.booleanOrNull }

    private fun JsonObject.positiveInt(vararg names: String): Int? =
        names.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.intOrNull?.takeIf { value -> value > 0 } }

    private fun JsonObject.stringList(name: String): List<String>? =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
}
