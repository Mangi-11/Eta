package fuck.andes.agent.runtime

import fuck.andes.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntrySurfaceGuardTest {
    @Test
    fun disabledHandoffDoesNotCreateGuard() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = false),
            logger = NoOpLogger,
        )

        assertNull(guard)
    }

    @Test
    fun breenoGuardOwnsAOneShotScreenshotExclusion() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertEquals("com.heytap.speechassist", guard?.targetPackageName)
        assertEquals(setOf("com.heytap.speechassist"), guard?.consumeScreenshotExcludedPackages())
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    @Test
    fun unknownEntryStillCreatesDismissGuardWithoutGuessingAPackage() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "future_entry", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertNull(guard?.targetPackageName)
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    private fun handoff(source: String, dismiss: Boolean) =
        AgentRuntimeWire.EntryHandoff(
            id = "run-1",
            source = source,
            payload = "{}",
            dismissEntrySurfaceOnForegroundOperation = dismiss,
        )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
