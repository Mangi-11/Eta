package fuck.andes.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotWindowPolicyTest {
    @Test
    fun `only confirmed own accessibility overlay is excluded`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(overlay = true, resolvedPackage = "fuck.andes"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(overlay = true, resolvedPackage = "third.party.accessibility"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(overlay = true, resolvedPackage = null),
        )
    }

    @Test
    fun `unknown active or focused window blocks package exclusion capture`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(active = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(focused = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(application = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
    }

    @Test
    fun `confirmed excluded package is omitted regardless of window type`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(resolvedPackage = "entry.app", excluded = setOf("entry.app")),
        )
    }

    private fun decide(
        overlay: Boolean = false,
        application: Boolean = false,
        active: Boolean = false,
        focused: Boolean = false,
        resolvedPackage: String? = "normal.app",
        excluded: Set<String> = emptySet(),
    ): ScreenshotWindowPolicy.Decision = ScreenshotWindowPolicy.decide(
        isAccessibilityOverlay = overlay,
        isApplicationWindow = application,
        active = active,
        focused = focused,
        resolvedPackage = resolvedPackage,
        ownPackage = "fuck.andes",
        excludedPackages = excluded,
    )
}
