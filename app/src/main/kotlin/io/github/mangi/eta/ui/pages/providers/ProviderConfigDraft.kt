package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.saveable.mapSaver
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.CustomHeaderFilter
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
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
) {
    companion object {
        fun from(provider: ProviderSetting): ProviderConfigDraft = ProviderConfigDraft(
            headers = provider.customHeaders.map { ProviderHeaderDraft(header = it) },
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            systemPrompt = provider.systemPrompt.orEmpty(),
            isEnabled = provider.isEnabled,
            endpointMode = when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            },
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        )
    }
}

internal val ProviderConfigDraftSaver = mapSaver(
    save = { draft ->
        mapOf(
            "headers" to ArrayList(draft.headers.flatMap { listOf(it.id, it.header.name, it.header.value) }),
            "name" to draft.name,
            "baseUrl" to draft.baseUrl,
            "apiKey" to draft.apiKey,
            "systemPrompt" to draft.systemPrompt,
            "isEnabled" to draft.isEnabled,
            "endpointMode" to draft.endpointMode,
            "hostedWebSearchEnabled" to draft.hostedWebSearchEnabled,
            "anthropicVersion" to draft.anthropicVersion,
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
            systemPrompt = state.getValue("systemPrompt") as String,
            isEnabled = state.getValue("isEnabled") as Boolean,
            endpointMode = state.getValue("endpointMode") as String,
            hostedWebSearchEnabled = state.getValue("hostedWebSearchEnabled") as Boolean,
            anthropicVersion = state.getValue("anthropicVersion") as String,
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
): ProviderSetting {
    val prompt = systemPrompt.trim().takeIf { it.isNotBlank() }
    return when (source) {
        is OpenAiCompatibleProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
        )
        is CustomProviderSetting -> source.copy(
            customHeaders = customHeaders.map { it.copy(name = it.name.trim()) },
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = endpointMode,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
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

internal fun validateProviderDraft(context: android.content.Context, draft: ProviderConfigDraft): String? {
    if (draft.name.isBlank()) return context.getString(R.string.page_name_cannot_be_empty_ca8984)
    val uri = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return context.getString(R.string.page_base_url_must_be_a_valid_http_s_address_0e7d58)
    }
    return CustomHeaderFilter.validationError(draft.headers.map { it.header })
}
