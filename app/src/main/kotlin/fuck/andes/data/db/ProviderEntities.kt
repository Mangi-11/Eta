package fuck.andes.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.CustomBody
import fuck.andes.data.model.CustomHeader
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.Model
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ProviderTypes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Entity(tableName = "model_providers")
internal data class ProviderEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "api_key") val apiKey: String,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean,
    @ColumnInfo(name = "is_built_in") val isBuiltIn: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String?,
    @ColumnInfo(name = "custom_headers_json") val customHeadersJson: String,
    @ColumnInfo(name = "custom_body_json") val customBodyJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "endpoint_mode") val endpointMode: String,
    @ColumnInfo(name = "anthropic_version") val anthropicVersion: String,
)

@Entity(
    tableName = "provider_models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("provider_id"),
        Index(value = ["provider_id", "sort_order"]),
    ],
)
internal data class ProviderModelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean,
    @ColumnInfo(name = "is_built_in") val isBuiltIn: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "supports_vision") val supportsVision: Boolean,
    @ColumnInfo(name = "supports_tools") val supportsTools: Boolean,
    @ColumnInfo(name = "supports_reasoning") val supportsReasoning: Boolean,
    @ColumnInfo(name = "context_window") val contextWindow: Int?,
    @ColumnInfo(name = "custom_headers_json") val customHeadersJson: String,
    @ColumnInfo(name = "custom_body_json") val customBodyJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

internal data class ProviderWithModels(
    @Embedded val provider: ProviderEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "provider_id",
    )
    val models: List<ProviderModelEntity>,
)

internal fun ProviderSetting.toEntity(): ProviderEntity =
    ProviderEntity(
        id = id,
        type = storageType,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        isEnabled = isEnabled,
        isBuiltIn = isBuiltIn,
        sortOrder = sortOrder,
        systemPrompt = systemPrompt,
        customHeadersJson = ProviderJson.encodeHeaders(customHeaders),
        customBodyJson = ProviderJson.encodeBody(customBody),
        createdAt = createdAt,
        endpointMode = when (this) {
            is OpenAiCompatibleProviderSetting -> endpointMode
            is CustomProviderSetting -> endpointMode
            is AnthropicProviderSetting -> OpenAiEndpointMode.CHAT_COMPLETIONS
        },
        anthropicVersion = when (this) {
            is AnthropicProviderSetting -> anthropicVersion
            else -> AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION
        },
    )

internal fun ProviderSetting.toModelEntities(): List<ProviderModelEntity> =
    models.map { model -> model.toEntity(providerId = id) }

internal fun ProviderWithModels.toDomain(): ProviderSetting {
    val domainModels = models
        .sortedBy { it.sortOrder }
        .map(ProviderModelEntity::toDomain)
    return when (provider.type) {
        ProviderTypes.ANTHROPIC -> AnthropicProviderSetting(
            id = provider.id,
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            isEnabled = provider.isEnabled,
            isBuiltIn = provider.isBuiltIn,
            sortOrder = provider.sortOrder,
            systemPrompt = provider.systemPrompt,
            models = domainModels,
            customHeaders = ProviderJson.decodeHeaders(provider.customHeadersJson),
            customBody = ProviderJson.decodeBody(provider.customBodyJson),
            createdAt = provider.createdAt,
            anthropicVersion = provider.anthropicVersion.ifBlank {
                AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION
            },
        )

        ProviderTypes.CUSTOM -> CustomProviderSetting(
            id = provider.id,
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            isEnabled = provider.isEnabled,
            isBuiltIn = provider.isBuiltIn,
            sortOrder = provider.sortOrder,
            systemPrompt = provider.systemPrompt,
            models = domainModels,
            customHeaders = ProviderJson.decodeHeaders(provider.customHeadersJson),
            customBody = ProviderJson.decodeBody(provider.customBodyJson),
            createdAt = provider.createdAt,
            endpointMode = provider.endpointMode.ifBlank { OpenAiEndpointMode.CHAT_COMPLETIONS },
        )

        else -> OpenAiCompatibleProviderSetting(
            id = provider.id,
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            isEnabled = provider.isEnabled,
            isBuiltIn = provider.isBuiltIn,
            sortOrder = provider.sortOrder,
            systemPrompt = provider.systemPrompt,
            models = domainModels,
            customHeaders = ProviderJson.decodeHeaders(provider.customHeadersJson),
            customBody = ProviderJson.decodeBody(provider.customBodyJson),
            createdAt = provider.createdAt,
            endpointMode = provider.endpointMode.ifBlank { OpenAiEndpointMode.CHAT_COMPLETIONS },
        )
    }
}

private val ProviderSetting.storageType: String
    get() = when (this) {
        is OpenAiCompatibleProviderSetting -> ProviderTypes.OPENAI_COMPATIBLE
        is AnthropicProviderSetting -> ProviderTypes.ANTHROPIC
        is CustomProviderSetting -> ProviderTypes.CUSTOM
    }

private fun Model.toEntity(providerId: String): ProviderModelEntity =
    ProviderModelEntity(
        id = id,
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        isEnabled = isEnabled,
        isBuiltIn = isBuiltIn,
        sortOrder = sortOrder,
        supportsVision = supportsVision,
        supportsTools = supportsTools,
        supportsReasoning = supportsReasoning,
        contextWindow = contextWindow,
        customHeadersJson = ProviderJson.encodeHeaders(customHeaders),
        customBodyJson = ProviderJson.encodeBody(customBody),
        createdAt = createdAt,
    )

private fun ProviderModelEntity.toDomain(): Model =
    Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        isEnabled = isEnabled,
        isBuiltIn = isBuiltIn,
        sortOrder = sortOrder,
        supportsVision = supportsVision,
        supportsTools = supportsTools,
        supportsReasoning = supportsReasoning,
        contextWindow = contextWindow,
        customHeaders = ProviderJson.decodeHeaders(customHeadersJson),
        customBody = ProviderJson.decodeBody(customBodyJson),
        createdAt = createdAt,
    )

private object ProviderJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val headersSerializer = ListSerializer(CustomHeader.serializer())
    private val bodySerializer = ListSerializer(CustomBody.serializer())

    fun encodeHeaders(headers: List<CustomHeader>): String =
        json.encodeToString(headersSerializer, headers)

    fun decodeHeaders(raw: String): List<CustomHeader> =
        runCatching { json.decodeFromString(headersSerializer, raw) }.getOrDefault(emptyList())

    fun encodeBody(body: List<CustomBody>): String =
        json.encodeToString(bodySerializer, body)

    fun decodeBody(raw: String): List<CustomBody> =
        runCatching { json.decodeFromString(bodySerializer, raw) }.getOrDefault(emptyList())
}
