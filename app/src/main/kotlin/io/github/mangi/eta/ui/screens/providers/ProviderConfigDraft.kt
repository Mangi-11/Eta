package io.github.mangi.eta.ui.screens.providers

import androidx.compose.runtime.saveable.mapSaver
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.provider.CustomHeaderFilter
import io.github.mangi.eta.agent.model.provider.ProviderUrls
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.util.UUID

internal data class ProviderHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val header: CustomHeader = CustomHeader("", ""),
)

internal data class ProviderConfigDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String,
    val isEnabled: Boolean,
    val endpointMode: String,
    val hostedWebSearchEnabled: Boolean,
    val anthropicVersion: String,
    val headers: List<ProviderHeaderDraft> = emptyList(),
    val authMode: String = ProviderAuthModes.API_KEY,
    val vertexProject: String = "",
    val vertexLocation: String = GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
) {
    companion object {
        fun from(provider: ProviderSetting): ProviderConfigDraft {
            val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
            return ProviderConfigDraft(
                headers = provider.customHeaders.map { ProviderHeaderDraft(header = it) },
                name = provider.name,
                baseUrl = profile?.baseUrl ?: provider.baseUrl,
                apiKey = provider.apiKey,
                authMode = provider.authMode,
                systemPrompt = provider.systemPrompt.orEmpty(),
                isEnabled = provider.isEnabled,
                endpointMode = profile?.endpointMode ?: when (provider) {
                    is OpenAiCompatibleProviderSetting -> provider.endpointMode
                    is CustomProviderSetting -> provider.endpointMode
                    is GeminiProviderSetting -> GeminiEndpointMode.normalize(provider.endpointMode)
                    is AnthropicProviderSetting -> ""
                },
                hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
                anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                    ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
                vertexProject = (provider as? GeminiProviderSetting)?.vertexProject.orEmpty(),
                vertexLocation = (provider as? GeminiProviderSetting)?.vertexLocation
                    ?.ifBlank { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION }
                    ?: GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
            )
        }
    }
}

internal val ProviderConfigDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "headers" to ArrayList(draft.headers.flatMap { listOf(it.id, it.header.name, it.header.value) }),
            "name" to draft.name,
            "baseUrl" to draft.baseUrl,
            "apiKey" to draft.apiKey,
            "authMode" to draft.authMode,
            "systemPrompt" to draft.systemPrompt,
            "isEnabled" to draft.isEnabled,
            "endpointMode" to draft.endpointMode,
            "hostedWebSearchEnabled" to draft.hostedWebSearchEnabled,
            "anthropicVersion" to draft.anthropicVersion,
            "vertexProject" to draft.vertexProject,
            "vertexLocation" to draft.vertexLocation,
        )
    },
    restore = { state ->
        ProviderConfigDraft(
            headers = (state["headers"] as? List<*>)?.chunked(3)?.map {
                ProviderHeaderDraft(it[0] as String, CustomHeader(it[1] as String, it[2] as String))
            }.orEmpty(),
            name = state.getValue("name") as String,
            baseUrl = state.getValue("baseUrl") as String,
            apiKey = state.getValue("apiKey") as String,
            authMode = state["authMode"] as? String ?: ProviderAuthModes.API_KEY,
            systemPrompt = state.getValue("systemPrompt") as String,
            isEnabled = state.getValue("isEnabled") as Boolean,
            endpointMode = state.getValue("endpointMode") as String,
            hostedWebSearchEnabled = state.getValue("hostedWebSearchEnabled") as Boolean,
            anthropicVersion = state.getValue("anthropicVersion") as String,
            vertexProject = state["vertexProject"] as? String ?: "",
            vertexLocation = (state["vertexLocation"] as? String)
                ?.takeIf(String::isNotBlank)
                ?: GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
        )
    },
)

internal fun buildUpdatedProvider(
    source: ProviderSetting,
    name: String,
    baseUrl: String,
    apiKey: String,
    systemPrompt: String,
    isEnabled: Boolean,
    endpointMode: String,
    hostedWebSearchEnabled: Boolean,
    anthropicVersion: String,
    customHeaders: List<CustomHeader>,
    authMode: String = source.authMode,
    vertexProject: String = "",
    vertexLocation: String = GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
): ProviderSetting {
    val prompt = systemPrompt.trim().takeIf { it.isNotBlank() }
    val profile = ProviderConnectionProfile.forAuthMode(authMode)
    return when (source) {
        is OpenAiCompatibleProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = profile?.baseUrl ?: baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = authMode,
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = profile?.endpointMode ?: endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
        )
        is CustomProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = profile?.baseUrl ?: baseUrl.trim(),
            apiKey = apiKey.trim(),
            authMode = authMode,
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = profile?.endpointMode ?: endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
        )
        is GeminiProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
            endpointMode = GeminiEndpointMode.normalize(endpointMode),
            vertexProject = vertexProject.trim(),
            vertexLocation = vertexLocation.trim().ifBlank { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION },
        )
        is AnthropicProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            anthropicVersion = anthropicVersion.trim().ifBlank { AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
        )
    }
}

