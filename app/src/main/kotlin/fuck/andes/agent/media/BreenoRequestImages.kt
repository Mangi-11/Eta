package fuck.andes.agent.media

import fuck.andes.agent.model.BreenoModelClient
import fuck.andes.core.HookSupport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object BreenoRequestImages {
    private const val MAX_IMAGES = 4
    private const val MAX_CANDIDATES = 32

    fun fromMessage(context: Context?, message: Any?): List<BreenoModelClient.ModelImage> {
        if (message == null) return emptyList()
        val candidates = linkedMapOf<String, String>()
        val events = HookSupport.invokeNoArgs(message, "getEvents") as? Iterable<*> ?: return emptyList()
        events.forEachIndexed { index, event ->
            val payload = HookSupport.invokeNoArgs(event ?: return@forEachIndexed, "getPayload")
            collectFromObject(payload, "message.event[$index].payload", candidates, depth = 0)
        }
        return resolve(context, candidates)
    }

    fun fromText(context: Context?, text: String?, source: String): List<BreenoModelClient.ModelImage> {
        if (text.isNullOrBlank()) return emptyList()
        val candidates = linkedMapOf<String, String>()
        collectFromString(text, source, candidates, imageHint = source.hasImageHint(), depth = 0)
        return resolve(context, candidates)
    }

    fun summary(images: List<BreenoModelClient.ModelImage>): String =
        images.joinToString(prefix = "[", postfix = "]") { image ->
            val size = if (image.width != null && image.height != null) {
                "${image.width}x${image.height}"
            } else {
                "unknown"
            }
            "${image.source}:$size/${image.bytes}B"
        }

    private fun collectFromObject(
        value: Any?,
        source: String,
        candidates: LinkedHashMap<String, String>,
        depth: Int
    ) {
        if (value == null || depth > 4 || candidates.size >= MAX_CANDIDATES) return
        when (value) {
            is String -> collectFromString(value, source, candidates, imageHint = source.hasImageHint(), depth = depth)
            is JSONObject -> collectFromJsonObject(value, source, candidates, depth)
            is JSONArray -> collectFromJsonArray(value, source, candidates, depth)
            is Iterable<*> -> value.forEachIndexed { index, item ->
                collectFromObject(item, "$source[$index]", candidates, depth + 1)
            }
            is Array<*> -> value.forEachIndexed { index, item ->
                collectFromObject(item, "$source[$index]", candidates, depth + 1)
            }
            else -> collectFromReflectedObject(value, source, candidates, depth)
        }
    }

    private fun collectFromReflectedObject(
        value: Any,
        source: String,
        candidates: LinkedHashMap<String, String>,
        depth: Int
    ) {
        val methods = (value.javaClass.methods.asSequence() + value.javaClass.declaredMethods.asSequence())
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.name != "getClass" &&
                    method.returnType != Void.TYPE &&
                    (method.name.hasImageHint() || method.name in setOf("getData", "getContent", "getPayload"))
            }
            .distinctBy { "${it.name}/${it.returnType.name}" }
            .take(24)
            .toList()
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                val result = method.invoke(value)
                collectFromObject(result, "$source.${method.name}", candidates, depth + 1)
            }
        }
    }

    private fun collectFromString(
        text: String,
        source: String,
        candidates: LinkedHashMap<String, String>,
        imageHint: Boolean,
        depth: Int
    ) {
        if (candidates.size >= MAX_CANDIDATES) return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJson(trimmed)?.let { json ->
                collectFromObject(json, source, candidates, depth + 1)
                return
            }
        }
        if (imageHint || trimmed.isDirectImageReference()) {
            candidates.putIfAbsent(trimmed, source)
        }
    }

    private fun collectFromJsonObject(
        json: JSONObject,
        source: String,
        candidates: LinkedHashMap<String, String>,
        depth: Int
    ) {
        val keys = json.keys()
        while (keys.hasNext() && candidates.size < MAX_CANDIDATES) {
            val key = keys.next()
            val value = json.opt(key)
            val nextSource = "$source.$key"
            when (value) {
                is String -> collectFromString(
                    value,
                    nextSource,
                    candidates,
                    imageHint = key.hasImageHint() || value.isDirectImageReference(),
                    depth = depth + 1
                )
                else -> collectFromObject(value, nextSource, candidates, depth + 1)
            }
        }
    }

    private fun collectFromJsonArray(
        json: JSONArray,
        source: String,
        candidates: LinkedHashMap<String, String>,
        depth: Int
    ) {
        for (index in 0 until json.length()) {
            if (candidates.size >= MAX_CANDIDATES) return
            collectFromObject(json.opt(index), "$source[$index]", candidates, depth + 1)
        }
    }

    private fun resolve(
        context: Context?,
        candidates: LinkedHashMap<String, String>
    ): List<BreenoModelClient.ModelImage> =
        candidates.entries
            .asSequence()
            .mapNotNull { (value, source) ->
                runCatching { BreenoImageCodec.fromReference(context, value, source) }.getOrNull()
            }
            .distinctBy { image -> "${image.mimeType}:${image.bytes}:${image.width}x${image.height}:${image.dataUrl.take(80)}" }
            .take(MAX_IMAGES)
            .toList()

    private fun parseJson(text: String): Any? =
        runCatching {
            if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
        }.getOrNull()

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

    private fun String.isDirectImageReference(): Boolean =
        startsWith("content://") ||
            startsWith("file://") ||
            startsWith("data:image/") ||
            startsWith("http://") && hasImageExtension() ||
            startsWith("https://") && hasImageExtension() ||
            startsWith("/") && hasImageExtension()

    private fun String.hasImageExtension(): Boolean {
        val path = substringBefore("?").substringBefore("#").lowercase()
        return path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".heic") ||
            path.endsWith(".heif")
    }
}
