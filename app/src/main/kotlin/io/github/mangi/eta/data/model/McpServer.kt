package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object McpProtocolMode {
    const val AUTO = "auto"
    const val LATEST = "2026-07-28"
    const val LEGACY = "2025-11-25"
}

internal object McpAuthorizationType {
    const val NONE = "none"
    const val BEARER = "bearer"
}

internal enum class McpDiscoveryTruncation(val wireValue: String) {
    TOOL_LIMIT("tool_limit"),
    PAGE_LIMIT("page_limit"),
    SIZE_LIMIT("size_limit"),
    CURSOR_LOOP("cursor_loop"),
    ;

    companion object {
        fun fromWireValue(value: String?): McpDiscoveryTruncation? =
            entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
internal data class McpToolDefinition(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
internal data class McpServerSetting(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val tools: List<McpToolDefinition> = emptyList(),
    val enabledToolNames: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastProtocolVersion: String? = null,
    val toolsExpireAt: Long? = null,
    val toolsTruncationReason: McpDiscoveryTruncation? = null,
) {
    val activeTools: List<McpToolDefinition>
        get() = if (!enabled) {
            emptyList()
        } else {
            tools.filter { it.name in enabledToolNames }
        }
}
