package io.github.mangi.eta.agent.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProviderUrls {
    private const val OFFICIAL_VERTEX_HOST = "aiplatform.googleapis.com"
    private val VERTEX_PROJECT_ERROR_REGEX = Regex("projects/([0-9A-Za-z_-]+)/locations/([0-9A-Za-z_-]+)")

    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

    fun anthropicModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/models")

    fun geminiInteractionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "interactions")

    fun geminiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun vertexStreamGenerateContentUrl(
        baseUrl: String,
        model: String,
        project: String = "",
        location: String = "global",
    ): String = "${vertexModelBaseUrl(baseUrl, model, project, location)}:streamGenerateContent?alt=sse"

    fun vertexCountTokensUrl(
        baseUrl: String,
        model: String,
        project: String = "",
        location: String = "global",
    ): String = "${vertexModelBaseUrl(baseUrl, model, project, location)}:countTokens"

    /** 未填 Project 的官方 Express 端点命中区域 404 时，改用提取出的项目号直连 global 端点。 */
    fun vertexGlobalProjectFallbackUrl(
        baseUrl: String,
        model: String,
        project: String,
        suffix: String = ":streamGenerateContent?alt=sse",
    ): String = "${vertexModelBaseUrl(baseUrl, model, project = project, location = "global")}$suffix"

    fun isOfficialVertexBaseUrl(baseUrl: String): Boolean {
        val host = normalizeBaseUrl(baseUrl).toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == OFFICIAL_VERTEX_HOST || host.endsWith("-$OFFICIAL_VERTEX_HOST")
    }

    /** 从 Vertex 区域 404 报错中提取绑定的项目号；若报错本身已在 global 节点则不触发无意义回退。 */
    fun extractVertexGlobalFallbackProject(errorBody: String): String? {
        val match = VERTEX_PROJECT_ERROR_REGEX.find(errorBody) ?: return null
        val project = match.groupValues[1].trim()
        val location = match.groupValues[2].trim().lowercase()
        return project.takeIf { it.isNotEmpty() && location != "global" }
    }

    private fun vertexModelBaseUrl(
        baseUrl: String,
        model: String,
        project: String,
        location: String,
    ): String {
        val normalizedBase = normalizeBaseUrl(baseUrl)
        val normalizedModel = model.trim()
            .removePrefix("models/")
            .removePrefix("publishers/google/models/")
            .trim('/')
        val trimmedProject = project.trim()
        val normalizedLocation = location.trim().ifBlank { "global" }
        val resolvedBase = resolveVertexHost(normalizedBase, trimmedProject, normalizedLocation)
        val hasProjectPath = resolvedBase.contains("/projects/", ignoreCase = true)
        val path = if (trimmedProject.isNotEmpty() && !hasProjectPath) {
            "projects/$trimmedProject/locations/$normalizedLocation/publishers/google/models/$normalizedModel"
        } else {
            "publishers/google/models/$normalizedModel"
        }
        return appendPath(resolvedBase, path)
    }

    private fun resolveVertexHost(baseUrl: String, project: String, location: String): String {
        if (project.isEmpty()) return baseUrl
        val parsed = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        val host = parsed.host.lowercase()
        val isOfficial = host == OFFICIAL_VERTEX_HOST || host.endsWith("-$OFFICIAL_VERTEX_HOST")
        if (!isOfficial) return baseUrl
        val targetHost = if (location.equals("global", ignoreCase = true)) {
            OFFICIAL_VERTEX_HOST
        } else {
            "${location.lowercase()}-$OFFICIAL_VERTEX_HOST"
        }
        return if (host == targetHost) baseUrl else parsed.newBuilder().host(targetHost).build().toString().trimEnd('/')
    }

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
