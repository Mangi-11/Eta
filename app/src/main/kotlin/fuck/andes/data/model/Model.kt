package fuck.andes.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val id: String,
    val modelId: String,
    val displayName: String,
    val ownedBy: String? = null,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val contextWindow: Int? = null,
    val inputModalities: List<String> = listOf(TEXT_MODALITY),
    val outputModalities: List<String> = listOf(TEXT_MODALITY),
    val attachment: Boolean? = null,
    val toolCall: Boolean? = null,
    val reasoning: Boolean? = null,
    val structuredOutput: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val source: ModelSource = ModelSource.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
) {
    val supportsVision: Boolean
        get() = attachment == true || inputModalities.any { it.equals(IMAGE_MODALITY, ignoreCase = true) }

    val supportsTools: Boolean
        get() = toolCall == true

    val supportsReasoning: Boolean
        get() = reasoning == true

    companion object {
        const val TEXT_MODALITY = "text"
        const val IMAGE_MODALITY = "image"
    }
}

@Serializable
enum class ModelSource {
    MANUAL,
    REMOTE,
    CATALOG,
}
