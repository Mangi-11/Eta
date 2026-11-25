package io.github.mangi.eta.agent.tool.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.mangi.eta.agent.device.DeviceToolContract
import org.json.JSONArray
import org.json.JSONObject

/** 包详情通过当前用户的 PackageManager 读取，权限列表单独分页。 */
internal class AppInspectionTool(private val context: Context) {
    fun inspect(args: JSONObject): JSONObject {
        val userId = DeviceToolContract.appUserId(context)
        DeviceToolContract.userScopeError(args, userId)?.let { return it }
        val packageName = args.optString("package_name")
        if (!DeviceToolContract.validPackageName(packageName)) {
            return DeviceToolContract.failure("INVALID_PACKAGE", "包名格式无效")
        }
        val offset = args.optInt("permission_offset", 0)
        val limit = args.optInt("permission_limit", 100)
        if (offset < 0 || limit !in 1..200) {
            return DeviceToolContract.failure("INVALID_ARGUMENT", "权限偏移必须非负，permission_limit 必须在 1 到 200 之间")
        }
        val manager = context.packageManager
        val info = try {
            manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            ))
        } catch (_: PackageManager.NameNotFoundException) {
            val allVisible = context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
            return DeviceToolContract.failure(
                if (allVisible) "APP_NOT_INSTALLED" else "APP_NOT_FOUND_OR_NOT_VISIBLE",
                if (allVisible) "此 Android 用户中未安装指定应用" else "当前用户未安装此应用，或 Android 包可见性限制阻止了查询",
            ).put("user_id", userId)
        } catch (_: SecurityException) {
            return DeviceToolContract.failure("APP_INSPECTION_DENIED", "系统拒绝读取此应用详情").put("user_id", userId)
        }
        val application = info.applicationInfo
            ?: return DeviceToolContract.failure("APP_INFO_UNAVAILABLE", "系统未返回应用信息").put("user_id", userId)
        val requested = info.requestedPermissions.orEmpty()
        val end = (offset.toLong() + limit).coerceAtMost(requested.size.toLong()).toInt()
        val permissions = JSONArray()
        for (index in offset.coerceAtMost(requested.size) until end) {
            permissions.put(JSONObject()
                .put("name", requested[index].take(256))
                .put("name_truncated", requested[index].length > 256)
                .put("granted", (info.requestedPermissionsFlags?.getOrNull(index) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0))
        }
        val splitPaths = application.splitSourceDirs.orEmpty()
        return JSONObject()
            .put("ok", true).put("tool", "inspect_app").put("source", "package_manager")
            .put("scope", "app_user").put("user_id", userId).put("package_name", packageName)
            .put("version_name", info.versionName?.take(256) ?: JSONObject.NULL)
            .put("version_name_truncated", (info.versionName?.length ?: 0) > 256).put("version_code", info.longVersionCode)
            .put("uid", application.uid).put("enabled", application.enabled)
            .put("stopped", application.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0)
            .put("system_app", application.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            .put("source_path", application.sourceDir ?: JSONObject.NULL)
            .put("split_source_paths", JSONArray(splitPaths.take(32)))
            .put("split_paths_truncated", splitPaths.size > 32)
            .put("first_install_time_ms", info.firstInstallTime).put("last_update_time_ms", info.lastUpdateTime)
            .put("install_source", installSource(manager, packageName))
            .put("permissions", permissions).put("permission_total", requested.size)
            .put("permission_offset", offset).put("permission_count", permissions.length())
            .put("has_more", end < requested.size)
            .put("next_permission_offset", if (end < requested.size) end else JSONObject.NULL)
    }

    private fun installSource(manager: PackageManager, packageName: String): JSONObject = try {
        val source = manager.getInstallSourceInfo(packageName)
        JSONObject().put("available", true)
            .put("installing_package", source.installingPackageName ?: JSONObject.NULL)
            .put("initiating_package", source.initiatingPackageName ?: JSONObject.NULL)
            .put("originating_package", source.originatingPackageName ?: JSONObject.NULL)
    } catch (_: PackageManager.NameNotFoundException) {
        JSONObject().put("available", false).put("code", "INSTALL_SOURCE_UNAVAILABLE")
    } catch (_: SecurityException) {
        JSONObject().put("available", false).put("code", "INSTALL_SOURCE_ACCESS_DENIED")
    }
}
