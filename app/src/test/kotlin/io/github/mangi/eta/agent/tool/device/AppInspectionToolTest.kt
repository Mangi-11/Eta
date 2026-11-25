package io.github.mangi.eta.agent.tool.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppInspectionToolTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun inspectsPackageWithoutLauncherAndPaginatesPermissions() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "example.service"
            versionName = "1.2"
            longVersionCode = 12L
            applicationInfo = ApplicationInfo().apply {
                packageName = "example.service"
                uid = context.applicationInfo.uid + 1
                enabled = true
                sourceDir = "/data/app/example/base.apk"
            }
            requestedPermissions = arrayOf("example.ONE", "example.TWO", "example.THREE")
            requestedPermissionsFlags = intArrayOf(0, PackageInfo.REQUESTED_PERMISSION_GRANTED, 0)
        })
        val tool = AppInspectionTool(context)
        val result = tool.inspect(JSONObject().put("package_name", "example.service")
            .put("permission_offset", 1).put("permission_limit", 1))
        assertTrue(result.getBoolean("ok"))
        assertEquals("1.2", result.getString("version_name"))
        assertEquals(12L, result.getLong("version_code"))
        assertEquals(3, result.getInt("permission_total"))
        assertEquals(1, result.getInt("permission_count"))
        assertEquals("example.TWO", result.getJSONArray("permissions").getJSONObject(0).getString("name"))
        assertTrue(result.getBoolean("has_more"))
        assertEquals(2, result.getInt("next_permission_offset"))
        val tail = tool.inspect(JSONObject().put("package_name", "example.service").put("permission_offset", 3))
        assertFalse(tail.getBoolean("has_more"))
        assertEquals(0, tail.getInt("permission_count"))
    }

    @Test
    fun missingPackageHasExplicitScopeAndNoInventedDetails() {
        val result = AppInspectionTool(context).inspect(JSONObject().put("package_name", "example.missing"))
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("code") in setOf("APP_NOT_INSTALLED", "APP_NOT_FOUND_OR_NOT_VISIBLE"))
        assertEquals(DeviceToolContract.appUserId(context), result.getInt("user_id"))
    }

    @Test
    fun rejectsCrossProfileAndOutOfBoundsPermissionQueries() {
        val tool = AppInspectionTool(context)
        val result = tool.inspect(JSONObject().put("package_name", "example.service")
            .put("user_id", DeviceToolContract.appUserId(context) + 1))
        assertEquals("USER_SCOPE_UNSUPPORTED", result.getString("code"))
        val invalid = tool.inspect(JSONObject().put("package_name", "example.service").put("permission_limit", 201))
        assertEquals("INVALID_ARGUMENT", invalid.getString("code"))
    }
}
