package io.github.mangi.eta.agent.display

internal data class VirtualDisplayInfo(
    val sessionId: String,
    val owner: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val manualInputGeneration: Long = 0,
    val density: Int = 0,
    val focusedPackage: String = "",
    val rotation: Int = 0,
) {
    fun hasSameGeometry(other: VirtualDisplayInfo): Boolean =
        width == other.width && height == other.height && rotation == other.rotation

    fun withGeometry(width: Int, height: Int, rotation: Int): VirtualDisplayInfo =
        if (this.width == width && this.height == height && this.rotation == rotation) this else copy(
            width = width, height = height, rotation = rotation, manualInputGeneration = manualInputGeneration + 1,
        )
}

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
    val taskPhase: VirtualScreenTaskPhase = VirtualScreenTaskPhase.IDLE,
    val activeRunId: String? = null,
    val operations: List<VirtualScreenOperation> = emptyList(),
    val fullViewerVisible: Boolean = false,
) {
    val isAgentControlling: Boolean get() = display != null && taskPhase == VirtualScreenTaskPhase.RUNNING

    fun beginRun(runId: String) = copy(taskPhase = VirtualScreenTaskPhase.RUNNING, activeRunId = runId)

    fun finishRun(runId: String, success: Boolean, cancelled: Boolean, paused: Boolean = false): VirtualScreenViewerState =
        if (activeRunId != runId) this else copy(
            activeRunId = null,
            taskPhase = when { paused && !cancelled -> VirtualScreenTaskPhase.PAUSED
                cancelled -> VirtualScreenTaskPhase.STOPPED
                success -> VirtualScreenTaskPhase.COMPLETED
                else -> VirtualScreenTaskPhase.FAILED },
        )

    fun record(operation: VirtualScreenOperation) = copy(operations = (operations + operation).takeLast(100))
}

internal enum class VirtualScreenTaskPhase { IDLE, RUNNING, COMPLETED, FAILED, STOPPED, PAUSED }

internal data class VirtualScreenOperation(
    val id: Long,
    val name: String,
    val timestamp: Long,
    val success: Boolean,
    val manual: Boolean,
)
