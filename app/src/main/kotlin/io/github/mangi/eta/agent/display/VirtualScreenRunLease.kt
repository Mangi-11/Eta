package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicReference

/** Prevent a delayed cancellation from destroying the next turn's retained display. */
internal class VirtualScreenRunLease(runId: String? = null) {
    private val run = AtomicReference(runId)
    fun acquire(runId: String): Boolean = run.compareAndSet(null, runId) || run.get() == runId
    fun release(runId: String, retain: Boolean): Boolean = run.compareAndSet(runId, if (retain) null else CLOSED)
    fun retireIdle(): Boolean = run.compareAndSet(null, CLOSED)
    private companion object { const val CLOSED = "<closed>" }
}
