package io.github.mangi.eta.hook.vivo

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Reconcile BlueLM's append-only accumulator with retries and authoritative block replacements. */
internal class VivoStreamingTargets private constructor(
    val onResult: Method,
    private val accumulated: Field,
    private val previous: Field,
    private val getParams: Method,
    private val getTrace: Method,
    private val getRequest: Method,
    private val getData: Method,
    private val getLast: Method,
    private val getExtras: Method,
    private val getText: Method,
    private val setText: Method,
) {
    fun prepare(processor: Any, message: Any, isOwned: (String, String, String) -> Boolean) {
        val params = getParams.invoke(message) ?: return
        val data = getData.invoke(params) ?: return
        @Suppress("UNCHECKED_CAST")
        val extras = (if (data is Map<*, *>) data["extras"] else if (getExtras.declaringClass.isInstance(data))
            getExtras.invoke(data) else null) as? MutableMap<String, Any?> ?: return
        val token = extras[VivoReplyProtocol.STREAM_TOKEN] as? String ?: return
        val text = extras[VivoReplyProtocol.STREAM_TEXT] as? String ?: return
        val trace = getTrace.invoke(params) as? String ?: return
        val request = getRequest.invoke(params) as? String ?: return
        if (!isOwned(trace, request, token)) return
        extras.remove(VivoReplyProtocol.STREAM_TOKEN)
        extras.remove(VivoReplyProtocol.STREAM_TEXT)
        val old = accumulated.get(processor)
        val oldParams = previous.get(processor)?.let(getParams::invoke)
        val last = getLast.invoke(params) == true
        if (old != null && oldParams != null && getTrace.invoke(oldParams) == trace && getRequest.invoke(oldParams) == request) {
            val delta = (if (data is Map<*, *>) data["text"] else getText.invoke(data)) as? String ?: ""
            setText.invoke(old, if (last) text else text.removeSuffix(delta))
        } else if (last) {
            // A native UI reset may have removed the accumulator while our packets were queued.
            if (data is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (data as MutableMap<String, Any?>)["text"] = text
            } else setText.invoke(data, text)
        }
    }

    companion object {
        fun resolve(loader: ClassLoader): VivoStreamingTargets? = runCatching {
            val message = loader.loadClass("com.vivo.ai.chat.MessageParams")
            val params = loader.loadClass("com.vivo.ai.chat.GptParams")
            val data = loader.loadClass("com.vivo.ai.chat.MessageExtents")
            val processor = loader.loadClass("repackage_name.w77")
            VivoStreamingTargets(
                processor.getDeclaredMethod("onResult", message, message),
                processor.getDeclaredField("a").apply { isAccessible = true },
                processor.getDeclaredField("b").apply { isAccessible = true },
                message.getMethod("getGptParams"), params.getMethod("getTrace_id"), params.getMethod("getRequest_id"),
                params.getMethod("getData"), params.methods.first {
                    it.name in setOf("is_last", "getIs_last") && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
                },
                data.getMethod("getExtras"), data.getMethod("getText"), data.getMethod("setText", String::class.java),
            )
        }.getOrNull()
    }
}
