package io.github.mangi.eta.hook.xiaoai

import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.atomic.AtomicReference

internal object XiaoAiTakeoverPolicy {
    private const val AGENT_PREFIX = "/agent "
    private const val AGENT_ENCODED_PREFIX = "/agent%20"
    private const val IMAGE_ONLY_PLACEHOLDER = "blank"
    private const val IMAGE_ONLY_PROMPT = "请分析这张图片"

    data class Decision(
        val prompt: String,
    )

    fun isSupportedVersion(versionCode: Long): Boolean =
        versionCode == XiaoAiHooks.SUPPORTED_VERSION_CODE ||
            versionCode == 507013034L // Super XiaoAI 7.13.34.3812; static signatures verified

    fun matchesOutboundEvent(fullName: String): Boolean =
        fullName == "Nlp.Request"

    fun decide(
        query: String,
        hasImage: Boolean,
        customModelEnabled: Boolean,
        requirePrefix: Boolean,
    ): Decision? {
        if (!customModelEnabled) return null
        val trimmed = query.trim()
        val prefixed = when {
            trimmed.startsWith(AGENT_PREFIX) ->
                trimmed.removePrefix(AGENT_PREFIX).trim()

            trimmed.startsWith(AGENT_ENCODED_PREFIX) ->
                trimmed.removePrefix(AGENT_ENCODED_PREFIX).trim()

            else -> null
        }
        if (requirePrefix && prefixed == null) return null

        val prompt = (prefixed ?: trimmed).let { candidate ->
            if (
                hasImage &&
                (candidate.isBlank() || candidate.equals(IMAGE_ONLY_PLACEHOLDER, ignoreCase = true))
            ) {
                IMAGE_ONLY_PROMPT
            } else {
                candidate
            }
        }
        return prompt.takeIf(String::isNotBlank)?.let(::Decision)
    }
}

/**
 * Ephemeral multi-turn context for XiaoAI's ASR-only voice path.
 *
 * A Xiaomi dialog ID changes between utterances. Keep an independent model
 * session ID and the exact provider transcript / context-compaction snapshot.
 * The bridge never persists this cache; it expires after inactivity or process
 * death. Do not clear it merely because MiSpeechEngine.V() was invoked: Xiaomi
 * can invoke that between consecutive voice turns.
 */
internal class XiaoAiVoiceConversation(
    private val idleTimeoutMillis: Long = 180_000L,
    private val maxTurns: Int = 12,
    private val maxHistoryChars: Int = 128_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Snapshot(
        val sessionId: String,
        val history: List<AgentModelClient.ConversationMessage>,
        val priorTurns: Int,
        val token: Long,
    )

    private var sessionId: String? = null
    private var history: List<AgentModelClient.ConversationMessage> = emptyList()
    private var completedTurns = 0
    private var touchedAt = 0L
    private var nextToken = 0L
    private var activeToken = 0L

    init {
        require(idleTimeoutMillis > 0L && maxTurns > 0 && maxHistoryChars > 0)
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = clock()
        if (sessionId == null || now < touchedAt ||
            now - touchedAt > idleTimeoutMillis || completedTurns >= maxTurns
        ) {
            reset(newSessionId())
        }
        touchedAt = now
        activeToken = ++nextToken
        return Snapshot(
            sessionId = requireNotNull(sessionId),
            history = history.toList(),
            priorTurns = completedTurns,
            token = activeToken,
        )
    }

    /**
     * Commit only the most recently started, successfully completed run.
     * Context snapshots already include a canonical, optionally compacted
     * history. Otherwise follow AgentContinuationBuilder's history contract:
     * previous history + current user input + returned incremental transcript.
     */
    @Synchronized
    fun recordSuccess(
        snapshot: Snapshot,
        prompt: String,
        reply: String,
        transcript: List<AgentModelClient.ConversationMessage>,
        contextMessages: List<AgentModelClient.ConversationMessage>? = null,
    ): Boolean {
        if (snapshot.sessionId != sessionId || snapshot.token != activeToken ||
            prompt.isBlank() || reply.isBlank()
        ) return false

        val nextHistory = contextMessages?.takeIf { it.isNotEmpty() }
            ?: (
                snapshot.history +
                    AgentModelClient.ConversationMessage(role = "user", content = prompt) +
                    transcript.ifEmpty {
                        listOf(AgentModelClient.ConversationMessage(role = "assistant", content = reply))
                    }
                )
        if (nextHistory.sumOf(::messageSize) > maxHistoryChars) {
            // Never cut through a tool-call/result pair or a compacted summary.
            // Start a fresh bounded session rather than submitting broken history.
            reset(newSessionId())
            return false
        }
        history = nextHistory.toList()
        completedTurns++
        touchedAt = clock()
        activeToken = 0L
        return true
    }

    @Synchronized
    fun abandon(snapshot: Snapshot) {
        if (snapshot.sessionId == sessionId && snapshot.token == activeToken) {
            activeToken = 0L
        }
    }

    @Synchronized
    fun clear() {
        sessionId = null
        history = emptyList()
        completedTurns = 0
        touchedAt = 0L
        activeToken = 0L
    }

    private fun reset(nextId: String) {
        sessionId = nextId
        history = emptyList()
        completedTurns = 0
        touchedAt = 0L
        activeToken = 0L
    }

    private fun messageSize(message: AgentModelClient.ConversationMessage): Int =
        message.content.length + message.contentJson.length +
            message.toolCallId.length + message.toolCallsJson.length +
            message.reasoningContent.length
}

