package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolBatchPlannerTest {
    @Test
    fun readsNeverCrossMutationOrObservationBarriers() {
        val calls = listOf(
            call("1", "read_file"), call("2", "web_search"),
            call("3", "write_file"), call("4", "read_file"), call("5", "search_apps"),
            call("6", "observe_screen"), call("7", "tap"), call("8", "mcp_read"),
        )
        val plan = AgentToolBatchPlanner.plan(calls)
        assertEquals(listOf(true, false, true, false, false, false), plan.map { it.parallel })
        assertEquals(listOf(listOf("1", "2"), listOf("3"), listOf("4", "5"), listOf("6"), listOf("7"), listOf("8")),
            plan.map { segment -> segment.calls.map { it.id } })
        assertEquals(calls, plan.flatMap { it.calls })
    }

    @Test
    fun environmentInitializationCacheAndInvalidArgumentsRemainSequential() {
        for (name in listOf("read_file", "stat_file", "glob_files", "grep_files", "list_directory")) {
            assertTrue(AgentToolBatchPlanner.isParallelSafe(call("x", name)))
            assertFalse(AgentToolBatchPlanner.isParallelSafe(call("x", name, "{\"identity\":\"root\"}")))
            assertFalse(AgentToolBatchPlanner.isParallelSafe(call("x", name, "{\"environment\":\"linux\"}")))
        }
        for (name in listOf("fetch_url", "browser_use", "terminal", "memory_get", "batch")) {
            assertFalse(AgentToolBatchPlanner.isParallelSafe(call("x", name)))
        }
        assertFalse(AgentToolBatchPlanner.isParallelSafe(call("x", "web_search", "{")))
        assertTrue(AgentToolBatchPlanner.plan(emptyList()).isEmpty())
        assertFalse(AgentToolBatchPlanner.plan(listOf(call("1", "search_apps"))).single().parallel)
    }

    private fun call(id: String, name: String, arguments: String = "{}") =
        AgentModelClient.ToolCall(id, name, arguments)
}
