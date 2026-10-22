package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
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
    @ColumnInfo(name = "hosted_web_search_enabled", defaultValue = "0")
    val hostedWebSearchEnabled: Boolean,
    @ColumnInfo(name = "anthropic_version") val anthropicVersion: String,
    @ColumnInfo(name = "auth_mode", defaultValue = "'api_key'")
    val authMode: String = ProviderAuthModes.API_KEY,
)

@Serializable
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
    @ColumnInfo(name = "owned_by") val ownedBy: String?,
    @ColumnInfo(name = "context_window") val contextWindow: Int?,
    @ColumnInfo(name = "context_window_override") val contextWindowOverride: Int?,
    @ColumnInfo(name = "input_modalities_json") val inputModalitiesJson: String,
    @ColumnInfo(name = "output_modalities_json") val outputModalitiesJson: String,
    @ColumnInfo(name = "attachment") val attachment: Boolean?,
    @ColumnInfo(name = "tool_call") val toolCall: Boolean?,
    @ColumnInfo(name = "reasoning") val reasoning: Boolean?,
    @ColumnInfo(name = "reasoning_capabilities_json", defaultValue = "'null'")
    val reasoningCapabilitiesJson: String = "null",
    @ColumnInfo(name = "reasoning_override") val reasoningOverride: Boolean?,
    @ColumnInfo(name = "reasoning_capabilities_override_json", defaultValue = "'null'")
    val reasoningCapabilitiesOverrideJson: String = "null",
    @ColumnInfo(name = "structured_output") val structuredOutput: Boolean?,
    @ColumnInfo(name = "supports_temperature") val supportsTemperature: Boolean?,
    @ColumnInfo(name = "custom_headers_json") val customHeadersJson: String,
    @ColumnInfo(name = "custom_body_json") val customBodyJson: String,
    val source: String,
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
        apiKey = apiKey.takeIf { authMode == ProviderAuthModes.API_KEY }.orEmpty(),
        authMode = authMode,
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
        hostedWebSearchEnabled = hostedWebSearchEnabled,
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
    val sourceType = ProviderConnectionProfile.forAuthMode(provider.authMode)?.sourceType ?: ProviderSourceRegistry.resolve(
        providerId = provider.id,
        baseUrl = provider.baseUrl,
        providerType = provider.type,
    )
    return when (provider.type) {
        ProviderTypes.ANTHROPIC -> AnthropicProviderSetting(
            id = provider.id,
            name = provider.name,
            baseUrl = provider.baseUrl,
            sourceType = sourceType,
            apiKey = provider.apiKey.takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
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
            sourceType = sourceType,
            apiKey = provider.apiKey.takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
            isEnabled = provider.isEnabled,
            isBuiltIn = provider.isBuiltIn,
            sortOrder = provider.sortOrder,
            systemPrompt = provider.systemPrompt,
            models = domainModels,
            customHeaders = ProviderJson.decodeHeaders(provider.customHeadersJson),
            customBody = ProviderJson.decodeBody(provider.customBodyJson),
            createdAt = provider.createdAt,
            endpointMode = provider.endpointMode.ifBlank { OpenAiEndpointMode.CHAT_COMPLETIONS },
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
        )

        else -> OpenAiCompatibleProviderSetting(
            id = provider.id,
            name = provider.name,
            baseUrl = provider.baseUrl,
            sourceType = sourceType,
            apiKey = provider.apiKey.takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
            isEnabled = provider.isEnabled,
            isBuiltIn = provider.isBuiltIn,
            sortOrder = provider.sortOrder,
            systemPrompt = provider.systemPrompt,
            models = domainModels,
            customHeaders = ProviderJson.decodeHeaders(provider.customHeadersJson),
            customBody = ProviderJson.decodeBody(provider.customBodyJson),
            createdAt = provider.createdAt,
            endpointMode = provider.endpointMode.ifBlank { OpenAiEndpointMode.CHAT_COMPLETIONS },
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
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
        ownedBy = ownedBy,
        contextWindow = contextWindow,
        contextWindowOverride = contextWindowOverride,
        inputModalitiesJson = ProviderJson.encodeStrings(inputModalities),
        outputModalitiesJson = ProviderJson.encodeStrings(outputModalities),
        attachment = attachment,
        toolCall = toolCall,
        reasoning = reasoning,
        reasoningCapabilitiesJson = ProviderJson.encodeReasoningCapabilities(reasoningCapabilities),
        reasoningOverride = reasoningOverride,
        reasoningCapabilitiesOverrideJson = ProviderJson.encodeReasoningCapabilities(
            reasoningCapabilitiesOverride
        ),
        structuredOutput = structuredOutput,
        supportsTemperature = supportsTemperature,
        customHeadersJson = ProviderJson.encodeHeaders(customHeaders),
        customBodyJson = ProviderJson.encodeBody(customBody),
        source = source.name.lowercase(),
        createdAt = createdAt,
    )

private fun ProviderModelEntity.toDomain(): Model =
    Model(
        id = id,
        modelId = modelId,
        displayName = displayName,
        ownedBy = ownedBy,
        isEnabled = isEnabled,
        isBuiltIn = isBuiltIn,
        sortOrder = sortOrder,
        contextWindow = contextWindow,
        contextWindowOverride = contextWindowOverride,
        inputModalities = ProviderJson.decodeStrings(inputModalitiesJson).ifEmpty {
            listOf(Model.TEXT_MODALITY)
        },
        outputModalities = ProviderJson.decodeStrings(outputModalitiesJson).ifEmpty {
            listOf(Model.TEXT_MODALITY)
        },
        attachment = attachment,
        toolCall = toolCall,
        reasoning = reasoning,
        reasoningCapabilities = ProviderJson.decodeReasoningCapabilities(reasoningCapabilitiesJson),
        reasoningOverride = reasoningOverride,
        reasoningCapabilitiesOverride = ProviderJson.decodeReasoningCapabilities(
            reasoningCapabilitiesOverrideJson
        ),
        structuredOutput = structuredOutput,
        supportsTemperature = supportsTemperature,
        customHeaders = ProviderJson.decodeHeaders(customHeadersJson),
        customBody = ProviderJson.decodeBody(customBodyJson),
        source = runCatching { ModelSource.valueOf(source.uppercase()) }.getOrDefault(
            if (isBuiltIn) ModelSource.CATALOG else ModelSource.MANUAL
        ),
        createdAt = createdAt,
    )

private object ProviderJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val headersSerializer = ListSerializer(CustomHeader.serializer())
    private val bodySerializer = ListSerializer(CustomBody.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    fun encodeHeaders(headers: List<CustomHeader>): String =
        json.encodeToString(headersSerializer, headers)

    fun decodeHeaders(raw: String): List<CustomHeader> =
        runCatching { json.decodeFromString(headersSerializer, raw) }.getOrDefault(emptyList())

    fun encodeBody(body: List<CustomBody>): String =
        json.encodeToString(bodySerializer, body)

    fun decodeBody(raw: String): List<CustomBody> =
        runCatching { json.decodeFromString(bodySerializer, raw) }.getOrDefault(emptyList())

    fun encodeStrings(values: List<String>): String =
        json.encodeToString(stringsSerializer, values)

    fun decodeStrings(raw: String): List<String> =
        runCatching { json.decodeFromString(stringsSerializer, raw) }.getOrDefault(emptyList())

    fun encodeReasoningCapabilities(capabilities: ModelReasoningCapabilities?): String =
        capabilities?.let { json.encodeToString(ModelReasoningCapabilities.serializer(), it) } ?: "null"

    fun decodeReasoningCapabilities(raw: String): ModelReasoningCapabilities? =
        raw.takeUnless { it.isBlank() || it == "null" }
            ?.let { encoded ->
                runCatching {
                    json.decodeFromString(ModelReasoningCapabilities.serializer(), encoded)
                }.getOrNull()
            }
}
