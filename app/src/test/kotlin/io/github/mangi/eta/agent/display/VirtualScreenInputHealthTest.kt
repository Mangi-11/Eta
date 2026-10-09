package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VirtualScreenInputHealthTest {
    @Test
    fun matchesExactDisplayWindowAndConnection() {
        val result = VirtualScreenInputHealth.parse(dump(false), 96, "com.example.app")
        assertEquals(VirtualScreenInputHealth.Status.UNRESPONSIVE, result.status)
        assertEquals(123, result.pid)
    }

    @Test
    fun unresponsivePrimaryWindowCannotPoisonResponsiveVirtualWindow() {
        assertEquals(VirtualScreenInputHealth.Status.RESPONSIVE,
            VirtualScreenInputHealth.parse(dump(true), 96, "com.example.app").status)
    }

    @Test
    fun otherDisplayAndSimilarlyNamedPackageAreUnknown() {
        for ((display, name) in listOf(0 to "com.example.app", 196 to "com.example.app", 96 to "com.example")) {
            assertEquals(VirtualScreenInputHealth.Status.UNKNOWN,
                VirtualScreenInputHealth.parse(dump(false), display, name).status)
        }
    }

    @Test
    fun hiddenWindowAndUnavailableConnectionRemainUnknown() {
        for (value in listOf(dump(false).replace("inputConfig=NONE", "inputConfig=NOT_VISIBLE"),
            dump(false).replace("inputConfig=NONE", "inputConfig=NO_INPUT_CHANNEL"),
            dump(false).replace("responsive=false", "legacy=true"), "permission denied", "")) {
            assertEquals(VirtualScreenInputHealth.Status.UNKNOWN,
                VirtualScreenInputHealth.parse(value, 96, "com.example.app").status)
        }
    }

    @Test
    fun missingWindowPidDoesNotInventProcessIdentity() {
        val value = dump(false).replace(", ownerPid=123", "")
        assertNull(VirtualScreenInputHealth.parse(value, 96, "com.example.app").pid)
    }

    private fun dump(responsive: Boolean) = """
Input Dispatcher State:
  FocusedWindows:
    displayId=0, name='Window:primary type=1 com.example.app/.Main'
    displayId=96, name='Window:virtual type=1 com.example.app/.Main'
  FocusRequests:
  Display: 0
    Windows:
      0: name=Window:primary type=1 com.example.app/.Main, id=1, displayId=0, inputConfig=NONE, ownerPid=456
  Display: 96
    Windows:
      0: name=Window:virtual type=1 com.example.app/.Main, id=2, displayId=96, inputConfig=NONE, ownerPid=123
  Connections:
    1: channelName='Window:primary type=1 com.example.app/.Main', status=NORMAL, monitor=false, responsive=false
    2: channelName='Window:virtual type=1 com.example.app/.Main', status=NORMAL, monitor=false, responsive=$responsive
""".trimIndent()
}
