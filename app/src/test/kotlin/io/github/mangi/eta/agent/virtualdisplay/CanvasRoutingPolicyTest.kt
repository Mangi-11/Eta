package io.github.mangi.eta.agent.virtualdisplay

import android.view.Display
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy.CanvasTarget
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy.RequestedDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRoutingPolicyTest {

    @Test
    fun crossAppDelegationRoutesToVirtualDisplayByDefault() {
        val target = CanvasRoutingPolicy.decideLaunchTarget(
            virtualDisplayEnabled = true,
            backendAvailable = true,
            requested = RequestedDisplay.AUTO,
            targetPackage = "com.sankuai.meituan",
            foregroundPackageOnMainDisplay = "com.tencent.mm",
        )
        assertEquals(CanvasTarget.VIRTUAL_DISPLAY, target)
    }

    @Test
    fun appAlreadyInForegroundOnMainDisplayStaysOnMainDisplayToPreventTaskStealing() {
        val target = CanvasRoutingPolicy.decideLaunchTarget(
            virtualDisplayEnabled = true,
            backendAvailable = true,
            requested = RequestedDisplay.AUTO,
            targetPackage = "com.xingin.xhs",
            foregroundPackageOnMainDisplay = "com.xingin.xhs",
        )
        assertEquals(CanvasTarget.MAIN_DISPLAY, target)
    }

    @Test
    fun systemUiAndEtaOwnPackageAlwaysStayOnMainDisplay() {
        listOf(
            "io.github.mangi.eta",
            "com.android.systemui",
            "com.miui.home",
            "com.oppo.launcher",
        ).forEach { pkg ->
            val target = CanvasRoutingPolicy.decideLaunchTarget(
                virtualDisplayEnabled = true,
                backendAvailable = true,
                requested = RequestedDisplay.VIRTUAL,
                targetPackage = pkg,
                foregroundPackageOnMainDisplay = "com.tencent.mm",
            )
            assertEquals(pkg, CanvasTarget.MAIN_DISPLAY, target)
        }
    }

    @Test
    fun explicitMainRequestOrDisabledSettingRoutesToMainDisplay() {
        assertEquals(
            CanvasTarget.MAIN_DISPLAY,
            CanvasRoutingPolicy.decideLaunchTarget(
                virtualDisplayEnabled = true,
                backendAvailable = true,
                requested = RequestedDisplay.MAIN,
                targetPackage = "com.jingdong.app.mall",
                foregroundPackageOnMainDisplay = "io.github.mangi.eta",
            ),
        )
        assertEquals(
            CanvasTarget.MAIN_DISPLAY,
            CanvasRoutingPolicy.decideLaunchTarget(
                virtualDisplayEnabled = false,
                backendAvailable = true,
                requested = RequestedDisplay.AUTO,
                targetPackage = "com.jingdong.app.mall",
                foregroundPackageOnMainDisplay = "io.github.mangi.eta",
            ),
        )
        assertEquals(
            CanvasTarget.MAIN_DISPLAY,
            CanvasRoutingPolicy.decideLaunchTarget(
                virtualDisplayEnabled = true,
                backendAvailable = false,
                requested = RequestedDisplay.VIRTUAL,
                targetPackage = "com.jingdong.app.mall",
                foregroundPackageOnMainDisplay = "io.github.mangi.eta",
            ),
        )
    }

    @Test
    fun requestedDisplayParsesValidModesAndRejectsUnknownValues() {
        assertEquals(RequestedDisplay.AUTO, RequestedDisplay.parse(null))
        assertEquals(RequestedDisplay.AUTO, RequestedDisplay.parse(""))
        assertEquals(RequestedDisplay.AUTO, RequestedDisplay.parse("auto"))
        assertEquals(RequestedDisplay.VIRTUAL, RequestedDisplay.parse("virtual"))
        assertEquals(RequestedDisplay.MAIN, RequestedDisplay.parse("main"))
        assertNull(RequestedDisplay.parse("invalid_display"))
    }

    @Test
    fun isVirtualDisplayDistinguishesVirtualIdsFromDefaultAndInvalidDisplays() {
        assertFalse(CanvasRoutingPolicy.isVirtualDisplay(Display.DEFAULT_DISPLAY))
        assertFalse(CanvasRoutingPolicy.isVirtualDisplay(Display.INVALID_DISPLAY))
        assertTrue(CanvasRoutingPolicy.isVirtualDisplay(2))
    }

    @Test
    fun trustedInteractiveFlagsUsePlatformFocusBitsAndTheirPrerequisites() {
        val flags = CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS
        assertTrue((flags and CanvasRoutingPolicy.FLAG_TRUSTED) != 0)
        assertTrue((flags and (1 shl 16)) != 0)
        assertTrue((flags and (1 shl 14)) != 0)
        assertFalse((flags and (1 shl 15)) != 0)
        assertFalse((flags and CanvasRoutingPolicy.FLAG_ROTATES_WITH_CONTENT) != 0)
        assertFalse((flags and CanvasRoutingPolicy.FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0)
    }

    @Test
    fun backgroundDisplayRequiresAndroid14FocusIsolation() {
        assertFalse(CanvasRoutingPolicy.supportsBackgroundDisplay(33))
        assertTrue(CanvasRoutingPolicy.supportsBackgroundDisplay(34))
        assertTrue(CanvasRoutingPolicy.supportsBackgroundDisplay(37))
    }

    @Test
    fun displayMustExposeAllIsolationFlagsAfterCreation() {
        val platformDisplayFlags = (1 shl 7) or (1 shl 11) or (1 shl 12)
        assertTrue(CanvasRoutingPolicy.hasRequiredDisplayFlags(platformDisplayFlags))
        for (requiredBit in listOf(7, 11, 12)) {
            assertFalse(CanvasRoutingPolicy.hasRequiredDisplayFlags(platformDisplayFlags and (1 shl requiredBit).inv()))
        }
        assertFalse(CanvasRoutingPolicy.hasRequiredDisplayFlags(CanvasRoutingPolicy.FLAG_TRUSTED))
    }
}
