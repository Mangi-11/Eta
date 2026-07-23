package fuck.andes.hook.breeno

import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

internal object BreenoRequestImages {
    private const val MAX_IMAGES = 4
    private const val MAX_INPUTS = 16
    private const val MAX_CANDIDATES = 32
    private const val MAX_JSON_DEPTH = 4
    private const val MAX_JSON_NODES = 128
    private const val MAX_NESTED_IMAGE_ITEMS = 8
    private const val MAX_INPUT_CHARS = 9 * 1024 * 1024
    private const val MAX_URI_CHARS = 16 * 1024
    private const val MAX_LEADING_WHITESPACE = 256
    private const val PARCEL_STRING_BYTES_PER_CHAR = 2L
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif")

    enum class FailureCode(val value: String) {
        IMAGE_DATA_LIMIT_EXCEEDED("image_data_limit_exceeded"),
        IMAGE_COUNT_LIMIT_EXCEEDED("image_count_limit_exceeded"),
        IMAGE_INPUT_LIMIT_EXCEEDED("image_input_limit_exceeded"),
        IMAGE_REFERENCE_CACHE_LIMIT_EXCEEDED("image_reference_cache_limit_exceeded"),
        IMAGE_REFERENCE_UNREADABLE("image_reference_unreadable"),
    }

    sealed interface Resolution {
        data class Success(
            val images: List<AgentModelClient.ModelImage>,
        ) : Resolution

        data class Failure(
            val code: FailureCode,
            val message: String,
            val imageCount: Int,
            val estimatedBytes: Long,
            val maxBytes: Long,
        ) : Resolution
    }

    internal class Snapshot internal constructor(
        internal val inputs: List<Input>,
        internal val failure: Resolution.Failure? = null,
    ) {
        val inputCount: Int
            get() = inputs.size

        val isEmpty: Boolean
            get() = inputs.isEmpty() && failure == null

        internal val estimatedChars: Long
            get() = inputs.sumOf { input ->
                input.value.length.toLong() + input.source.length.toLong()
            } + (failure?.message?.length?.toLong() ?: 0L)
    }

    internal class Input internal constructor(
        val value: String,
        val source: String,
        val imageHint: Boolean,
    )

    private data class Candidate(
        val value: String,
        val source: String,
    )

    private class TraversalBudget {
        var nodes: Int = 0
            private set
        var exceeded: Boolean = false
            private set

        fun consume(): Boolean {
            if (nodes >= MAX_JSON_NODES) {
                exceeded = true
                return false
            }
            nodes++
            return true
        }

        fun markExceeded() {
            exceeded = true
        }
    }

    val Empty = Snapshot(emptyList())

