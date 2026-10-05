package io.github.mangi.eta.agent.device

import io.github.mangi.eta.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegedCommandExecutorTest {
    @Test
    fun noChannelReturnsLegacyRootRequired() {
        rejectingRoot().use { root ->
            val shizuku = ShizukuShellExecutor(context(), NoOpLogger, shizukuAvailable = { false })
            val privileged = PrivilegedCommandExecutor(root, shizuku, { false }, { false })
            assertEquals(false, privileged.elevatedAvailable())
            assertEquals("ROOT_REQUIRED", privileged.execute("id").errorCode)
        }
    }

    @Test
    fun shizukuFallbackIsAttemptedWhenRootMissing() {
        rejectingRoot().use { root ->
            // 测试环境无 Shizuku binder：pingBinder 为 false，执行器应走到 Shizuku 分支
            // 并返回 SHIZUKU_UNAVAILABLE，而非 ROOT_REQUIRED。
            val shizuku = ShizukuShellExecutor(context(), NoOpLogger, shizukuAvailable = { true })
            val privileged = PrivilegedCommandExecutor(root, shizuku, { false }, { true })
            assertEquals(true, privileged.elevatedAvailable())
            assertEquals("SHIZUKU_UNAVAILABLE", privileged.execute("id").errorCode)
        }
    }

    @Test
    fun rootIsPreferredWhenGranted() {
        val root = BoundedRootCommandExecutor(NoOpLogger) { error("root path") }
        val shizuku = ShizukuShellExecutor(context(), NoOpLogger, shizukuAvailable = {
            fail("Root 可用时不应咨询 Shizuku")
            false
        })
        // 外层 rootAvailable 直接返回 true：网关必须走 Root 分支（抛 marker），
        // 且不得咨询任何 shizukuAvailable。
        val privileged = PrivilegedCommandExecutor(root, shizuku, { true }, {
            fail("Root 可用时不应咨询 Shizuku")
            true
        })
        try {
            privileged.execute("id")
            fail("expected root path")
        } catch (expected: IllegalStateException) {
            assertEquals("root path", expected.message)
        }
    }

    private fun context() = RuntimeEnvironment.getApplication()

    private fun rejectingRoot() = BoundedRootCommandExecutor(NoOpLogger) {
        error("无提权通道时不应启动任何进程")
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
