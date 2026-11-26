package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class)
class AgentExecutionServiceTest {
    @Test
    @Config(sdk = [33, 36])
    fun agentRunPromotionEndsWhileTerminalSessionRemains() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:terminal-session"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, activeRunCount = 1)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(Build.VERSION.SDK_INT >= 36, notification.extras.getBoolean("android.requestPromotedOngoing"))
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals(Notification.BigTextStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
                assertEquals(context.getString(R.string.overlay_reasoning), notification.extras.getString("android.shortCriticalText"))
                assertNull(notification.getLargeIcon())
            }

            AgentExecutionService.updateRunStatus(context, null, activeRunCount = 0)
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
            assertFalse(remaining.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(remaining.extras.containsKey("android.shortCriticalText"))
            assertNull(remaining.getLargeIcon())
            assertTrue(remaining.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun foregroundExecutionUsesOnlyServiceTypesSupportedByThePlatform() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val service = controller.get()
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                service.foregroundServiceType,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [36])
    fun liveUpdateUsesMinimalBigTextWithChronometerAndTargetConversationIntent() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:title-run"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        AgentExecutionService.updateRunStatus(
            context = context,
            status = AgentOverlayStatus.RunningTool("tap_element"),
            activeRunCount = 1,
            conversationId = "conv-42",
            startedAtEpochMs = 1_700_000_000_000L,
        )
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(Notification.BigTextStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals(context.getString(R.string.tool_tap_element), notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertNull(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            assertNull(notification.getLargeIcon())
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(context.getString(R.string.execution_chip_running), notification.extras.getString("android.shortCriticalText"))
            assertEquals(
                listOf(
                    context.getString(R.string.execution_action_open),
                    context.getString(R.string.execution_action_stop),
                ),
                notification.actions.map { it.title.toString() },
            )
            val launchIntent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(MainActivity.ACTION_OPEN_CONVERSATION_ID, launchIntent.action)
            assertEquals("conv-42", launchIntent.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID))

            // GUI 悬浮胶囊接管顶部时，撤销 Live Update 提升，仅保留普通前台通知。
            AgentExecutionService.updateRunStatus(
                context = context,
                status = AgentOverlayStatus.RunningTool("tap_element"),
                activeRunCount = 1,
                conversationId = "conv-42",
                startedAtEpochMs = 1_700_000_000_000L,
                suppressLiveUpdate = true,
            )
            shadowOf(Looper.getMainLooper()).idle()
            val demoted = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
            assertFalse(demoted.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(demoted.extras.containsKey("android.shortCriticalText"))
            assertTrue(demoted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }
}