    /**
     * CDM 图片引用按一次请求保存为一个条目，别名只作为索引。
     * 所有变更都在同一把锁内完成，命中任一别名后会连同其他别名一起消费。
     */
    internal class SnapshotCache(
        private val maxEntries: Int,
        private val maxAliases: Int,
        private val maxEstimatedChars: Long,
        private val ttlMillis: Long,
        private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    ) {
        init {
            require(maxEntries > 0)
            require(maxAliases > 0)
            require(maxEstimatedChars > 0)
            require(ttlMillis > 0)
        }

        enum class StoreResult {
            STORED,
            STORED_FAILURE,
            EMPTY,
            TOO_MANY_ALIASES,
            TOO_LARGE,
        }

        data class Stats(
            val entries: Int,
            val aliases: Int,
            val estimatedChars: Long,
        )

        private data class Entry(
            val id: Long,
            val aliases: Set<String>,
            val snapshot: Snapshot,
            val estimatedChars: Long,
            val expiresAtMillis: Long,
        )

        private val entries = LinkedHashMap<Long, Entry>()
        private val entryIdByAlias = HashMap<String, Long>()
        private var nextEntryId = 1L
        private var estimatedChars = 0L

        @Synchronized
        fun store(aliases: Collection<String>, snapshot: Snapshot): StoreResult {
            if (snapshot.isEmpty) return StoreResult.EMPTY
            val normalizedAliases = aliases.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (normalizedAliases.isEmpty()) return StoreResult.EMPTY
            if (normalizedAliases.size > maxAliases) return StoreResult.TOO_MANY_ALIASES

            val aliasChars = normalizedAliases.sumOf { it.length.toLong() }
            var storedSnapshot = snapshot
            var entryChars = storedSnapshot.estimatedChars + aliasChars
            var storedAsFailure = false
            if (entryChars > maxEstimatedChars) {
                storedSnapshot = Snapshot(
                    inputs = emptyList(),
                    failure = Resolution.Failure(
                        code = FailureCode.IMAGE_REFERENCE_CACHE_LIMIT_EXCEEDED,
                        message = "图片引用数据过大，无法安全暂存，请重新选择图片或使用远程图片链接",
                        imageCount = snapshot.inputCount.coerceAtLeast(1),
                        estimatedBytes = estimatedBytesForChars(snapshot.estimatedChars),
                        maxBytes = estimatedBytesForChars(maxEstimatedChars),
                    ),
                )
                entryChars = storedSnapshot.estimatedChars + aliasChars
                if (entryChars > maxEstimatedChars) return StoreResult.TOO_LARGE
                storedAsFailure = true
            }

            val now = nowMillis()
            purgeExpired(now)
            normalizedAliases.mapNotNullTo(linkedSetOf()) { entryIdByAlias[it] }
                .forEach(::removeEntry)

            while (
                entries.size >= maxEntries ||
                entryIdByAlias.size + normalizedAliases.size > maxAliases ||
                estimatedChars + entryChars > maxEstimatedChars
            ) {
                val oldestId = entries.keys.firstOrNull() ?: break
                removeEntry(oldestId)
            }

            val entryId = nextEntryId++
            val entry = Entry(
                id = entryId,
                aliases = normalizedAliases.toSet(),
                snapshot = storedSnapshot,
                estimatedChars = entryChars,
                expiresAtMillis = expiresAt(now),
            )
            entries[entryId] = entry
            entry.aliases.forEach { alias -> entryIdByAlias[alias] = entryId }
            estimatedChars += entryChars
            return if (storedAsFailure) StoreResult.STORED_FAILURE else StoreResult.STORED
        }

        @Synchronized
        fun consume(aliases: Collection<String>): Snapshot {
            purgeExpired(nowMillis())
            val entryId = aliases.asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull(entryIdByAlias::get)
                .firstOrNull()
                ?: return Empty
            return removeEntry(entryId)?.snapshot ?: Empty
        }

        @Synchronized
        fun stats(): Stats {
            purgeExpired(nowMillis())
            return Stats(entries.size, entryIdByAlias.size, estimatedChars)
        }

        private fun purgeExpired(now: Long) {
            entries.values.asSequence()
                .filter { entry -> now >= entry.expiresAtMillis }
                .map { it.id }
                .toList()
                .forEach(::removeEntry)
        }

        private fun removeEntry(entryId: Long): Entry? {
            val entry = entries.remove(entryId) ?: return null
            entry.aliases.forEach { alias ->
                entryIdByAlias.remove(alias, entryId)
            }
            estimatedChars -= entry.estimatedChars
            return entry
        }

        private fun expiresAt(now: Long): Long =
            if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis

        private fun estimatedBytesForChars(chars: Long): Long =
            if (chars > Long.MAX_VALUE / PARCEL_STRING_BYTES_PER_CHAR) {
                Long.MAX_VALUE
            } else {
                chars * PARCEL_STRING_BYTES_PER_CHAR
            }
    }

    /**
     * Hook 热路径只读取已确认的图片字段并保存字符串，不读取 URI/文件，也不编码图片。
     */
    fun capturePayload(namespace: String?, name: String?, payload: Any?): Snapshot {
        if (payload == null || namespace != "Nlp" || name != "Doc") return Empty
        val type = invokeKnownNoArgs(payload, "getType") as? String
        if (!type.equals("image", ignoreCase = true)) return Empty
        val inputs = ArrayList<Input>(4)

        var failure = collectKnownImageItems(
            value = invokeKnownNoArgs(payload, "getImagePreProcessResult"),
            source = "message.nlp.doc.imagePreProcessResult",
            inputs = inputs,
        )
        if (failure == null) {
            failure = addInput(
                inputs = inputs,
                value = invokeKnownNoArgs(payload, "getUrl") as? String,
                source = "message.nlp.doc.url",
            )
        }

        return Snapshot(inputs.toList(), failure)
    }

    /** 保存可能包含图片引用的文本，JSON 解析延迟到 Agent 后台线程。 */
    fun captureText(text: String?, source: String): Snapshot {
        if (text.isNullOrEmpty()) return Empty
        val imageHint = source.hasImageHint()
        if (!imageHint && !text.isPotentialImageInput()) return Empty
        if (text.length > MAX_INPUT_CHARS) {
            return Snapshot(
                inputs = emptyList(),
                failure = oversizedReferenceFailure(text.length, source),
            )
        }
        return Snapshot(listOf(Input(text, source, imageHint)))
    }

