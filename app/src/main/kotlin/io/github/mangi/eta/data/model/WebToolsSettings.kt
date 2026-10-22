package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal enum class WebToolBackend {
    BUILTIN,
    DEEPSEEK,
    CUSTOM,
}

@Serializable
internal enum class WebHttpMethod {
    POST,
    GET,
}

@Serializable
internal data class DeepSeekWebConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}

@Serializable
internal data class CustomWebSearchConfig(
    val method: WebHttpMethod = WebHttpMethod.POST,
    val url: String = "",
    val headersText: String = "",
    val bodyTemplate: String = DEFAULT_BODY_TEMPLATE,
    val resultsPath: String = DEFAULT_RESULTS_PATH,
    val titleField: String = DEFAULT_TITLE_FIELD,
    val urlField: String = DEFAULT_URL_FIELD,
    val snippetField: String = DEFAULT_SNIPPET_FIELD,
) {
    companion object {
        const val DEFAULT_BODY_TEMPLATE = """{"query": {{query_json}}, "count": {{count}}}"""
        const val DEFAULT_RESULTS_PATH = "results"
        const val DEFAULT_TITLE_FIELD = "title"
        const val DEFAULT_URL_FIELD = "url"
        const val DEFAULT_SNIPPET_FIELD = "snippet"
    }
}

@Serializable
internal data class CustomWebFetchConfig(
    val method: WebHttpMethod = WebHttpMethod.POST,
    val url: String = "",
    val headersText: String = "",
    val bodyTemplate: String = DEFAULT_BODY_TEMPLATE,
    val contentField: String = DEFAULT_CONTENT_FIELD,
    val titleField: String = DEFAULT_TITLE_FIELD,
) {
    companion object {
        const val DEFAULT_BODY_TEMPLATE = """{"url": {{url_json}}}"""
        const val DEFAULT_CONTENT_FIELD = "content"
        const val DEFAULT_TITLE_FIELD = "title"
    }
}

@Serializable
internal data class WebToolsSettings(
    val searchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val fetchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val deepSeek: DeepSeekWebConfig = DeepSeekWebConfig(),
    val customSearch: CustomWebSearchConfig = CustomWebSearchConfig(),
    val customFetch: CustomWebFetchConfig = CustomWebFetchConfig(),
)

@Serializable
internal data class WebToolsCredentials(
    val deepSeekApiKey: String = "",
    val customSearchApiKey: String = "",
    val customFetchApiKey: String = "",
)
