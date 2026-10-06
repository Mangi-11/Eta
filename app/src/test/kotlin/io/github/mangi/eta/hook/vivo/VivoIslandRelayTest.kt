package io.github.mangi.eta.hook.vivo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.ui.MainActivity
import java.time.Duration
import org.junit.Assert.*
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
class VivoIslandRelayTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val lifetime = Messenger(Handler(Looper.getMainLooper()))
    private lateinit var notifications: VivoIslandNotifications
    private lateinit var relay: VivoIslandRelay
    private var localActive = false

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications = VivoIslandNotifications(context, AndroidAgentLogger, {}, { true })
        relay = VivoIslandRelay(context, notifications) { localActive }
        manager.notify(1000, Notification.Builder(context).setSmallIcon(R.drawable.ic_notification).build())
    }

    @Test
    fun untrustedAndMissingSenderUidCannotPublishOrClearAnIsland() {
        receive(snapshot(), uid = -1)
        receive(snapshot(), uid = etaUid() + 1)
        assertNull(ownNotification())
        receive(snapshot())
        receive(snapshot(sequence = 2).copy(clear = true), uid = etaUid() + 1)
        assertNotNull(ownNotification())
        assertTrue(manager.activeNotifications.any { it.id == 1000 })
    }

    @Test
    fun outOfOrderUpdatesAndOldClearCannotReplaceCurrentTask() {
        receive(snapshot(sequence = 1))
        receive(snapshot(sequence = 3, token = "new", runId = "new-run"))
        receive(snapshot(sequence = 2).copy(state = VivoIslandNotifications.State.FAILED))
        receive(snapshot(sequence = 4).copy(clear = true))
        assertTrue(ownNotification()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        receive(snapshot(sequence = 5, token = "new", runId = "new-run").copy(clear = true))
        assertNull(ownNotification())
        assertTrue(manager.activeNotifications.any { it.id == 1000 })
    }

    @Test
    fun finishedTaskCannotBeResurrectedByLateProgress() {
        receive(snapshot())
        receive(snapshot(sequence = 2).copy(state = VivoIslandNotifications.State.COMPLETED))
        val done = ownNotification()!!
        receive(snapshot(sequence = 3).copy(progress = VivoIslandWire.Progress(VivoIslandWire.Phase.TOOL, "run_command")))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        assertEquals(done.extras.getCharSequence(Notification.EXTRA_TEXT),
            ownNotification()!!.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(ownNotification()!!.actions.isNullOrEmpty())
    }

    @Test
    fun nativeTakeoverKeepsItsIslandWhenOldEtaProgressArrives() {
        receive(snapshot())
        localActive = true
        notifications.start("vivo-run", "vivo-conversation")
        receive(snapshot(sequence = 2).copy(progress = VivoIslandWire.Progress(VivoIslandWire.Phase.TOOL)))
        receive(snapshot(sequence = 3).copy(clear = true))
        assertTrue(ownNotification()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(VivoHandoff.SOURCE, shadowOf(ownNotification()!!.contentIntent).savedIntent
            .getStringExtra(MainActivity.EXTRA_EXECUTION_SOURCE))
    }

    @Test
    fun replacementRuntimeRetiresOldOwnerAndIgnoresItsLatePackets() {
        receive(snapshot())
        receive(snapshot(sequence = 1, token = "new", runId = "new-run").copy(owner = "replacement"))
        receive(snapshot(sequence = 9).copy(clear = true))
        assertNotNull(ownNotification())
    }

    @Test
    fun mutableActionsAndUnsupportedProtocolAreRejected() {
        val raw = snapshot().toBundle().apply {
            putParcelable("stop", PendingIntent.getBroadcast(context, 0,
                Intent("mutable").setPackage(ModuleConfig.ETA_PACKAGE), PendingIntent.FLAG_MUTABLE))
        }
        assertNull(VivoIslandWire.fromBundle(raw))
        assertNull(VivoIslandWire.fromBundle(snapshot().toBundle().apply { putInt("version", 99) }))
        assertNull(VivoIslandWire.fromBundle(snapshot().toBundle().apply { putString("state", "unknown") }))
        assertNull(VivoIslandWire.fromBundle(snapshot().toBundle().apply { putLong("sequence", 0) }))
    }

    @Test
    fun unknownToolTextCannotBePublishedAndEtaViewActionIsPreserved() {
        val safe = VivoIslandWire.fromBundle(snapshot().toBundle().apply {
            putString("phase", "TOOL")
            putString("tool", "private command or api key")
        })!!
        assertEquals("", safe.progress.tool)
        receive(safe)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        val notification = ownNotification()!!
        assertEquals(safe.open, notification.contentIntent)
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("private"))
        notification.actions.single().actionIntent.send()
        ShadowLooper.idleMainLooper()
        assertEquals("test-stop", shadowOf(context).broadcastIntents.last().action)
    }

    @Test
    fun clientDeathAfterTaskReplacementRemovesOnlyTheCurrentEtaIsland() {
        val binder = object : Binder() {
            var recipient: IBinder.DeathRecipient? = null
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
                if (this.recipient == recipient) this.recipient = null
                return true
            }
        }
        fun send(value: VivoIslandWire.Snapshot) = relay.receive(Message.obtain(null, VivoIslandWire.MSG_RENDER).apply {
            data = value.toBundle()
            sendingUid = etaUid()
            replyTo = Messenger(binder)
        })
        send(snapshot())
        val oldDeath = binder.recipient!!
        send(snapshot(sequence = 2, token = "replacement", runId = "replacement"))
        oldDeath.binderDied()
        ShadowLooper.idleMainLooper()
        assertNotNull(ownNotification())
        binder.recipient!!.binderDied()
        ShadowLooper.idleMainLooper()
        assertNull(ownNotification())
        assertTrue(manager.activeNotifications.any { it.id == 1000 })
    }

    private fun snapshot(sequence: Long = 1, token: String = "token", runId: String = "run") =
        VivoIslandWire.Snapshot(
            "runtime", sequence, token, runId, VivoIslandNotifications.State.RUNNING,
            VivoIslandWire.Progress(VivoIslandWire.Phase.PREPARING),
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .setData(Uri.parse("eta-test://view/$token")), PendingIntent.FLAG_IMMUTABLE),
            PendingIntent.getBroadcast(context, 0, Intent("test-stop").setPackage(ModuleConfig.ETA_PACKAGE)
                .setData(Uri.parse("eta-test://stop/$token")), PendingIntent.FLAG_IMMUTABLE),
        )

    private fun receive(snapshot: VivoIslandWire.Snapshot, uid: Int = etaUid()) = relay.receive(
        Message.obtain(null, VivoIslandWire.MSG_RENDER).apply {
            data = snapshot.toBundle()
            sendingUid = uid
            replyTo = lifetime
        },
    )

    private fun etaUid() = context.packageManager.getPackageUid(ModuleConfig.ETA_PACKAGE, 0)
    private fun ownNotification() = manager.activeNotifications
        .firstOrNull { it.id == VivoIslandNotifications.NOTIFICATION_ID }?.notification
}
