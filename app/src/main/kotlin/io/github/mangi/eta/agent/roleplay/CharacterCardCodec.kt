package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object CharacterCardCodec {
    const val MAX_CARD_BYTES = 16 * 1024 * 1024
    private val json = Json { prettyPrint = true }
    private val textFields = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version",
    )

    fun create(name: String): CharacterCard = decodeJson(JsonObject(mapOf("name" to JsonPrimitive(name))).toString())

    fun decodeBytes(bytes: ByteArray): CharacterCard {
        if (bytes.size > MAX_CARD_BYTES) throw CharacterCardException("CARD_TOO_LARGE", "角色卡超过 16 MiB 限制")
        val source = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) {
            throw CharacterCardException("CARD_INVALID_UTF8", "角色卡必须使用有效的 UTF-8 编码", failure)
        }
        return decodeJson(source)
    }

    fun decodeJson(source: String): CharacterCard {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_CARD_BYTES) throw CharacterCardException("CARD_TOO_LARGE", "角色卡超过 16 MiB 限制")
        val root = try {
            json.parseToJsonElement(source.removePrefix("\uFEFF")) as? JsonObject
        } catch (failure: IllegalArgumentException) {
            throw CharacterCardException("CARD_INVALID_JSON", "角色卡 JSON 格式无效", failure)
        } ?: throw CharacterCardException("CARD_INVALID_JSON", "角色卡必须是 JSON 对象")
        val spec = root.text("spec")
        if (spec.isNotEmpty() && spec !in setOf("chara_card_v2", "chara_card_v3")) {
            throw CharacterCardException("CARD_UNSUPPORTED_SPEC", "不支持的角色卡规范")
        }
        val originalData = if (spec.isEmpty()) root else root["data"] as? JsonObject
            ?: throw IllegalArgumentException("角色卡缺少 data 对象")
        require(originalData.text("name").isNotBlank()) { "角色卡缺少名称" }
        textFields.forEach { field ->
            val value = originalData[field]
            require(value == null || value is JsonPrimitive && value.isString) { "角色卡字段 $field 必须是文本" }
        }
        listOf("tags", "alternate_greetings", "group_only_greetings").forEach { field ->
            val value = originalData[field]
            require(value == null || value is JsonArray && value.all { it is JsonPrimitive && it.isString }) {
                "角色卡字段 $field 必须是文本数组"
            }
        }
        require(originalData["extensions"] == null || originalData["extensions"] is JsonObject) { "角色扩展格式无效" }
        require(originalData["character_book"] == null || originalData["character_book"] is JsonObject) { "角色世界书格式无效" }
        (originalData["character_book"] as? JsonObject)?.let(::validateBook)
        if (spec.isNotEmpty()) return CharacterCard(root)
        val data = originalData.toMutableMap().apply {
            textFields.forEach { putIfAbsent(it, JsonPrimitive("")) }
            if (spec.isEmpty() && originalData["creator_notes"] == null) {
                put("creator_notes", JsonPrimitive(originalData.text("creatorcomment")))
            }
            putIfAbsent("alternate_greetings", JsonArray(emptyList()))
            putIfAbsent("tags", JsonArray(emptyList()))
            putIfAbsent("extensions", JsonObject(emptyMap()))
        }
        return CharacterCard(JsonObject(root.toMutableMap().apply {
            put("spec", JsonPrimitive(spec.ifEmpty { "chara_card_v2" }))
            put("spec_version", root["spec_version"] ?: JsonPrimitive(if (spec == "chara_card_v3") "3.0" else "2.0"))
            put("data", JsonObject(data))
        }))
    }

    fun encodeJson(card: CharacterCard): String = json.encodeToString(JsonObject.serializer(), card.raw)

    fun exportView(card: CharacterCard, version: Int): String {
        require(version == 2 || version == 3)
        val fields = card.data.toMutableMap()
        textFields.forEach { fields.putIfAbsent(it, JsonPrimitive("")) }
        fields.putIfAbsent("tags", JsonArray(emptyList()))
        fields.putIfAbsent("alternate_greetings", JsonArray(emptyList()))
        fields.putIfAbsent("extensions", JsonObject(emptyMap()))
        if (version == 3) fields.putIfAbsent("group_only_greetings", JsonArray(emptyList()))
        val root = card.raw.toMutableMap().apply {
            put("spec", JsonPrimitive("chara_card_v$version"))
            put("spec_version", JsonPrimitive("$version.0"))
            put("data", JsonObject(fields))
            // 旧卡可能同时带顶层镜像，导出时保持同名字段一致。
            textFields.forEach { key -> if (containsKey(key)) fields[key]?.let { put(key, it) } }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    private fun validateBook(book: JsonObject) {
        listOf("scan_depth", "token_budget").forEach { field ->
            book[field]?.let { value ->
                require(value is JsonPrimitive && !value.isString && value.intOrNull?.let { it >= 0 } == true) { "世界书 $field 必须为非负整数" }
            }
        }
        book["recursive_scanning"]?.let { value ->
            require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "世界书递归设置无效" }
        }
        require(book["extensions"] == null || book["extensions"] is JsonObject) { "世界书扩展格式无效" }
        val entries = book["entries"] ?: return
        require(entries is JsonArray) { "世界书条目必须为数组" }
        entries.forEachIndexed { index, value ->
            require(value is JsonObject) { "世界书第 ${index + 1} 项不是对象" }
            listOf("keys", "secondary_keys").forEach { field ->
                value[field]?.let { keys ->
                    require(keys is JsonArray && keys.all { it is JsonPrimitive && it.isString }) { "世界书第 ${index + 1} 项关键字格式无效" }
                }
            }
            value["content"]?.let { require(it is JsonPrimitive && it.isString) { "世界书正文必须为文本" } }
            listOf("enabled", "constant", "selective", "case_sensitive", "use_regex").forEach { field ->
                value[field]?.let { flag -> require(flag is JsonPrimitive && !flag.isString && flag.booleanOrNull != null) { "世界书第 ${index + 1} 项开关格式无效" } }
            }
            listOf("insertion_order", "priority").forEach { field ->
                value[field]?.let { number -> require(number is JsonPrimitive && !number.isString && number.doubleOrNull?.isFinite() == true) { "世界书第 ${index + 1} 项顺序或优先级无效" } }
            }
            require(value["extensions"] == null || value["extensions"] is JsonObject) { "世界书条目扩展格式无效" }
        }
    }
}
