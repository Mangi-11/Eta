package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CharacterCardCompatibility {
    private val html = Regex(
        "<\\s*/?\\s*(?:script|iframe|style|div|span|details|summary|html|body|button|input|img|audio|video|canvas|table|p|br|a)(?=[\\s/>])",
        RegexOption.IGNORE_CASE,
    )

    fun warnings(card: CharacterCard): List<String> = buildList {
        val fields = listOf(card.description, card.personality, card.scenario, card.firstMessage,
            card.exampleMessages, card.systemPrompt, card.postHistoryInstructions) + card.alternateGreetings +
            ((card.extensions["depth_prompt"] as? JsonObject)?.text("prompt") ?: "") +
            (card.characterBook?.get("entries") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("content") }
        if (fields.any { CharacterMacros.hasUnsupportedMacros(it, card) }) {
            add("含未支持的宏，已保留原文，不执行变量、条件或脚本操作。")
        }
        if (fields.any { html.containsMatchIn(it) }) {
            add("含 HTML 或脚本界面标记，按文本保留，不运行交互界面。")
        }
        val extensionKeys = mutableSetOf<String>()
        collectExtensionKeys(card.extensions, extensionKeys, 0)
        if (extensionKeys.any { it.contains("regex") }) add("含正则替换扩展，数据会保留，替换规则不执行。")
        if (extensionKeys.any { it.contains("script") }) add("含脚本扩展，数据会保留，脚本不执行。")
        card.extensions["depth_prompt"]?.takeUnless { it == JsonNull }?.let { value ->
            if (value !is JsonObject || value.text("prompt").isNotBlank() && card.depthPrompt == null) {
                add("角色深度备注的参数未受支持，已跳过该备注。")
            }
        }
        val unsupported = CharacterWorldbook.unsupportedEntries(card)
        if (unsupported.isNotEmpty()) add("${unsupported.size} 条世界书使用未支持的触发条件，已跳过这些条目。")
        if ((card.data["assets"] as? JsonArray)?.isNotEmpty() == true) add("附带资源清单已保留，本版使用角色卡主图，不加载额外资源。")
        if (card.data.strings("group_only_greetings").isNotEmpty()) add("群聊专用开场白已保留，本版仅用于单角色对话。")
    }

    private fun collectExtensionKeys(value: JsonElement, keys: MutableSet<String>, depth: Int) {
        if (depth >= 4 || value !is JsonObject) return
        value.forEach { (key, child) ->
            val present = when (child) {
                JsonNull -> false
                is JsonArray -> child.isNotEmpty()
                is JsonObject -> child.isNotEmpty()
                is JsonPrimitive -> child.content.isNotBlank() && child.content != "false"
            }
            if (present) keys += key.lowercase()
            collectExtensionKeys(child, keys, depth + 1)
        }
    }
}
