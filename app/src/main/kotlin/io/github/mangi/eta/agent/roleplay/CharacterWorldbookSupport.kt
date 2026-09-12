package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal data class UnsupportedWorldbookEntry(val index: Int, val reasons: List<String>)

/** 同一判定同时用于导入后的能力说明和每轮投影，未支持的条件不退化为无条件触发。 */
internal object CharacterWorldbookSupport {
    fun reasons(entry: JsonObject): List<String> = buildList {
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val useRegex = (entry["use_regex"] as? JsonPrimitive)?.booleanOrNull != false
        if (useRegex && (entry.strings("keys") + entry.strings("secondary_keys")).any(::regexKey)) add("正则关键字")
        if (entry.text("content").lineSequence().any { it.trimStart().startsWith("@@") }) add("世界书装饰器")
        val position = (extensions["position"] as? JsonPrimitive)?.intOrNull
        if (extensions["position"].let { it != null && it != JsonNull } && (position == null || position !in 0..1)) add("特殊插入位置")
        if (entry.text("position").let { it.isNotEmpty() && it !in setOf("before_char", "after_char") }) add("特殊插入位置")
        if (extensions.nonzero("selectiveLogic")) add("高级次级匹配")
        val probabilityEnabled = (extensions["useProbability"] as? JsonPrimitive)?.booleanOrNull != false
        val probability = (extensions["probability"] as? JsonPrimitive)?.doubleOrNull
        if (probabilityEnabled && probability != null && probability != 100.0) add("概率触发")
        if (extensions.nonzero("group")) add("条目分组")
        if (listOf("sticky", "cooldown", "delay", "delay_until_recursion").any { extensions.nonzero(it) }) add("时序触发")
        if (listOf("exclude_recursion", "prevent_recursion", "ignore_budget", "match_whole_words", "vectorized",
                "match_persona_description", "match_character_description", "match_character_personality",
                "match_character_depth_prompt", "match_scenario", "match_creator_notes")
            .any { (extensions[it] as? JsonPrimitive)?.booleanOrNull == true }) add("扩展匹配条件")
        if ((extensions["triggers"] as? JsonArray)?.isNotEmpty() == true) add("指定生成类型")
        if (extensions.text("automation_id").isNotBlank()) add("脚本自动化")
    }.distinct()

    private fun regexKey(key: String): Boolean {
        val slash = key.lastIndexOf('/')
        return key.startsWith('/') && slash > 0 && key.substring(slash + 1).all(Char::isLetter)
    }

    private fun JsonObject.nonzero(key: String): Boolean {
        val value = get(key)
        if (value == null || value == JsonNull) return false
        return (value as? JsonPrimitive)?.let {
            it.booleanOrNull ?: it.doubleOrNull?.let { number -> number != 0.0 } ?: it.content.isNotBlank()
        } ?: (value as? JsonArray)?.isNotEmpty() ?: true
    }
}
