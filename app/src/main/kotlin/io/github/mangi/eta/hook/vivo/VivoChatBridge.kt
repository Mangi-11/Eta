package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.core.HookSupport
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Resolve the complete reply path before claiming a request. No DEX scanning at runtime. */
internal class VivoChatBridge private constructor(
    private val center: Any,
    private val post: Method,
    private val eventConstructor: Constructor<*>,
    private val requestField: Field,
    private val resultField: Field,
    private val traceField: Field,
) {
    data class Request(
        val message: Any,
        val turn: VivoReplyProtocol.Turn,
        val text: String,
        val hasAttachments: Boolean,
    )

    fun start(request: Request) {
        post(request, state = 1)
        post(request, state = 2)
    }

    fun complete(request: Request, text: String) {
        post(request, state = 3, result = VivoReplyProtocol.reply(request.turn, text))
        post(request, state = 5)
    }

    private fun post(request: Request, state: Int, result: String? = null) {
        val event = eventConstructor.newInstance(state)
        requestField.set(event, request.message)
        resultField.set(event, result)
        traceField.set(event, request.turn.traceId)
        post.invoke(center, event)
    }

    internal class Targets private constructor(
        val sendChat: Method,
        val cancel: Method,
        private val getGptParams: Method,
        private val getMainType: Method,
        private val getSubType: Method,
        private val getData: Method,
        private val getText: Method,
        private val getTraceId: Method,
        private val getRequestId: Method,
        private val getSessionId: Method,
        private val getProductId: Method,
        private val extentsClass: Class<*>,
        private val getImages: Method,
        private val getFiles: Method,
        private val getFileType: Method,
        private val centerField: Field,
        private val post: Method,
        private val eventConstructor: Constructor<*>,
        private val requestField: Field,
        private val resultField: Field,
        private val traceField: Field,
    ) {
        fun request(message: Any): Request? {
            val params = getGptParams.invoke(message) ?: return null
            if (getMainType.invoke(params) != "data" || getSubType.invoke(params) != "ask") return null
            val data = getData.invoke(params) ?: return null
            if (!extentsClass.isInstance(data)) return null
            val text = getText.invoke(data) as? String ?: return null
            val turn = VivoReplyProtocol.Turn(
                traceId = getTraceId.invoke(params) as? String ?: return null,
                requestId = getRequestId.invoke(params) as? String ?: return null,
                sessionId = getSessionId.invoke(params) as? String ?: return null,
                productId = getProductId.invoke(params) as? String ?: return null,
            )
            if (turn.traceId.isBlank() || turn.requestId.isBlank()) return null
            val images = getImages.invoke(data) as? Collection<*>
            val files = getFiles.invoke(data) as? Collection<*>
            val fileType = getFileType.invoke(data) as? String
            return Request(
                message, turn, text,
                hasAttachments = !images.isNullOrEmpty() || !files.isNullOrEmpty() ||
                    (!fileType.isNullOrBlank() && fileType != "text"),
            )
        }

        /** Called only after the host application has initialized ARouter. */
        fun bind(): VivoChatBridge? {
            val center = centerField.get(null) ?: return null
            if (!post.declaringClass.isInstance(center)) return null
            return VivoChatBridge(
                center, post, eventConstructor, requestField, resultField, traceField,
            )
        }

        companion object {
            const val LINKER_CLASS = "com.vivo.ai.copilot.common.core.link.CopilotSpeechGptLinker"

            fun resolve(loader: ClassLoader): Targets? {
                val linker = HookSupport.findClassOrNull(loader, LINKER_CLASS) ?: return null
                val message = HookSupport.findClassOrNull(loader, "com.vivo.ai.chat.MessageParams") ?: return null
                val params = HookSupport.findClassOrNull(loader, "com.vivo.ai.chat.GptParams") ?: return null
                val extents = HookSupport.findClassOrNull(loader, "com.vivo.ai.chat.MessageExtents") ?: return null
                val node = HookSupport.findClassOrNull(loader, "com.vivo.ai.copilot.core.link.LinkNodeType") ?: return null
                val event = HookSupport.findClassOrNull(loader, "repackage_name.yd") ?: return null
                val baseEvent = HookSupport.findClassOrNull(loader, "repackage_name.ut2") ?: return null
                val center = HookSupport.findClassOrNull(loader, "com.vivo.ai.copilot.core.ICopilotEventCenter") ?: return null
                val holder = HookSupport.findClassOrNull(loader, "repackage_name.vt2") ?: return null
                val send = HookSupport.findMethod(linker, "sendChat", message) ?: return null
                val cancel = HookSupport.findMethod(linker, "cancel", node, Boolean::class.javaPrimitiveType!!) ?: return null
                if (send.returnType != Void.TYPE || cancel.returnType != Void.TYPE) return null
                val requestField = HookSupport.findField(event, "b")?.takeIf { it.type == message } ?: return null
                val resultField = HookSupport.findField(event, "c")?.takeIf { it.type == String::class.java } ?: return null
                val traceField = HookSupport.findField(event, "i")?.takeIf { it.type == String::class.java } ?: return null
                val centerField = HookSupport.findField(holder, "a")?.takeIf { it.type == center } ?: return null
                if (!baseEvent.isAssignableFrom(event)) return null
                return Targets(
                    send, cancel,
                    HookSupport.findMethod(message, "getGptParams") ?: return null,
                    HookSupport.findMethod(params, "getMain_type") ?: return null,
                    HookSupport.findMethod(params, "getSubs_type") ?: return null,
                    HookSupport.findMethod(params, "getData") ?: return null,
                    HookSupport.findMethod(extents, "getText") ?: return null,
                    HookSupport.findMethod(params, "getTrace_id") ?: return null,
                    HookSupport.findMethod(params, "getRequest_id") ?: return null,
                    HookSupport.findMethod(params, "getSid") ?: return null,
                    HookSupport.findMethod(params, "getPro_id") ?: return null,
                    extents,
                    HookSupport.findMethod(extents, "getLocalImageList2") ?: return null,
                    HookSupport.findMethod(extents, "getLocalFileList") ?: return null,
                    HookSupport.findMethod(extents, "getSend_filetype") ?: return null,
                    centerField,
                    HookSupport.findMethod(center, "post", baseEvent) ?: return null,
                    event.getDeclaredConstructor(Int::class.javaPrimitiveType!!).apply { isAccessible = true },
                    requestField, resultField, traceField,
                )
            }
        }
    }
}