    fun merge(vararg snapshots: Snapshot): Snapshot {
        val totalInputs = snapshots.sumOf { it.inputs.size.toLong() }
        val failure = snapshots.firstNotNullOfOrNull(Snapshot::failure)
            ?: if (totalInputs > MAX_INPUTS) {
                inputLimitFailure(totalInputs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else {
                null
            }
        return Snapshot(
            inputs = snapshots.asSequence()
                .flatMap { it.inputs.asSequence() }
                .take(MAX_INPUTS)
                .toList(),
            failure = failure,
        )
    }

    /** 必须从后台线程调用；这里解析 JSON 与图片引用，真正的跨进程正文由文件描述符承载。 */
    fun resolve(context: Context?, snapshot: Snapshot): Resolution {
        snapshot.failure?.let { return it }
        if (snapshot.isEmpty) return Resolution.Success(emptyList())
        val candidates = linkedMapOf<String, Candidate>()
        val budget = TraversalBudget()
        snapshot.inputs.forEach { input ->
            collectFromString(
                text = input.value,
                source = input.source,
                candidates = candidates,
                imageHint = input.imageHint,
                depth = 0,
                budget = budget,
            )
        }
        if (budget.exceeded) {
            return inputLimitFailure(snapshot.inputCount)
        }
        if (candidates.size > MAX_IMAGES) {
            return Resolution.Failure(
                code = FailureCode.IMAGE_COUNT_LIMIT_EXCEEDED,
                message = "一次最多支持 $MAX_IMAGES 张图片，请减少图片数量后再试",
                imageCount = candidates.size,
                estimatedBytes = 0,
                maxBytes = 0,
            )
        }
        val resolvedImages = ArrayList<AgentModelClient.ModelImage>(candidates.size)
        for (candidate in candidates.values) {
            val image = try {
                AgentImageCodec.fromTransferReference(context, candidate.value, candidate.source)
            } catch (_: Exception) {
                null
            }
            if (image == null) {
                return unreadableReferenceFailure(candidates.size)
            }
            resolvedImages += image
        }
        val images = resolvedImages
            .asSequence()
            .distinctBy { image ->
                "${image.mimeType}:${image.bytes}:${image.width}x${image.height}:${image.reference.take(80)}"
            }
            .toList()
        return Resolution.Success(images)
    }

    private fun unreadableReferenceFailure(imageCount: Int): Resolution.Failure =
        Resolution.Failure(
            code = FailureCode.IMAGE_REFERENCE_UNREADABLE,
            message = "无法读取请求中的全部图片，请重新选择图片后再试",
            imageCount = imageCount,
            estimatedBytes = 0,
            maxBytes = 0,
        )

    private fun inputLimitFailure(inputCount: Int): Resolution.Failure =
        Resolution.Failure(
            code = FailureCode.IMAGE_INPUT_LIMIT_EXCEEDED,
            message = "图片输入结构超过安全上限，请减少图片数量或简化图片数据后再试",
            imageCount = inputCount.coerceAtLeast(1),
            estimatedBytes = 0,
            maxBytes = 0,
        )

    fun summary(images: List<AgentModelClient.ModelImage>): String =
        "count=${images.size}, bytes=${images.sumOf { it.bytes }}"

    private fun collectKnownImageItems(
        value: Any?,
        source: String,
        inputs: MutableList<Input>,
    ): Resolution.Failure? {
        val items = when (value) {
            is Iterable<*> -> value.iterator()
            is Array<*> -> value.iterator()
            else -> return null
        }
        var index = 0
        while (items.hasNext() && index < MAX_NESTED_IMAGE_ITEMS) {
            val item = items.next()
            val failure = when (item) {
                is String -> addInput(inputs, item, "$source[$index]")
                null -> null
                else -> addInput(
                    inputs = inputs,
                    value = invokeKnownNoArgs(item, "getUrl") as? String,
                    source = "$source[$index].url",
                )
            }
            if (failure != null) return failure
            index++
        }
        return if (items.hasNext()) inputLimitFailure(index + 1) else null
    }

    private fun oversizedReferenceFailure(valueChars: Int, source: String): Resolution.Failure =
        Resolution.Failure(
            code = FailureCode.IMAGE_DATA_LIMIT_EXCEEDED,
            message = "图片输入数据超过安全上限，请缩小图片后重试",
            imageCount = 1,
            estimatedBytes = estimateStringBytes(valueChars.toLong() + source.length),
            maxBytes = estimateStringBytes(MAX_INPUT_CHARS.toLong()),
        )

    private fun estimateStringBytes(chars: Long): Long =
        (chars + 1L) * PARCEL_STRING_BYTES_PER_CHAR

    private fun addInput(
        inputs: MutableList<Input>,
        value: String?,
        source: String,
    ): Resolution.Failure? {
        if (value.isNullOrEmpty()) return null
        if (inputs.size >= MAX_INPUTS) return inputLimitFailure(inputs.size + 1)
        if (value.length > MAX_INPUT_CHARS) {
            return oversizedReferenceFailure(value.length, source)
        }
        inputs += Input(value, source, imageHint = true)
        return null
    }

    private fun collectFromObject(
        value: Any?,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        depth: Int,
        budget: TraversalBudget,
    ) {
        if (value == null) return
        if (depth > MAX_JSON_DEPTH || candidates.size >= MAX_CANDIDATES) {
            budget.markExceeded()
            return
        }
        if (!budget.consume()) {
            return
        }
        when (value) {
            is String -> collectFromString(
                text = value,
                source = source,
                candidates = candidates,
                imageHint = source.hasImageHint(),
                depth = depth,
                budget = budget,
            )
            is JSONObject -> collectFromJsonObject(value, source, candidates, depth, budget)
            is JSONArray -> collectFromJsonArray(value, source, candidates, depth, budget)
        }
    }

    private fun collectFromString(
        text: String,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        imageHint: Boolean,
        depth: Int,
        budget: TraversalBudget,
    ) {
        if (candidates.size >= MAX_CANDIDATES) {
            budget.markExceeded()
            return
        }
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (depth <= MAX_JSON_DEPTH && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            parseJson(trimmed)?.let { json ->
                collectFromObject(json, source, candidates, depth + 1, budget)
                return
            }
        }
        if (imageHint || trimmed.isDirectImageReference()) {
            candidates.putIfAbsent(trimmed, Candidate(trimmed, source))
        }
    }

    private fun collectFromJsonObject(
        json: JSONObject,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        depth: Int,
        budget: TraversalBudget,
    ) {
        val keys = json.keys()
        while (keys.hasNext()) {
            if (candidates.size >= MAX_CANDIDATES) {
                budget.markExceeded()
                return
            }
            if (!budget.consume()) return
            val key = keys.next()
            val value = json.opt(key)
            val nextSource = "$source.$key"
            if (value == null || value === JSONObject.NULL) continue
            if (value is String) {
                collectFromString(
                    text = value,
                    source = nextSource,
                    candidates = candidates,
                    imageHint = source.hasImageHint() || key.hasImageHint() || value.isDirectImageReference(),
                    depth = depth + 1,
                    budget = budget,
                )
            } else {
                collectFromObject(value, nextSource, candidates, depth + 1, budget)
            }
        }
    }

    private fun collectFromJsonArray(
        json: JSONArray,
        source: String,
        candidates: LinkedHashMap<String, Candidate>,
        depth: Int,
        budget: TraversalBudget,
    ) {
        for (index in 0 until json.length()) {
            if (candidates.size >= MAX_CANDIDATES) {
                budget.markExceeded()
                return
            }
            collectFromObject(json.opt(index), "$source[$index]", candidates, depth + 1, budget)
            if (budget.exceeded) return
        }
    }

    private fun parseJson(text: String): Any? =
        try {
            if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
        } catch (_: Exception) {
            null
        }

    private fun invokeKnownNoArgs(target: Any, methodName: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(methodName).apply { isAccessible = true }
                return method.invoke(target)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun String.hasImageHint(): Boolean {
        val lower = lowercase()
        return lower.contains("image") ||
            lower.contains("images") ||
            lower.contains("photo") ||
            lower.contains("picture") ||
            lower.contains("pic") ||
            lower.contains("screenshot") ||
            lower.contains("thumbnail") ||
            lower.contains("base64") ||
            lower.contains("uri") ||
            lower.contains("url") && lower.contains("img") ||
            lower.contains("path") && (lower.contains("img") || lower.contains("image"))
    }

    private fun String.isPotentialImageInput(): Boolean {
        var start = 0
        while (start < length && start < MAX_LEADING_WHITESPACE && this[start].isWhitespace()) start++
        if (start >= length) return false
        if (start == MAX_LEADING_WHITESPACE && this[start].isWhitespace()) return false
        val first = this[start]
        return first == '{' || first == '[' || isDirectImageReference(start)
    }

    private fun String.isDirectImageReference(): Boolean = isDirectImageReference(0)

    private fun String.isDirectImageReference(start: Int): Boolean =
        startsWith("content://", start) ||
            startsWith("file://", start) ||
            startsWith("data:image/", start) ||
            startsWith("http://", start) && hasImageExtension(start) ||
            startsWith("https://", start) && hasImageExtension(start) ||
            getOrNull(start) == '/' && hasImageExtension(start)

    private fun String.hasImageExtension(start: Int = 0): Boolean {
        val scanEnd = minOf(length, start + MAX_URI_CHARS)
        var end = scanEnd
        for (index in start until scanEnd) {
            if (this[index] == '?' || this[index] == '#') {
                end = index
                break
            }
        }
        if (scanEnd < length && end == scanEnd) return false
        return imageExtensions.any { extension ->
            end - start >= extension.length &&
                regionMatches(end - extension.length, extension, 0, extension.length, ignoreCase = true)
        }
    }
}
