package fuck.andes.agent.runtime

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-persistent archive for externally initiated runs that should later be
 * mirrored into the module's own chat history.
 *
 * Unlike [AgentRuntimeResultStore], entries here are not an entry-adapter retry
 * queue. They preserve the event trace so the first-party UI can reconstruct
 * thinking and tool activity that third-party assistant surfaces cannot show.
 */
internal object AgentRunArchiveStore {
    private const val PREFS_NAME = "agent_run_archive"
    private const val KEY_RUNS = "runs"
    private const val MAX_ARCHIVED = 32
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    data class ArchivedRun(
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val result: AgentRuntimeWire.RunResult,
        val createdAt: Long
    )

    fun add(context: Context, run: ArchivedRun) {
        val runId = run.result.runId.ifBlank { run.handoff.id }
        val runs = list(context)
            .filterNot { archived ->
                val archivedRunId = archived.result.runId.ifBlank { archived.handoff.id }
                archivedRunId == runId
            } + run.copy(events = compactEvents(run.events))
        write(context, prune(runs))
    }

    fun list(context: Context): List<ArchivedRun> {
        val raw = prefs(context).getString(KEY_RUNS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        var decodedLength = 0
        val runs = runCatching {
            val array = JSONArray(raw)
            decodedLength = array.length()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
        }.getOrDefault(emptyList())
            .filter { now - it.createdAt <= MAX_AGE_MS }
            .sortedBy { it.createdAt }
        if (runs.size != decodedLength) {
            write(context, prune(runs))
        }
        return runs
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        write(
            context,
            list(context).filterNot { archived ->
                archived.result.runId == runId || archived.handoff.id == runId
            }
        )
    }

    private fun prune(runs: List<ArchivedRun>): List<ArchivedRun> {
        val now = System.currentTimeMillis()
        return runs
            .filter { now - it.createdAt <= MAX_AGE_MS }
            .sortedBy { it.createdAt }
            .takeLast(MAX_ARCHIVED)
    }

    private fun write(context: Context, runs: List<ArchivedRun>) {
        val array = JSONArray()
        runs.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_RUNS, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun toJson(run: ArchivedRun): JSONObject =
        JSONObject()
            .put("createdAt", run.createdAt)
            .put(
                "handoff",
                JSONObject()
                    .put("id", run.handoff.id)
                    .put("source", run.handoff.source)
                    .put("payload", run.handoff.payload)
            )
            .put(
                "result",
                JSONObject()
                    .put("runId", run.result.runId)
                    .put("ok", run.result.ok)
                    .put("content", run.result.content)
                    .put("reasoningContent", run.result.reasoningContent)
                    .put("error", run.result.error)
            )
            .put("events", JSONArray().also { array ->
                run.events.forEach { event -> array.put(bundleToJson(AgentRuntimeWire.eventToBundle(event))) }
            })

    private fun fromJson(json: JSONObject): ArchivedRun? =
        runCatching {
            val handoff = json.getJSONObject("handoff")
            val result = json.getJSONObject("result")
            val events = json.optJSONArray("events") ?: JSONArray()
            ArchivedRun(
                createdAt = json.optLong("createdAt"),
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = handoff.optString("id"),
                    source = handoff.optString("source"),
                    payload = handoff.optString("payload")
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = result.optString("runId").ifBlank { json.optString("runId") },
                    ok = result.optBoolean("ok"),
                    content = result.optString("content"),
                    error = result.optString("error").ifBlank { null },
                    reasoningContent = result.optString("reasoningContent")
                        .ifBlank { result.optString("reasoning_content") }
                ),
                events = (0 until events.length()).mapNotNull { index ->
                    events.optJSONObject(index)
                        ?.let(::jsonToBundle)
                        ?.let(AgentRuntimeWire::eventFromBundle)
                }
            )
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): JSONObject =
        JSONObject().also { json ->
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is ArrayList<*> -> json.put(key, JSONArray(value))
                    null -> json.put(key, JSONObject.NULL)
                }
            }
        }

    private fun jsonToBundle(json: JSONObject): Bundle =
        Bundle().also { bundle ->
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is JSONArray -> bundle.putStringArrayList(
                        key,
                        ArrayList((0 until value.length()).map { index -> value.optString(index) })
                    )
                }
            }
        }

    private fun compactEvents(events: List<AgentEvent>): List<AgentEvent> {
        val compacted = mutableListOf<AgentEvent>()
        events.forEach { event ->
            val previous = compacted.lastOrNull()
            val merged = when {
                previous is AgentEvent.AssistantTextDelta &&
                    event is AgentEvent.AssistantTextDelta &&
                    previous.round == event.round -> {
                    previous.copy(
                        delta = previous.delta + event.delta,
                        deltaChars = previous.deltaChars + event.deltaChars,
                    )
                }

                previous is AgentEvent.AssistantReasoningDelta &&
                    event is AgentEvent.AssistantReasoningDelta &&
                    previous.round == event.round -> {
                    previous.copy(
                        delta = previous.delta + event.delta,
                        deltaChars = previous.deltaChars + event.deltaChars,
                    )
                }

                else -> null
            }
            if (merged != null) {
                compacted[compacted.lastIndex] = merged
            } else {
                compacted += event
            }
        }
        return compacted
    }
}
