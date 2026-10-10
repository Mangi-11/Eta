package io.github.mangi.eta.agent.display

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualScreenAppRotationTest {
    @Test
    fun fixedAndReverseOrientationsFollowNaturalPortraitAxes() {
        assertEquals(1, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, false, 0))
        assertEquals(0, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, false, 1))
        assertEquals(3, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, false, 0))
        assertEquals(2, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, false, 0))
    }

    @Test
    fun naturalLandscapeDisplaysAndLockedOrientationKeepCorrectAxes() {
        assertEquals(0, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, true, 1))
        assertEquals(1, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, true, 0))
        assertEquals(3, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, true, 0))
        assertEquals(2, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_LOCKED, false, 2))
        assertEquals(0, VirtualScreenAppRotation.rotationFor(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, false, 1))
    }
}
