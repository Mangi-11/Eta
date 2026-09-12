package io.github.mangi.eta.agent.roleplay

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CharacterMacros {
    const val MAX_EXPANDED_CHARS = 2 * 1024 * 1024
    // Android 的 ICU 正则要求字面量右花括号也转义，否则对象初始化会失败。
    private val token = Regex("\\{\\{([^{}]+)\\}\\}|<(USER|CHAR|BOT)>", RegexOption.IGNORE_CASE)
    private val opening = Regex("\\{\\{\\s*([^\\s:{}]+)")

    fun hasUnsupportedMacros(text: String, card: CharacterCard): Boolean {
        val names = values(card, "用户", "", "").keys
        return token.findAll(text).any { match ->
            val key = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).trim().lowercase(Locale.ROOT)
            !key.startsWith("//") && key !in names
        } || opening.findAll(text).any { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            !key.startsWith("//") && key !in names
        }
    }

    fun expand(
        text: String,
        card: CharacterCard,
        userName: String = "用户",
        userDescription: String = "",
        original: String = "",
    ): String {
        val values = values(card, userName, userDescription, original)
        fun resolve(source: String, active: Set<String>, depth: Int): String {
            checkSize(source.length)
            if (depth >= 8) return source
            val output = StringBuilder(minOf(source.length, 8192))
            var offset = 0
            token.findAll(source).forEach { match ->
                appendBounded(output, source, offset, match.range.first)
                val key = (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).trim().lowercase(Locale.ROOT)
                val replacement = when {
                    key.startsWith("//") -> ""
                    key in active -> match.value
                    key in values -> resolve(values.getValue(key), active + key, depth + 1)
                    else -> match.value
                }
                appendBounded(output, replacement)
                offset = match.range.last + 1
            }
            appendBounded(output, source, offset, source.length)
            return output.toString()
        }
        return resolve(text, emptySet(), 0)
    }

    private fun appendBounded(output: StringBuilder, value: String, start: Int = 0, end: Int = value.length) {
        checkSize(output.length.toLong() + end - start)
        output.append(value, start, end)
    }

    private fun checkSize(length: Number) {
        if (length.toLong() > MAX_EXPANDED_CHARS) {
            throw CharacterCardException("CARD_MACRO_EXPANSION_LIMIT", "角色宏展开超过长度上限，请精简循环引用或重复宏")
        }
    }

    private fun values(card: CharacterCard, userName: String, userDescription: String, original: String): Map<String, String> {
        val now = LocalDateTime.now()
        return mapOf(
            "char" to card.nickname,
            "user" to userName.ifBlank { "用户" },
            "bot" to card.nickname,
            "original" to original,
            "description" to card.description,
            "personality" to card.personality,
            "scenario" to card.scenario,
            "persona" to userDescription,
            "mesexamples" to card.exampleMessages,
            "mesexamplesraw" to card.exampleMessages,
            "charfirstmessage" to card.firstMessage,
            "charprompt" to card.systemPrompt,
            "charinstruction" to card.postHistoryInstructions,
            "chardepthprompt" to card.depthPrompt?.prompt.orEmpty(),
            "group" to card.nickname,
            "charifnotgroup" to card.nickname,
            "notchar" to userName.ifBlank { "用户" },
            "date" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "isodate" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "isotime" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "weekday" to now.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)),
            "newline" to "\n",
            "noop" to "",
        )
    }
}
