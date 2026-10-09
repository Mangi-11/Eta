package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Old Activity callbacks cannot hide a newer viewer after recreation or navigation. */
internal class VirtualScreenViewerVisibility {
    private val viewer = AtomicReference<String?>()
    private val previews = ConcurrentHashMap.newKeySet<String>()

    fun show(viewerId: String) { viewer.set(viewerId) }
    fun hide(viewerId: String): Boolean = viewer.compareAndSet(viewerId, null)
    fun showPreview(viewerId: String) { previews.add(viewerId) }
    fun hidePreview(viewerId: String): Boolean = previews.remove(viewerId)
    fun isCurrent(viewerId: String): Boolean = viewer.get() == viewerId || viewerId in previews
    val fullViewerVisible: Boolean get() = viewer.get() != null
    val visible: Boolean get() = fullViewerVisible || previews.isNotEmpty()
}
