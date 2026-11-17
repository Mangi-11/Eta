package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import io.github.mangi.eta.core.AssistantContextFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaAssistantScreenContextTest {
    @Test
    fun replacingEntryClosesOldContextAndOldOwnerCannotClearNewEntry() = runBlocking {
        val old = EtaAssistantScreenContexts.begin(0, listOf(ComponentName("old.app", "OldActivity")))
        val current = EtaAssistantScreenContexts.begin(0, listOf(ComponentName("current.app", "CurrentActivity")))
        try {
            EtaAssistantScreenContexts.release(old.id)
            assertSame(current, EtaAssistantScreenContexts.find(current.id))
            assertNull(EtaAssistantScreenContexts.find(old.id))
            assertEquals("", old.snapshot().text)
            assertTrue(current.snapshot().text.contains("current.app"))
            assertFalse(current.snapshot().text.contains("old.app"))
        } finally {
            EtaAssistantScreenContexts.release(current.id)
        }
    }

    @Test
    fun deniedScreenshotStillProvidesApplicationIdentity() = runBlocking {
        val capture = EtaAssistantScreenContext(
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
            listOf(ComponentName("current.app", "CurrentActivity")),
        )
        try {
            capture.acceptScreenshot(null)
            val snapshot = capture.snapshot()
            assertNull(snapshot.image)
            assertTrue(snapshot.text.contains("current.app"))
            assertTrue(snapshot.text.contains("未提供可用截图"))
        } finally { capture.close() }
    }

    @Test
    fun closedEntryRejectsLateScreenshotAndReleasesContext() = runBlocking {
        val capture = EtaAssistantScreenContext(0, listOf(ComponentName("closed.app", "Activity")))
        capture.close()
        capture.acceptScreenshot(null)
        assertEquals(EtaAssistantScreenContext.Snapshot(), capture.snapshot())
    }

    @Test
    fun flagsRequestBothChannelsAndAndroid17ScreenContent() {
        val older = AssistantContextFlags.forSdk(34)
        assertTrue(older and VoiceInteractionSession.SHOW_WITH_ASSIST != 0)
        assertTrue(older and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0)
        assertEquals(0, older and VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT)
        assertTrue(AssistantContextFlags.forSdk(37) and VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT != 0)
    }

    @Test
    fun passwordNodesAreExcludedWithoutExcludingOrdinaryTextFields() {
        assertTrue(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(EtaAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }

    @Test
    fun freshVisibleEntryCoalescesDuplicateShowsUntilDismissedOrExpired() {
        assertTrue(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 3_500L,
            ),
        )
        assertFalse(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = true,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_500L,
            ),
        )
        assertFalse(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = true,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_500L,
            ),
        )
        assertFalse(
            EtaAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_000L + EtaAssistantOverlayService.FRESH_ENTRY_COALESCE_WINDOW_MS + 1L,
            ),
        )
    }
}