internal class XiaoAiRunSlot<T : Any> {
    private val current = AtomicReference<T?>()

    fun get(): T? = current.get()

    fun replace(value: T): T? = current.getAndSet(value)

    fun clear(value: T): Boolean = current.compareAndSet(value, null)
}

internal class XiaoAiRendererSlot<T : Any> {
    private val attached = AtomicReference<T?>()

    fun attach(value: T): T? = attached.getAndSet(value)

    fun detach(): T? = attached.getAndSet(null)
}

internal class XiaoAiQueryCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class QueryInfo(
        val dialogId: String,
        val query: String,
        val imageFileId: String?,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private val entries = LinkedHashMap<String, QueryInfo>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun put(
        dialogId: String,
        query: String,
        imageFileId: String?,
        documentInput: Boolean = false,
    ): Boolean {
        val normalizedId = dialogId.trim()
        if (normalizedId.isBlank()) return false
        val now = clock()
        pruneExpired(now)
        entries.remove(normalizedId)
        entries[normalizedId] = QueryInfo(
            dialogId = normalizedId,
            query = query,
            imageFileId = imageFileId?.trim()?.takeIf(String::isNotBlank),
            documentInput = documentInput,
            capturedAt = now,
        )
        trimToCapacity()
        return true
    }

    @Synchronized
    fun take(dialogId: String): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        return entries.remove(dialogId.trim())
    }

    /**
     * Nlp.Request 的 Event ID 由小爱在出站时重新生成，不能假设它等于 setQueryInfo 的
     * dialogId。优先按 ID 取值，再按同一轮查询文本关联；关联成功后同样消费一次。
     */
    @Synchronized
    fun takeMatching(
        eventId: String,
        eventQuery: String,
    ): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        entries.remove(eventId.trim())?.let { return it }

        val normalizedQuery = eventQuery.trim()
        if (normalizedQuery.isBlank()) return null
        val matchingKey = entries.entries
            .lastOrNull { (_, value) -> value.query.trim() == normalizedQuery }
            ?.key
            ?: return null
        return entries.remove(matchingKey)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        pruneExpired(clock())
        return entries.size
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, value) -> now - value.capturedAt > ttlMillis }
    }

    private fun trimToCapacity() {
        while (entries.size > capacity) {
            val oldest = entries.entries.firstOrNull()?.key ?: return
            entries.remove(oldest)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}

internal class XiaoAiTurnTracker(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Turn(
        val dialogId: String,
        val query: String,
        val hasImage: Boolean,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private var current: Turn? = null

    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun capture(
        dialogId: String,
        query: String,
        hasImage: Boolean = false,
        documentInput: Boolean = false,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        current = Turn(
            dialogId = dialogId.trim(),
            query = normalizedQuery,
            hasImage = hasImage,
            documentInput = documentInput,
            capturedAt = clock(),
        )
    }

    @Synchronized
    fun latest(): Turn? {
        val turn = current ?: return null
        if (clock() - turn.capturedAt > ttlMillis) {
            current = null
            return null
        }
        return turn
    }

    @Synchronized
    fun clear() {
        current = null
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 15_000L
    }
}

internal class XiaoAiRecentIds(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, Long>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun add(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val now = clock()
        pruneExpired(now)
        entries.remove(normalized)
        entries[normalized] = now
        while (entries.size > capacity) {
            entries.remove(entries.entries.firstOrNull()?.key ?: break)
        }
    }

    @Synchronized
    fun contains(id: String): Boolean {
        val now = clock()
        pruneExpired(now)
        return entries.containsKey(id.trim())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, createdAt) -> now - createdAt > ttlMillis }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}
