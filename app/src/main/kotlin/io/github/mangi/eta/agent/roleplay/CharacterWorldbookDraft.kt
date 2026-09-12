package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal data class CharacterBookDraft(
    val name: String = "",
    val scanDepth: Int? = null,
    val recursiveScanning: Boolean? = null,
    val tokenBudget: Int? = null,
    val entries: List<CharacterBookEntryDraft> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

internal data class CharacterBookEntryDraft(
    val name: String = "",
    val content: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val position: String = "after_char",
    val insertionOrder: Int = 0,
    val raw: JsonObject = JsonObject(emptyMap()),
)

internal object CharacterWorldbookDraftCodec {
    fun read(card: CharacterCard): CharacterBookDraft {
        val book = card.characterBook ?: return CharacterBookDraft()
        return CharacterBookDraft(
            name = book.text("name"),
            scanDepth = (book["scan_depth"] as? JsonPrimitive)?.intOrNull,
            recursiveScanning = (book["recursive_scanning"] as? JsonPrimitive)?.booleanOrNull,
            tokenBudget = (book["token_budget"] as? JsonPrimitive)?.intOrNull,
            entries = (book["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
                val entry = value as? JsonObject ?: return@mapIndexedNotNull null
                val extensionPosition = ((entry["extensions"] as? JsonObject)?.get("position") as? JsonPrimitive)?.intOrNull
                CharacterBookEntryDraft(
                    name = entry.text("name").ifEmpty { entry.text("comment") },
                    content = entry.text("content"),
                    keys = entry.strings("keys"),
                    secondaryKeys = entry.strings("secondary_keys"),
                    enabled = (entry["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    constant = (entry["constant"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    selective = (entry["selective"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    position = when (extensionPosition) {
                        0 -> "before_char"
                        1 -> "after_char"
                        else -> entry.text("position").ifEmpty { "after_char" }
                    },
                    insertionOrder = (entry["insertion_order"] as? JsonPrimitive)?.intOrNull ?: index,
                    raw = entry,
                )
            },
            raw = book,
        )
    }

    fun write(card: CharacterCard, draft: CharacterBookDraft): CharacterCard {
        require(draft.scanDepth == null || draft.scanDepth >= 0) { "扫描深度不能为负数" }
        require(draft.tokenBudget == null || draft.tokenBudget >= 0) { "世界书预算不能为负数" }
        val book = draft.raw.toMutableMap().apply {
            if (draft.name != draft.raw.text("name")) put("name", JsonPrimitive(draft.name))
            if (draft.scanDepth != (draft.raw["scan_depth"] as? JsonPrimitive)?.intOrNull) {
                if (draft.scanDepth == null) remove("scan_depth") else put("scan_depth", JsonPrimitive(draft.scanDepth))
            }
            if (draft.tokenBudget != (draft.raw["token_budget"] as? JsonPrimitive)?.intOrNull) {
                if (draft.tokenBudget == null) remove("token_budget") else put("token_budget", JsonPrimitive(draft.tokenBudget))
            }
            if (draft.recursiveScanning != (draft.raw["recursive_scanning"] as? JsonPrimitive)?.booleanOrNull) {
                if (draft.recursiveScanning == null) remove("recursive_scanning") else put("recursive_scanning", JsonPrimitive(draft.recursiveScanning))
            }
            if (draft.raw.isEmpty()) put("extensions", JsonObject(emptyMap()))
            if (draft.raw.isEmpty() || draft.raw.containsKey("entries") || draft.entries.isNotEmpty()) put("entries", JsonArray(draft.entries.map { entry ->
                require(entry.position in setOf("before_char", "after_char") || entry.position == entry.raw.text("position")) {
                    "不支持的世界书插入位置"
                }
                JsonObject(entry.raw.toMutableMap().apply {
                    val fresh = entry.raw.isEmpty()
                    if (entry.name != entry.raw.text("name").ifEmpty { entry.raw.text("comment") }) {
                        put("name", JsonPrimitive(entry.name))
                        if (containsKey("comment")) put("comment", JsonPrimitive(entry.name))
                    }
                    if (fresh || entry.content != entry.raw.text("content")) put("content", JsonPrimitive(entry.content))
                    if (fresh || entry.keys != entry.raw.strings("keys")) put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
                    if (entry.secondaryKeys != entry.raw.strings("secondary_keys")) put("secondary_keys", JsonArray(entry.secondaryKeys.map(::JsonPrimitive)))
                    if (fresh || entry.enabled != ((entry.raw["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)) put("enabled", JsonPrimitive(entry.enabled))
                    if (entry.constant != ((entry.raw["constant"] as? JsonPrimitive)?.booleanOrNull ?: false)) put("constant", JsonPrimitive(entry.constant))
                    if (entry.selective != ((entry.raw["selective"] as? JsonPrimitive)?.booleanOrNull ?: false)) put("selective", JsonPrimitive(entry.selective))
                    if (fresh || (entry.raw["insertion_order"] as? JsonPrimitive)?.intOrNull?.let { it != entry.insertionOrder } == true) {
                        put("insertion_order", JsonPrimitive(entry.insertionOrder))
                    }
                    val extensions = (get("extensions") as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                    val storedPosition = (extensions["position"] as? JsonPrimitive)?.intOrNull
                    val originalPosition = when (storedPosition) {
                        0 -> "before_char"
                        1 -> "after_char"
                        else -> entry.raw.text("position").ifEmpty { "after_char" }
                    }
                    if (fresh || entry.position != originalPosition) {
                        put("position", JsonPrimitive(entry.position))
                        if (extensions.containsKey("position")) extensions["position"] = JsonPrimitive(if (entry.position == "before_char") 0 else 1)
                    }
                    if (fresh || containsKey("extensions")) put("extensions", JsonObject(extensions))
                })
            }))
        }
        return CharacterCard(JsonObject(card.raw.toMutableMap().apply {
            put("data", JsonObject(card.data.toMutableMap().apply { put("character_book", JsonObject(book)) }))
        }))
    }
}
