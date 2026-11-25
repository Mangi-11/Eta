package io.github.mangi.eta.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpRunContextTest {
    @Test
    fun enabledToolIsActiveRegardlessOfSchemaKeywords() {
        val definition = McpToolDefinition(
            name = "create_task",
            inputSchemaJson = """{"type":"object","anyOf":[]}""",
        )
        val server = McpServerSetting(
            id = "server",
            name = "Server",
            url = "https://example.com/mcp",
            tools = listOf(definition),
            enabledToolNames = setOf(definition.name),
        )

        assertEquals(listOf(definition), server.activeTools)
    }

    @Test
    fun snapshotUsesFrozenTokenAndReportsTruncatedOrUnsupportedContent() {
        val authorization = AtomicReference<String>()
        val server = localServer { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respond(
                """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[{"type":"resource_link","name":"Docs","uri":"https://example.com/docs"},{"type":"audio","mimeType":"audio/wav","data":"AA=="},{"type":"text","text":"${"x".repeat(70_000)}"}]}}""",
            )
        }
        try {
            val definition = McpToolDefinition(
                name = "search",
                inputSchemaJson = """{"type":"object"}""",
            )
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
                tools = listOf(definition),
                enabledToolNames = setOf(definition.name),
            )
            val executor = McpToolExecutor(
                McpRunSnapshot(
                    listOf(McpRunTool("mcp_server_search", setting, definition, "run-token"))
                )
            )

            val result = executor.execute(
                AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
            )
            executor.close()
            val payload = JSONObject(result.content)

            assertEquals("Bearer run-token", authorization.get())
            assertTrue(result.sensitive)
            assertTrue(payload.getBoolean("truncated"))
            assertEquals(1, payload.getInt("omitted_items"))
            assertTrue(payload.getJSONArray("content").getString(0).contains("https://example.com/docs"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closedExecutorDoesNotStartFirstRemoteCall() {
        val requests = AtomicInteger()
        val server = localServer { exchange ->
            requests.incrementAndGet()
            exchange.respond("""{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[]}}""")
        }
        try {
            val definition = McpToolDefinition("search", inputSchemaJson = """{"type":"object"}""")
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
            )
            val executor = McpToolExecutor(
                McpRunSnapshot(
                    listOf(McpRunTool("mcp_server_search", setting, definition, null))
                )
            )
            executor.close()

            val result = executor.execute(
                AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
            )

            assertEquals("MCP_EXECUTOR_CLOSED", JSONObject(result.content).getString("code"))
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", handler)
            start()
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respond(body: String) {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
