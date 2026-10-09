package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicReference

/** Old Activity callbacks cannot hide a newer viewer after recreation or navigation. */
internal class VirtualScreenViewerVisibility {
    private val viewer = AtomicReference<String?>()

    fun show(viewerId: String) { viewer.set(viewerId) }
    fun hide(viewerId: String): Boolean = viewer.compareAndSet(viewerId, null)
    fun isCurrent(viewerId: String): Boolean = viewer.get() == viewerId
    val visible: Boolean get() = viewer.get() != null
}
