package fuck.andes.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotOutcomePolicyTest {
    @Test
    fun `classifies complete partial failed and not requested outcomes`() {
        assertEquals(ScreenshotQuality.NOT_REQUESTED, classify(false, false, false))
        assertEquals(ScreenshotQuality.COMPLETE, classify(true, true, true))
        assertEquals(ScreenshotQuality.PARTIAL, classify(true, true, false))
        assertEquals(ScreenshotQuality.FAILED, classify(true, false, false))
    }

    @Test
    fun `never root-fallbacks across exclusions or a missing critical window`() {
        assertTrue(ScreenshotOutcomePolicy.mayFallbackToRoot(false, false))
        assertFalse(ScreenshotOutcomePolicy.mayFallbackToRoot(true, false))
        assertFalse(ScreenshotOutcomePolicy.mayFallbackToRoot(false, true))
    }

    private fun classify(requested: Boolean, image: Boolean, complete: Boolean): ScreenshotQuality =
        ScreenshotOutcomePolicy.classify(requested, image, complete)
}
