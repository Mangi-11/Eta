package fuck.andes.agent.runtime

import java.util.concurrent.CopyOnWriteArraySet

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    fun throwIfCancelled() {
        if (cancelled) throw AgentRunCancelledException()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        fun cancel() = cancelBlock()
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
