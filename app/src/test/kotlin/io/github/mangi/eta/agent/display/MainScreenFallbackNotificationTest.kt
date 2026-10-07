package io.github.mangi.eta.agent.display

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MainScreenFallbackNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun appConflictProvidesThreeChoicesAndThePromptSurvivesMissingNotifications() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val result = worker.submit<MainScreenFallbackDecision> {
                MainScreenFallbackApproval.request(context, "conflict", "launch_app", "APP_ALREADY_RUNNING", { false }, { true })
            }
            val notification = waitForNotification()
            assertEquals(3, notification.actions.size)
            assertTrue(notification.actions[0].isAuthenticationRequired)
            assertTrue(notification.actions[1].isAuthenticationRequired)
            notification.actions[0].actionIntent.send()
            ShadowLooper.idleMainLooper()
            assertEquals(MainScreenFallbackDecision.RESTART_VIRTUAL, result.get(3, TimeUnit.SECONDS))
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val next = worker.submit<MainScreenFallbackDecision> {
                MainScreenFallbackApproval.request(context, "no-notification", "launch_app", "APP_ALREADY_RUNNING", { false }, { true })
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (MainScreenFallbackApproval.state.value.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            val prompt = MainScreenFallbackApproval.state.value.single()
            MainScreenFallbackApproval.resolve(prompt.token, MainScreenFallbackDecision.TASK_CANCELLED)
            assertEquals(MainScreenFallbackDecision.TASK_CANCELLED, next.get(3, TimeUnit.SECONDS))
        } finally {
            MainScreenFallbackApproval.cancelAll()
            worker.shutdownNow()
        }
    }

    @Test
    fun notificationRequiresAnExplicitAuthenticatedAllowAction() = withWorker { submit ->
        val decision = submit("current-run")
        val notification = waitForNotification()
        assertTrue(notification.actions[0].isAuthenticationRequired)
        assertFalse(
            context.packageManager.getReceiverInfo(
                ComponentName(
                    context,
                    MainScreenFallbackReceiver::class.java
                ), 0
            ).exported
        )
        assertFalse(decision.isDone)
        notification.actions[0].actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertEquals(MainScreenFallbackDecision.ALLOWED, decision.get(3, TimeUnit.SECONDS))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun rejectedAndStaleNotificationsCannotGrantAReplacementRun() = withWorker { submit ->
        val first = submit("old-run")
        val old = waitForNotification()
        old.actions[1].actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertEquals(MainScreenFallbackDecision.DENIED, first.get(3, TimeUnit.SECONDS))
        val next = submit("new-run")
        val current = waitForNotification()
        old.actions[0].actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertFalse(next.isDone)
        current.actions[0].actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertEquals(MainScreenFallbackDecision.ALLOWED, next.get(3, TimeUnit.SECONDS))
    }

    @Test
    fun forgedAndCancelledRequestsDoNotAllowPrimaryOperations() = withWorker { submit ->
        val result = submit("cancelled-run")
        waitForNotification()
        MainScreenFallbackApproval.receive(
            Intent().setAction(MainScreenFallbackApproval.ACTION_ALLOW)
                .putExtra("fallback_request", "unknown-token")
        )
        assertFalse(result.isDone)
        MainScreenFallbackApproval.cancelOwner("cancelled-run")
        assertEquals(MainScreenFallbackDecision.CANCELLED, result.get(3, TimeUnit.SECONDS))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private fun waitForNotification(): Notification {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            shadowOf(manager).allNotifications.firstOrNull()?.let { return it }
            Thread.sleep(10)
        }
        error("approval notification missing")
    }

    private fun withWorker(block: (((String) -> Future<MainScreenFallbackDecision>)) -> Unit) {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val executor = Executors.newSingleThreadExecutor()
        try {
            block { owner ->
                executor.submit<MainScreenFallbackDecision> {
                    MainScreenFallbackApproval.request(
                        context,
                        owner,
                        "launch_app",
                        "DISPLAY_APP_UNSUPPORTED",
                        { false },
                        { true })
                }
            }
        } finally {
            MainScreenFallbackApproval.cancelAll()
            executor.shutdownNow()
        }
    }
}
