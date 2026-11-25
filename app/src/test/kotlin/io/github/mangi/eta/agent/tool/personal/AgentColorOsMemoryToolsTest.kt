package io.github.mangi.eta.agent.tool.personal

import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentColorOsMemoryToolsTest {
    @Test
    fun snapshotCopiesAllSqliteSidecars() {
        val command = buildColorOsMemorySnapshotCommand(
            source = "/data/user/0/com.oplus.aimemory/databases/ai_memory",
            snapshot = File("/data/user/0/io.github.mangi.eta/cache/eta-coloros-memory-test.db"),
        )

        assertTrue(command.contains("ai_memory-wal"))
        assertTrue(command.contains("ai_memory-shm"))
        assertTrue(command.contains("ai_memory-journal"))
        assertTrue(command.contains("eta-coloros-memory-test.db-shm"))
        assertTrue(command.contains("rm -f '/data/user/0/io.github.mangi.eta/cache/eta-coloros-memory-test.db-journal'"))
    }

    @Test
    fun hookBridgeRoundTripsBoundedPayload() {
        val encodedRequest = ColorOsMemoryBridgeProtocol.encodeRequest(
            ColorOsMemoryBridgeProtocol.OPERATION_SEARCH,
            JSONObject().put("query", "快递").put("limit", 10),
        )
        val request = requireNotNull(ColorOsMemoryBridgeProtocol.decodeRequest(encodedRequest))
        assertEquals(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, request.operation)
        assertEquals("快递", request.args.getString("query"))

        val content = JSONObject().put("ok", true).put("count", 2).toString()
        val envelope = ColorOsMemoryBridgeProtocol.encodeResponse(content)
        val stdout = "Result: Bundle[{${ColorOsMemoryBridgeProtocol.RESULT_KEY}=$envelope}]"
        assertEquals(content, ColorOsMemoryBridgeProtocol.decodeShellResponse(stdout))
    }

    @Test
    fun hookCommandContainsOnlyFixedProviderAndEncodedArgument() {
        val encodedRequest = ColorOsMemoryBridgeProtocol.encodeRequest(
            ColorOsMemoryBridgeProtocol.OPERATION_PLACES,
            JSONObject().put("query", "公司"),
        )
        val command = ColorOsMemoryBridgeProtocol.buildRootCommand(encodedRequest)

        assertTrue(command.contains(ColorOsMemoryBridgeProtocol.PROVIDER_URI))
        assertTrue(command.contains(ColorOsMemoryBridgeProtocol.METHOD))
        assertTrue(command.contains(encodedRequest))
        assertFalse(command.contains("公司"))
    }

    @Test
    fun sqliteProjectionQuotesReservedShipmentColumn() {
        assertEquals("\"order\"", quoteColorOsMemoryIdentifier("order"))
    }
}
