package io.github.mangi.eta.ui.components.pane

import androidx.compose.ui.util.fastCoerceIn
internal data class ConversationPaneMotion(
    val progress: Float,
    val foregroundOffset: Float,
    val panelOffset: Float,
)

internal fun conversationPaneMotion(offset: Float, paneWidth: Float): ConversationPaneMotion {
    val width = paneWidth.takeIf { it.isFinite() && it > 0f } ?: 0f
    val displacement = if (offset.isFinite()) offset.fastCoerceIn(0f, width) else 0f
    return ConversationPaneMotion(
        progress = if (width > 0f) displacement / width else 0f,
        foregroundOffset = displacement,
        panelOffset = displacement - width,
    )
}
