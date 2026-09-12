package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class RoleplayBinding(
    val characterId: String,
    val cardSnapshotJson: String,
    val characterName: String,
    val avatarPath: String? = null,
    val userName: String = "用户",
    val userDescription: String = "",
)

@Serializable
internal data class UserPersona(val name: String = "用户", val description: String = "")

internal enum class CharacterCardFormat { PNG, JSON }

internal data class CharacterProfile(
    val id: String,
    val card: CharacterCard,
    val avatarPath: String? = null,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class CharacterDepthPrompt(val prompt: String, val depth: Int, val role: String)

/** 原始树保留未实现的字段，界面编辑仅覆盖所属字段。 */
internal data class CharacterCard(val raw: JsonObject) {
    val data: JsonObject get() = raw["data"] as? JsonObject ?: raw
    val name get() = data.text("name")
    val description get() = data.text("description")
    val personality get() = data.text("personality")
    val scenario get() = data.text("scenario")
    val firstMessage get() = data.text("first_mes")
    val alternateGreetings get() = data.strings("alternate_greetings")
    val exampleMessages get() = data.text("mes_example")
    val systemPrompt get() = data.text("system_prompt")
    val postHistoryInstructions get() = data.text("post_history_instructions")
    val creatorNotes get() = data.text("creator_notes").ifEmpty { data.text("creatorcomment") }
    val tags get() = data.strings("tags")
    val creator get() = data.text("creator")
    val version get() = data.text("character_version")
    val nickname get() = data.text("nickname").ifBlank { name }
    val characterBook get() = data["character_book"] as? JsonObject
    val extensions get() = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
    fun worldbookDraft(): CharacterBookDraft = CharacterWorldbookDraftCodec.read(this)
    fun withWorldbook(draft: CharacterBookDraft): CharacterCard = CharacterWorldbookDraftCodec.write(this, draft)
    val depthPrompt: CharacterDepthPrompt?
        get() {
            val value = extensions["depth_prompt"] as? JsonObject ?: return null
            val prompt = value.text("prompt").takeIf(String::isNotBlank) ?: return null
            val depth = if (value["depth"] == null) 4 else (value["depth"] as? JsonPrimitive)?.intOrNull ?: return null
            val role = value.text("role").ifEmpty { "system" }
            if (depth < 0 || role !in setOf("system", "user", "assistant")) return null
            return CharacterDepthPrompt(prompt, depth, role)
        }

    fun withEdits(
        name: String = this.name,
        description: String = this.description,
        personality: String = this.personality,
        scenario: String = this.scenario,
        firstMessage: String = this.firstMessage,
        alternateGreetings: List<String> = this.alternateGreetings,
        exampleMessages: String = this.exampleMessages,
        systemPrompt: String = this.systemPrompt,
        postHistoryInstructions: String = this.postHistoryInstructions,
        creatorNotes: String = this.creatorNotes,
        tags: List<String> = this.tags,
        creator: String = this.creator,
        version: String = this.version,
    ): CharacterCard {
        val updated = data.toMutableMap().apply {
            if (name != this@CharacterCard.name) put("name", JsonPrimitive(name))
            if (description != this@CharacterCard.description) put("description", JsonPrimitive(description))
            if (personality != this@CharacterCard.personality) put("personality", JsonPrimitive(personality))
            if (scenario != this@CharacterCard.scenario) put("scenario", JsonPrimitive(scenario))
            if (firstMessage != this@CharacterCard.firstMessage) put("first_mes", JsonPrimitive(firstMessage))
            if (alternateGreetings != this@CharacterCard.alternateGreetings) put("alternate_greetings", JsonArray(alternateGreetings.map(::JsonPrimitive)))
            if (exampleMessages != this@CharacterCard.exampleMessages) put("mes_example", JsonPrimitive(exampleMessages))
            if (systemPrompt != this@CharacterCard.systemPrompt) put("system_prompt", JsonPrimitive(systemPrompt))
            if (postHistoryInstructions != this@CharacterCard.postHistoryInstructions) put("post_history_instructions", JsonPrimitive(postHistoryInstructions))
            if (creatorNotes != this@CharacterCard.creatorNotes) put("creator_notes", JsonPrimitive(creatorNotes))
            if (tags != this@CharacterCard.tags) put("tags", JsonArray(tags.map(::JsonPrimitive)))
            if (creator != this@CharacterCard.creator) put("creator", JsonPrimitive(creator))
            if (version != this@CharacterCard.version) put("character_version", JsonPrimitive(version))
        }
        return CharacterCard(JsonObject(raw.toMutableMap().apply {
            put("data", JsonObject(updated))
            updated.forEach { (key, value) -> if (containsKey(key) && data[key] != value) put(key, value) }
            if (containsKey("creatorcomment") && creatorNotes != this@CharacterCard.creatorNotes) {
                put("creatorcomment", JsonPrimitive(creatorNotes))
            }
        }))
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
