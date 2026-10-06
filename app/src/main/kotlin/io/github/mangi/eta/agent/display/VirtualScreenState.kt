package io.github.mangi.eta.agent.display

internal data class VirtualDisplayInfo(
    val sessionId: String,
    val owner: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val manualInputGeneration: Long = 0,
    val density: Int = 0,
)

internal data class VirtualScreenGesture(
    val id: Long,
    val sessionId: String,
    val action: String,
    val x: Int,
    val y: Int,
    val endX: Int = x,
    val endY: Int = y,
    val durationMs: Int = 500,
    val startedAt: Long,
)

internal data class VirtualScreenViewerState(
    val display: VirtualDisplayInfo? = null,
    val gesture: VirtualScreenGesture? = null,
    val lastAction: String = "",
)
