package io.github.mangi.eta.agent.display

import android.app.Application
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VirtualScreenNotificationTest {
    @Test
    fun notificationOpensViewerAndOldCleanupCannotRemoveNewSessionNotification() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        VirtualScreenNotification.show(context, "first")
        val notification = manager.activeNotifications.single().notification
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(VirtualScreenViewerActivity::class.java.name, intent.component!!.className)
        assertEquals("eta-virtual-screen://view/first", intent.dataString)
        assertTrue(notification.contentIntent.isImmutable)
        VirtualScreenNotification.cancel(context, "first")
        VirtualScreenNotification.show(context, "second")
        VirtualScreenNotification.cancel(context, "first")
        assertEquals("second", manager.activeNotifications.single().tag)
        VirtualScreenNotification.cancel(context, "second")
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
