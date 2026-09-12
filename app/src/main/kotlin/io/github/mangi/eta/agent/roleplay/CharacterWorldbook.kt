package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal data class WorldbookProjection(
    val beforeCharacter: String = "",
    val afterCharacter: String = "",
    val usedTokens: Int = 0,
)

internal object CharacterWorldbook {
    fun unsupportedEntries(card: CharacterCard): List<UnsupportedWorldbookEntry> =
        (card.characterBook?.get("entries") as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
            val entry = value as? JsonObject ?: return@mapIndexedNotNull null
            CharacterWorldbookSupport.reasons(entry).takeIf { it.isNotEmpty() }?.let { UnsupportedWorldbookEntry(index, it) }
        }

    private data class Entry(val index: Int, val data: JsonObject) {
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val content = data.text("content")
        val constant = data.bool("constant") ?: false
        val order = data.number("insertion_order") ?: 0
        val priority = data.number("priority") ?: 0
        val before = when (extensions.number("position")) {
            0 -> true
            1 -> false
            else -> data.text("position") == "before_char"
        }
    }

    fun resolve(
        card: CharacterCard,
        messages: List<String>,
        inputTokenBudget: Int,
        estimateTokens: (String) -> Int,
    ): WorldbookProjection {
        val book = card.characterBook ?: return WorldbookProjection()
        val available = inputTokenBudget.coerceAtLeast(0)
        val budget = (book.number("token_budget") ?: (available / 4)).coerceIn(0, available)
        if (budget == 0) return WorldbookProjection()
        val depth = (book.number("scan_depth") ?: 2).coerceAtLeast(0)
        val recursive = book.bool("recursive_scanning") ?: false
        val entries = (book["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
            val entry = value as? JsonObject ?: return@mapIndexedNotNull null
            if (entry.bool("enabled") == false) return@mapIndexedNotNull null
            if (CharacterWorldbookSupport.reasons(entry).isNotEmpty()) return@mapIndexedNotNull null
            Entry(index, entry).takeIf { it.content.isNotBlank() }
        }
        val matched = linkedMapOf<Int, Entry>()
        do {
            val priorSize = matched.size
            val recursiveText = if (recursive) matched.values.joinToString("\n") { it.content } else ""
            entries.forEach { entry ->
                if (entry.index in matched) return@forEach
                val localDepth = (entry.extensions.number("scan_depth") ?: depth).coerceAtLeast(0)
                val scanned = messages.takeLast(localDepth).joinToString("\n") + "\n" + recursiveText
                val caseSensitive = entry.data.bool("case_sensitive") ?: entry.extensions.bool("case_sensitive") ?: false
                val primary = entry.data.strings("keys").any { matches(it, scanned, caseSensitive) }
                val secondary = entry.data.bool("selective") != true ||
                    entry.data.strings("secondary_keys").any { matches(it, scanned, caseSensitive) }
                if (entry.constant || primary && secondary) matched[entry.index] = entry
            }
        } while (recursive && matched.size > priorSize)

        val selected = mutableListOf<Entry>()
        var tokens = 0
        matched.values.sortedWith(
            compareByDescending<Entry> { it.constant }.thenByDescending { it.priority }.thenBy { it.order }.thenBy { it.index },
        ).forEach { entry ->
            val candidate = (selected + entry).sortedWith(compareBy<Entry> { it.order }.thenBy { it.index })
            val before = candidate.filter { it.before }.joinToString("\n\n") { it.content }
            val after = candidate.filterNot { it.before }.joinToString("\n\n") { it.content }
            val cost = estimateTokens(before).coerceAtLeast(0).toLong() + estimateTokens(after).coerceAtLeast(0)
            if (cost <= budget) {
                selected += entry
                tokens = cost.toInt()
            }
        }
        val ordered = selected.sortedWith(compareBy<Entry> { it.order }.thenBy { it.index })
        return WorldbookProjection(
            beforeCharacter = ordered.filter { it.before }.joinToString("\n\n") { it.content },
            afterCharacter = ordered.filterNot { it.before }.joinToString("\n\n") { it.content },
            usedTokens = tokens,
        )
    }

    private fun matches(key: String, text: String, caseSensitive: Boolean): Boolean {
        if (key.isEmpty()) return false
        return text.contains(key, ignoreCase = !caseSensitive)
    }

    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
