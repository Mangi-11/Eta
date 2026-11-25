package io.github.mangi.eta.agent.tool.device

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatQueryTest {
    @Test
    fun filtersWithinSampleAndReportsOmittedMatches() {
        val logText = listOf(
            "1791187200.000 42 43 I EtaTag: before boundary",
            "1791187201.000 42 43 W EtaTag: matching one",
            "1791187202.000 42 43 E EtaTag: matching two",
            "1791187203.000 99 99 E EtaTag: other pid",
            "1791187204.000 42 43 E Other: other tag",
        ).joinToString("\n")
        var command = ""
        val result = LogcatQuery { command = it; output(logText) }.execute(JSONObject()
            .put("pid", 42).put("tag", "EtaTag").put("level", "W")
            .put("since", "2026-10-05T08:00:01Z").put("max_lines", 1).put("scan_lines", 5))
        assertTrue(result.getBoolean("ok"))
        assertEquals(2, result.getInt("matched_lines"))
        assertTrue(result.getJSONArray("lines").getString(0).contains("matching two"))
        assertTrue(result.getBoolean("has_more"))
        assertTrue(result.getBoolean("scan_limited"))
        assertFalse(result.getBoolean("complete_within_scan"))
        assertFalse(result.getBoolean("history_complete"))
        assertTrue(command.contains("--pid=42"))
        assertTrue(command.contains("'EtaTag:W' '*:S'"))
        assertEquals("device", result.getString("scope"))
    }

    @Test
    fun noMatchesNeverClaimsCompleteDeviceHistory() {
        val result = LogcatQuery { output("1791187200.000 42 43 I EtaTag: unrelated") }
            .execute(JSONObject().put("query", "absent").put("scan_lines", 1))
        assertEquals(0, result.getInt("count"))
        assertTrue(result.getBoolean("scan_limited"))
        assertFalse(result.getBoolean("has_more"))
        assertFalse(result.getBoolean("history_complete"))
    }

    @Test
    fun malformedOutputAndClippedTextAreVisibleInCompleteness() {
        val result = LogcatQuery { output("unparsed record\n1791187200.000 42 43 I EtaTag: ${"x".repeat(6000)}") }
            .execute(JSONObject())
        assertEquals(1, result.getInt("unparsed_lines"))
        assertEquals(1, result.getInt("clipped_lines"))
        assertTrue(result.getBoolean("truncated"))
        assertFalse(result.getBoolean("complete_within_scan"))
        assertTrue(result.getJSONArray("lines").getString(0).length <= 4_000)
    }

    @Test
    fun invalidFiltersDoNotReachShell() {
        val query = LogcatQuery { error("不应执行命令") }
        listOf(
            JSONObject().put("tag", "x;touch /tmp/test"),
            JSONObject().put("since", "10-05 08:00:00"),
            JSONObject().put("pid", -1),
            JSONObject().put("scan_lines", 10_001),
        ).forEach { assertEquals("INVALID_ARGUMENT", query.execute(it).getString("code")) }
        assertEquals("USER_SCOPE_UNSUPPORTED", query.execute(JSONObject().put("user_id", 0)).getString("code"))
    }

    @Test
    fun rootFailureDoesNotBecomeEmptySuccess() {
        val result = LogcatQuery { BoundedRootCommandExecutor.Result.failed("ROOT_REQUIRED") }.execute(JSONObject())
        assertFalse(result.getBoolean("ok"))
        assertEquals("ROOT_REQUIRED", result.getString("code"))
    }

    private fun output(text: String) = BoundedRootCommandExecutor.Result(0, text, "", false, false)
}
