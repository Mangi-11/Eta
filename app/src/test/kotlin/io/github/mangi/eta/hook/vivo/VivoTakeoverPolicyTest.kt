package io.github.mangi.eta.hook.vivo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VivoTakeoverPolicyTest {
    @Test
    fun explicitPrefixDoesNotOverrideDisabledTakeoverOrAttachments() {
        assertNull(VivoTakeoverPolicy.prompt("/agent hello", enabled = false, requirePrefix = true))
        assertNull(VivoTakeoverPolicy.prompt("/agent hello", true, true, hasAttachments = true))
    }

    @Test
    fun prefixModeLeavesOrdinaryQueriesAndEmptyCommandsNative() {
        listOf("hello", "/agent", "/agent   ", "/agentic hello", "/agenthello").forEach {
            assertNull(VivoTakeoverPolicy.prompt(it, enabled = true, requirePrefix = true))
        }
        assertEquals("你好", VivoTakeoverPolicy.prompt("  /agent 你好  ", true, true))
        assertEquals("hello", VivoTakeoverPolicy.prompt("/agent%20hello", true, true))
    }

    @Test
    fun allQueryModeStillRejectsEmptyOrOversizedInputs() {
        assertEquals("hello", VivoTakeoverPolicy.prompt("hello", true, false))
        assertNull(VivoTakeoverPolicy.prompt("  ", true, false))
        assertNull(VivoTakeoverPolicy.prompt("x".repeat(32_001), true, false))
    }

    @Test
    fun unknownAssistantVersionsKeepNativeBehavior() {
        assertTrue(VivoTakeoverPolicy.isSupportedVersion(68503L))
        assertFalse(VivoTakeoverPolicy.isSupportedVersion(68504L))
        assertFalse(VivoTakeoverPolicy.isSupportedVersion(-1L))
    }
}
