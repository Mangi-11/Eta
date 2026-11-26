package io.github.mangi.eta.ui.app

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.mangi.eta.ui.model.PermissionStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class LocalNetworkPermissionTest {
    @Test
    @Config(sdk = [33, 36])
    fun olderDevicesDoNotCheckOrDisplayTheNewPermission() {
        val context = PermissionContext(granted = false)

        assertEquals(LocalNetworkPermission.AccessState.NOT_REQUIRED, LocalNetworkPermission.accessState(context))
        assertNull(localNetworkPermissionHealthItem(context))
        assertEquals(emptyList<String>(), context.checkedPermissions)
    }

    @Test
    fun missingPermissionRemainsVisibleAndOffersAuthorization() {
        val context = PermissionContext(granted = false)

        assertEquals(LocalNetworkPermission.AccessState.DENIED, LocalNetworkPermission.accessState(context))
        val item = requireNotNull(localNetworkPermissionHealthItem(context))
        assertEquals(LOCAL_NETWORK_PERMISSION_ITEM_ID, item.id)
        assertEquals(PermissionStatusUi.Missing, item.status)
        assertNotNull(item.primaryActionLabel)
        assertEquals(setOf(Manifest.permission.ACCESS_LOCAL_NETWORK), context.checkedPermissions.toSet())
    }

    @Test
    fun permissionHealthRefreshReflectsGrantAndRevocationWithoutCaching() {
        val context = PermissionContext(granted = false)
        assertEquals(PermissionStatusUi.Missing, localNetworkPermissionHealthItem(context)?.status)

        context.granted = true
        assertEquals(LocalNetworkPermission.AccessState.GRANTED, LocalNetworkPermission.accessState(context))
        assertEquals(PermissionStatusUi.Available, localNetworkPermissionHealthItem(context)?.status)

        context.granted = false
        assertEquals(LocalNetworkPermission.AccessState.DENIED, LocalNetworkPermission.accessState(context))
        assertEquals(PermissionStatusUi.Missing, localNetworkPermissionHealthItem(context)?.status)
    }

    private class PermissionContext(var granted: Boolean) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val checkedPermissions = mutableListOf<String>()

        override fun checkSelfPermission(permission: String): Int {
            checkedPermissions += permission
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }
}