internal fun ProviderConfigDraft.toProvider(source: ProviderSetting): ProviderSetting = buildUpdatedProvider(
    source = source,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    systemPrompt = systemPrompt,
    isEnabled = isEnabled,
    endpointMode = endpointMode,
    hostedWebSearchEnabled = hostedWebSearchEnabled,
    anthropicVersion = anthropicVersion,
    customHeaders = headers.map { it.header },
    authMode = authMode,
    vertexProject = vertexProject,
    vertexLocation = vertexLocation,
)

internal fun ProviderConfigDraft.withGeminiEndpointMode(mode: String): ProviderConfigDraft {
    val normalized = GeminiEndpointMode.normalize(mode)
    val trimmedUrl = baseUrl.trim().trimEnd('/')
    val knownDefaults = setOf(
        "",
        GeminiProviderSetting.DEFAULT_BASE_URL,
        GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
    )
    val nextBaseUrl = if (trimmedUrl in knownDefaults) {
        if (normalized == GeminiEndpointMode.VERTEX_AI) {
            GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL
        } else {
            GeminiProviderSetting.DEFAULT_BASE_URL
        }
    } else {
        baseUrl
    }
    return copy(
        endpointMode = normalized,
        baseUrl = nextBaseUrl,
        vertexLocation = vertexLocation.ifBlank { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION },
    )
}

internal fun providerAccountAuthMode(provider: ProviderSetting): String? {
    if (provider is AnthropicProviderSetting || provider is GeminiProviderSetting) return null
    if (ProviderAuthModes.isOAuth(provider.authMode)) return provider.authMode
    return when (ProviderSourceRegistry.resolve(provider)) {
        ProviderSourceTypes.OPENAI -> ProviderAuthModes.OPENAI_CHATGPT
        ProviderSourceTypes.XAI -> ProviderAuthModes.XAI_GROK
        else -> null
    }
}

internal fun ProviderConfigDraft.withAuthMode(mode: String, provider: ProviderSetting): ProviderConfigDraft {
    val profile = ProviderConnectionProfile.forAuthMode(mode)
    if (profile != null) return copy(
        authMode = mode,
        baseUrl = profile.baseUrl,
        endpointMode = profile.endpointMode,
        hostedWebSearchEnabled = hostedWebSearchEnabled && profile.endpointMode == OpenAiEndpointMode.RESPONSES,
    )
    val original = ProviderConfigDraft.from(provider)
    if (provider.authMode == ProviderAuthModes.API_KEY) return copy(
        authMode = mode,
        baseUrl = original.baseUrl,
        endpointMode = original.endpointMode,
    )
    val apiProvider = checkNotNull(BuiltinProviders.providerById(
        if (authMode == ProviderAuthModes.XAI_GROK) BuiltinProviders.XAI_ID else BuiltinProviders.OPENAI_ID,
    ))
    return copy(
        authMode = mode,
        baseUrl = apiProvider.baseUrl,
        endpointMode = ProviderConfigDraft.from(apiProvider).endpointMode,
    )
}

internal fun validateProviderDraft(context: android.content.Context, draft: ProviderConfigDraft): String? {
    if (draft.name.isBlank()) return context.getString(R.string.page_name_cannot_be_empty_ca8984)
    val uri = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return context.getString(R.string.page_base_url_must_be_a_valid_http_s_address_0e7d58)
    }
    if (draft.endpointMode == GeminiEndpointMode.VERTEX_AI) {
        try {
            ProviderUrls.validateVertexChatEndpoint(draft.baseUrl, draft.vertexProject, draft.vertexLocation)
        } catch (failure: IllegalArgumentException) {
            return failure.message ?: context.getString(R.string.provider_i18n_vertex_config_invalid)
        }
    }
    return CustomHeaderFilter.validationError(draft.headers.map { it.header })
}
