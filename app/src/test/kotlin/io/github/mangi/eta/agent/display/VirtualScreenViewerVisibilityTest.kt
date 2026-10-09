package io.github.mangi.eta.agent.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenViewerVisibilityTest {
    @Test
    fun pauseStopsRequestsSynchronouslyWithoutWaitingForRootIpc() {
        val visibility = VirtualScreenViewerVisibility()
        visibility.show("viewer")
        assertTrue(visibility.isCurrent("viewer"))
        assertTrue(visibility.hide("viewer"))
        assertFalse(visibility.visible)
        assertFalse(visibility.isCurrent("viewer"))
    }

    @Test
    fun latePauseFromOldActivityCannotHideReplacement() {
        val visibility = VirtualScreenViewerVisibility()
        visibility.show("old")
        visibility.show("new")
        assertFalse(visibility.hide("old"))
        assertTrue(visibility.visible)
        assertTrue(visibility.isCurrent("new"))
        assertFalse(visibility.isCurrent("old"))
    }

    @Test
    fun repeatedPauseCannotUndoAResumedViewer() {
        val visibility = VirtualScreenViewerVisibility()
        visibility.show("old")
        assertTrue(visibility.hide("old"))
        visibility.show("new")
        assertFalse(visibility.hide("old"))
        assertTrue(visibility.isCurrent("new"))
    }
}
