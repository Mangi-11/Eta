package io.github.mangi.eta.hook.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HookInstallReportTest {

    @Test
    fun combine_preservesEntriesAndCountsStatuses() {
        val first = report(
            "first",
            HookInstallStatus.INSTALLED,
            HookInstallStatus.MISSING
        )
        val second = report(
            "second",
            HookInstallStatus.FAILED,
            HookInstallStatus.SKIPPED,
            HookInstallStatus.INSTALLED
        )

        val combined = HookInstallReport.combine("process", listOf(first, second))

        assertEquals(5, combined.entries.size)
        assertEquals(2, combined.installedCount)
        assertEquals(1, combined.missingCount)
        assertEquals(1, combined.failedCount)
        assertEquals(1, combined.skippedCount)
        assertEquals(
            "Hook 安装完成: installed=2, missing=1, failed=1, skipped=1",
            combined.summary()
        )
    }

    @Test
    fun capture_whenOrdinaryExceptionOccurs_preservesPriorEntriesAndAddsFailure() {
        val journal = HookInstallJournal("test")
        val expected = IllegalStateException("boom")
        var captured: Exception? = null

        journal.capture(
            block = {
                journal.installed("test.first", "first")
                journal.missing("test.second", "second", "missing")
                throw expected
            },
            onFailure = { captured = it }
        )

        val report = journal.report()
        assertSame(expected, captured)
        assertEquals(3, report.entries.size)
        assertEquals(
            listOf(
                HookInstallStatus.INSTALLED,
                HookInstallStatus.MISSING,
                HookInstallStatus.FAILED
            ),
            report.entries.map { it.status }
        )
        assertEquals("install.failed", report.entries.last().id)
        assertEquals("IllegalStateException", report.entries.last().detail)
    }

    @Test
    fun capture_whenErrorOccurs_doesNotConvertFrameworkErrorsToOrdinaryFailure() {
        val journal = HookInstallJournal("test")
        var callbackInvoked = false

        assertThrows(AssertionError::class.java) {
            journal.capture(
                block = { throw AssertionError("framework failure") },
                onFailure = { callbackInvoked = true }
            )
        }

        assertFalse(callbackInvoked)
        assertTrue(journal.report().entries.isEmpty())
    }

    @Test
    fun methodSelectors_findPublicAndAccessibleDeclaredTargets() {
        val publicMethod = HookSupport.findPublicMethod(MethodFixture::class.java) { method ->
            method.name == "inherited" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        val declaredMethods = HookSupport.findDeclaredMethods(
            clazz = MethodFixture::class.java,
            makeAccessible = true
        ) { method ->
            method.name == "hidden" &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }

        assertNotNull(publicMethod)
        assertEquals(1, declaredMethods.size)
        assertTrue(declaredMethods.single().isAccessible)
    }

    private fun report(
        group: String,
        vararg statuses: HookInstallStatus
    ): HookInstallReport = HookInstallReport(
        group = group,
        entries = statuses.mapIndexed { index, status ->
            HookInstallEntry(
                group = group,
                id = "$group.$index",
                description = "$group-$index",
                status = status
            )
        }
    )

    private open class MethodBase {
        fun inherited(value: String): String = value
    }

    private class MethodFixture : MethodBase() {
        @Suppress("unused")
        private fun hidden(value: Int): Int = value
    }
}
