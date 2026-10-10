package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** Independent reads may overlap; all other calls form an execution barrier. */
internal object AgentToolBatchPlanner {
    const val MAX_CONCURRENT_CALLS = 4

    data class Segment(
        val parallel: Boolean,
        val calls: List<AgentModelClient.ToolCall>,
    )

    private val parallelSafeTools = setOf(
        "search_apps", "web_search", "tasks_triggers", "tasks_list", "tasks_history",
    )
    private val fileReadTools = setOf(
        "stat_file", "list_directory", "glob_files", "grep_files", "read_file",
    )

    // fetch_url retains a small, ordered document cache. Its calls remain sequential.
    val batchTools: Set<String> = parallelSafeTools + fileReadTools + "fetch_url"

    fun isParallelSafe(call: AgentModelClient.ToolCall): Boolean {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return false
        if (call.name in parallelSafeTools) return true
        if (call.name !in fileReadTools) return false
        // Linux/root file access uses a shared shell environment and privilege state.
        return args.optString("environment", "android").equals("android", ignoreCase = true) &&
            args.optString("identity", "user").equals("user", ignoreCase = true)
    }

    fun plan(calls: List<AgentModelClient.ToolCall>): List<Segment> {
        val segments = mutableListOf<Segment>()
        val parallelRun = mutableListOf<AgentModelClient.ToolCall>()

        fun flushParallel() {
            if (parallelRun.isNotEmpty()) {
                segments += Segment(parallel = parallelRun.size > 1, calls = parallelRun.toList())
                parallelRun.clear()
            }
        }

        calls.forEach { call ->
            if (isParallelSafe(call)) {
                parallelRun += call
            } else {
                flushParallel()
                segments += Segment(parallel = false, calls = listOf(call))
            }
        }
        flushParallel()
        return segments
    }
}
