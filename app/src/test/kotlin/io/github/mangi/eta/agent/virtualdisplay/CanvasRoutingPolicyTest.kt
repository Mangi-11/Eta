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

    @Test
    fun missingDisplayFlagsDescriptionReportsMissingBits() {
        val full = (1 shl 7) or (1 shl 11) or (1 shl 12)
        assertEquals("", CanvasRoutingPolicy.missingDisplayFlagsDescription(full))
        assertEquals(
            "OWN_FOCUS,STEAL_TOP_FOCUS_DISABLED",
            CanvasRoutingPolicy.missingDisplayFlagsDescription(1 shl 7),
        )
        assertEquals(
            "TRUSTED,OWN_FOCUS,STEAL_TOP_FOCUS_DISABLED",
            CanvasRoutingPolicy.missingDisplayFlagsDescription(0),
        )
    }

    @Test
    fun applyDisplayImePolicyHideRetriesTransientFallbackBeforeSucceeding() {
        class FakeWms {
            var calls = 0
            var policy = CanvasRoutingPolicy.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
            fun setDisplayImePolicy(displayId: Int, newPolicy: Int) {
                calls++
                if (calls >= 2 && displayId == 5) {
                    policy = newPolicy
                }
            }
            fun getDisplayImePolicy(displayId: Int): Int =
                if (displayId == 5) policy else CanvasRoutingPolicy.DISPLAY_IME_POLICY_LOCAL
        }
        val delays = mutableListOf<Long>()
        val result = CanvasRoutingPolicy.applyDisplayImePolicyHide(
            wms = FakeWms(),
            displayId = 5,
            retryDelaysMs = longArrayOf(15L, 30L),
            sleep = { delays.add(it) },
        )
        assertTrue(result.ok)
        assertEquals(CanvasRoutingPolicy.DISPLAY_IME_POLICY_HIDE, result.actualPolicy)
        assertEquals(listOf(15L), delays)
    }

    @Test
    fun applyDisplayImePolicyHideReportsMismatchAndExceptionsExplicitly() {
        class MismatchedWms {
            fun setDisplayImePolicy(displayId: Int, newPolicy: Int) = Unit
            fun getDisplayImePolicy(displayId: Int): Int = CanvasRoutingPolicy.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
        }
        val mismatch = CanvasRoutingPolicy.applyDisplayImePolicyHide(
            wms = MismatchedWms(),
            displayId = 3,
            retryDelaysMs = longArrayOf(10L),
            sleep = {},
        )
        assertFalse(mismatch.ok)
        assertEquals("IME_POLICY_MISMATCH", mismatch.reason)
        assertEquals(CanvasRoutingPolicy.DISPLAY_IME_POLICY_FALLBACK_DISPLAY, mismatch.actualPolicy)

        class ThrowingWms {
            fun setDisplayImePolicy(displayId: Int, newPolicy: Int) {
                throw SecurityException("denied")
            }
            fun getDisplayImePolicy(displayId: Int): Int = 1
        }
        val failed = CanvasRoutingPolicy.applyDisplayImePolicyHide(
            wms = ThrowingWms(),
            displayId = 3,
        )
        assertFalse(failed.ok)
        assertEquals("IME_POLICY_EXCEPTION", failed.reason)
        assertEquals("SecurityException", failed.errorType)

        class IncompleteWms {
            fun setDisplayImePolicy(displayId: Int, newPolicy: Int) = Unit
        }
        val missingGet = CanvasRoutingPolicy.applyDisplayImePolicyHide(
            wms = IncompleteWms(),
            displayId = 3,
        )
        assertFalse(missingGet.ok)
        assertEquals("GET_POLICY_METHOD_MISSING", missingGet.reason)
    }

    @Test
    fun decideLaunchPlanReportsDiagnosticReasons() {
        assertEquals(
            "virtual_disabled",
            CanvasRoutingPolicy.decideLaunchPlan(
                virtualDisplayEnabled = false,
                backendAvailable = true,
            ).reason,
        )
        assertEquals(
            "backend_unavailable",
            CanvasRoutingPolicy.decideLaunchPlan(
                virtualDisplayEnabled = true,
                backendAvailable = false,
            ).reason,
        )
        assertEquals(
            "already_on_main",
            CanvasRoutingPolicy.decideLaunchPlan(
                virtualDisplayEnabled = true,
                backendAvailable = true,
                targetPackage = "com.tencent.mm",
                foregroundPackageOnMainDisplay = "com.tencent.mm",
            ).reason,
        )
        assertEquals(
            "auto_virtual",
            CanvasRoutingPolicy.decideLaunchPlan(
                virtualDisplayEnabled = true,
                backendAvailable = true,
                targetPackage = "com.sankuai.meituan",
                foregroundPackageOnMainDisplay = "com.tencent.mm",
            ).reason,
        )
    }
}
