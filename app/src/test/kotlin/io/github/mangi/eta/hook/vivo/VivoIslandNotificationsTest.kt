package io.github.mangi.eta.hook.vivo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.ui.MainActivity
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VivoIslandNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val stopped = mutableListOf<String>()
    private lateinit var bridge: VivoIslandNotifications
    private var sceneAvailable = true

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        bridge = VivoIslandNotifications(context, AndroidAgentLogger, stopped::add, { sceneAvailable })
        manager.notify(1000, Notification.Builder(context).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Native PhoneGPT task").build())
    }

    @Test
    fun islandUsesIndependentNotificationIdentityAndKeepsNativeTask() {
        bridge.start("run-1", "conversation-1")
        val notification = ownNotification()
        assertNativeTaskExists()
        assertEquals("FAST_COMMAND", notification.extras.getString("notification.superx.scene"))
        assertEquals(0, notification.extras.getInt("notification.superx.operation"))
        assertEquals(0x111, notification.extras.getInt("notification.superx.displays"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.extras.getBundle("notification.superx.capsule"))
        val island = notification.extras.getBundle("notification.superx.island")!!
        assertEquals(6, island.getInt("island.superx.rightTemplate"))
        assertNotNull(island.getBundle("island.superx.rightInfo")!!.getParcelable(
            "island.superx.rightInfo.clickResp", android.app.PendingIntent::class.java,
        ))
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.ACTION_VIEW_EXECUTION, open.action)
        assertEquals(ModuleConfig.ETA_PACKAGE, open.component!!.packageName)
        assertEquals(VivoHandoff.SOURCE, open.getStringExtra(MainActivity.EXTRA_EXECUTION_SOURCE))
        assertEquals("conversation-1", open.getStringExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY))
        assertTrue(notification.contentIntent.isImmutable)
    }

    @Test
    fun progressIsCoalescedAndNeverDisplaysToolArgumentsOrCommands() {
        bridge.start("run", "private-session")
        val initial = displayedText(ownNotification())
        bridge.update("run", AgentEvent.ToolStarted(1, "call", "run_command", "private-api-key", "private-command"))
        assertEquals(initial, displayedText(ownNotification()))
        advance(650)
        val notification = ownNotification()
        assertEquals(1, notification.extras.getInt("notification.superx.operation"))
        val shown = displayedText(notification)
        assertFalse(shown.contains("private-api-key"))
        assertFalse(shown.contains("private-command"))
        assertFalse(shown.contains("private-session"))
        assertFalse(shown == initial)
        assertNativeTaskExists()
    }

    @Test
    fun staleAndForgedStopIntentsCannotStopReplacementTask() {
        bridge.start("old-run", "old-session")
        val oldStop = ownNotification().actions.single().actionIntent
        bridge.start("new-run", "new-session")
        val current = ownNotification()
        oldStop.send()
        ShadowLooper.idleMainLooper()
        val forged = Intent(shadowOf(current.actions.single().actionIntent).savedIntent)
            .putExtra("eta_vivo_notification_token", "forged-token")
        context.sendBroadcast(forged)
        ShadowLooper.idleMainLooper()
        assertTrue(stopped.isEmpty())
        current.actions.single().actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("new-run"), stopped)
        bridge.finish("new-run", VivoIslandNotifications.State.COMPLETED)
        current.actions.single().actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("new-run"), stopped)
    }

    @Test
    fun terminalStateIgnoresLateEventsAndExpiresOnlyItsOwnNotification() {
        bridge.start("run", "session")
        bridge.update("run", AgentEvent.ToolStarted(1, "call", "observe_screen", ""))
        bridge.finish("run", VivoIslandNotifications.State.COMPLETED)
        val done = ownNotification()
        assertFalse(done.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(done.actions.isNullOrEmpty())
        bridge.update("run", AgentEvent.ProviderRequestStarted(2))
        advance(650)
        assertEquals(displayedText(done), displayedText(ownNotification()))
        advance(30_000)
        assertFalse(hasOwnNotification())
        assertNativeTaskExists()
    }

    @Test
    fun lateCompletionAndExpiryCannotClearReplacementTask() {
        bridge.start("old", "session")
        bridge.finish("old", VivoIslandNotifications.State.CANCELLED)
        advance(1000)
        bridge.start("new", "session")
        bridge.finish("old", VivoIslandNotifications.State.FAILED)
        advance(5000)
        assertTrue(ownNotification().flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNativeTaskExists()
    }

    @Test
    fun dismissedTaskIsNotRepublishedByLaterProgressOrCompletion() {
        bridge.start("run", "session")
        ownNotification().deleteIntent.send()
        ShadowLooper.idleMainLooper()
        bridge.update("run", AgentEvent.ProviderRequestStarted(1))
        bridge.finish("run", VivoIslandNotifications.State.FAILED)
        advance(650)
        assertFalse(hasOwnNotification())
        assertTrue(stopped.isEmpty())
        assertNativeTaskExists()
    }

    @Test
    fun unavailableOrRevokedSceneLeavesExistingNotificationsUntouched() {
        sceneAvailable = false
        bridge.start("unavailable", "session")
        assertFalse(hasOwnNotification())
        sceneAvailable = true
        bridge.start("available", "session")
        assertTrue(hasOwnNotification())
        sceneAvailable = false
        bridge.update("available", AgentEvent.ProviderRequestStarted(1))
        advance(650)
        assertFalse(hasOwnNotification())
        assertNativeTaskExists()
    }

    @Test
    fun clearingTaskCancelsOemRetaggedNotificationWithoutTouchingNativeTask() {
        bridge.start("run", "session")
        val notification = ownNotification()
        manager.cancel(VivoIslandNotifications.TAG, VivoIslandNotifications.NOTIFICATION_ID)
        manager.notify("VIVO_SUPERX_TAG", VivoIslandNotifications.NOTIFICATION_ID, notification)
        bridge.clear("run")
        assertFalse(manager.activeNotifications.any { it.id == VivoIslandNotifications.NOTIFICATION_ID })
        assertNativeTaskExists()
    }

    @Test
    fun mutedNativeChannelPreventsIslandPublication() {
        manager.createNotificationChannel(NotificationChannel(
            "SI_NOTIFICATION_CHANNEL_DEFAULT", "Native task channel", NotificationManager.IMPORTANCE_NONE,
        ))
        bridge.start("run", "session")
        assertFalse(hasOwnNotification())
        assertNativeTaskExists()
    }

    @Test
    fun revokedNotificationPermissionClearsOnlyEtaIsland() {
        bridge.start("run", "session")
        assertTrue(hasOwnNotification())
        shadowOf(manager).setNotificationsEnabled(false)
        bridge.update("run", AgentEvent.ProviderRequestStarted(1))
        advance(650)
        assertFalse(hasOwnNotification())
        assertNativeTaskExists()
    }

    private fun ownNotification(): Notification = manager.activeNotifications
        .single { it.tag == VivoIslandNotifications.TAG && it.id == VivoIslandNotifications.NOTIFICATION_ID }.notification

    private fun hasOwnNotification(): Boolean = manager.activeNotifications.any { it.tag == VivoIslandNotifications.TAG }

    private fun assertNativeTaskExists() = assertTrue(manager.activeNotifications.any {
        it.id == 1000 && it.tag == null && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Native PhoneGPT task"
    })

    private fun displayedText(notification: Notification): String {
        val base = notification.extras.getBundle("notification.superx.baseInfos")!!
        return base.getCharSequence("notification.superx.baseInfos.title").toString() + " " +
            base.getCharSequence("notification.superx.baseInfos.content").toString()
    }

    private fun advance(milliseconds: Long) = shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
}
