package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal object McpServerManager {
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val discovery = client.discoverTools()
        discovery.truncation?.let { reason ->
            AndroidAgentLogger.warn(
                "MCP tool discovery truncated: serverId=${server.id}, " +
                    "imported=${discovery.tools.size}, reason=${reason.wireValue}",
            )
        }
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
            toolsTruncationReason = discovery.truncation,
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }
}
