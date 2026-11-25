package io.github.mangi.eta.agent.tool.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Display
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.core.AgentLogger
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 负责应用检索、应用启动（含虚拟屏/主屏路由与窗口确认）、URI 跳转以及将虚拟屏应用移交主屏展示。
 */
internal class AppLaunchTools(
    private val logger: AgentLogger,
    private val requireContext: () -> Context,
    private val rootAvailable: () -> Boolean,
    private val virtualDisplayEnabled: () -> Boolean,
    private val lsposedAvailable: () -> Boolean,
    private val activeDisplayId: () -> Int,
    private val setActiveDisplayId: (Int) -> Unit,
    private val checkCancellation: () -> Unit,
) {

    fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }
        val requestedDisplay = CanvasRoutingPolicy.RequestedDisplay.parse(
            args.optString("display", "auto"),
        ) ?: return errorResult("INVALID_ARGUMENT", "display 仅支持 auto、virtual 或 main")

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName",
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}",
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        if (virtualDisplayEnabled() && requestedDisplay != CanvasRoutingPolicy.RequestedDisplay.MAIN && !rootAvailable()) {
            RootAccess.awaitOrRetryIfPreviouslyGranted(context)
        }
        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val launchPlan = CanvasRoutingPolicy.decideLaunchPlan(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = requestedDisplay,
            targetPackage = app.packageName,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )

        val launchedVirtualId = if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.launchAppOnVirtualDisplay(
                context = context,
                packageName = app.packageName,
                appName = app.appName,
                launchIntent = launchIntent,
                rootAvailable = rootAvailable,
                checkCancellation = checkCancellation,
            )
        } else {
            null
        }

        val finalDisplayId = if (launchedVirtualId != null && CanvasRoutingPolicy.isVirtualDisplay(launchedVirtualId)) {
            setActiveDisplayId(launchedVirtualId)
            launchedVirtualId
        } else {
            checkCancellation()
            setActiveDisplayId(Display.DEFAULT_DISPLAY)
            context.startActivity(launchIntent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        val canvasReason = if (finalCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName) {
            launchPlan.reason
        } else if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            "virtual_create_failed"
        } else {
            launchPlan.reason
        }

        val verification = AppLaunchVerification.awaitWindow(
            packageName = app.packageName,
            displayId = finalDisplayId,
            query = { remainingMillis ->
                AgentAccessibilityService.current()?.observeNodeSnapshot(
                    maxNodes = 1,
                    displayId = finalDisplayId,
                    includeNodes = false,
                    timeoutMillis = remainingMillis,
                ) ?: AgentAccessibilityService.NodeSnapshotObservation(
                    status = AgentAccessibilityService.NodeSnapshotStatus.SERVICE_UNAVAILABLE,
                    snapshot = null,
                    window = null,
                )
            },
            checkCancellation = checkCancellation,
        )
        logger.info(
            "Agent local tool action=launch_app outcome=${verification.launchStatus} " +
                "canvas=$finalCanvas reason=$canvasReason display=$finalDisplayId " +
                "root=${RootAccess.state.value.status.name.lowercase()} " +
                "lsposed=${lsposedAvailable()}",
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .put("canvas", finalCanvas)
            .put("canvas_reason", canvasReason)
            .put("display_id", finalDisplayId)
            .put("launch_status", verification.launchStatus)
            .put("window_observation", verification.toJson())
            .apply {
                if (!verification.confirmed) {
                    put("note", "启动请求已提交，但尚未确认目标窗口；请先观察当前画布，不要直接重放启动或点击")
                }
            }
            .toString()
    }

    fun presentToUser(args: JSONObject): String {
        val context = requireContext()
        val reason = args.optString("reason").trim()
        val vdSnapshot = VirtualDisplayController.snapshots.value
        val wasVirtual = vdSnapshot.active || CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId())
        val presented = if (wasVirtual) {
            VirtualDisplayController.handoffToMainDisplay(context, rootAvailable)
        } else {
            true
        }
        setActiveDisplayId(Display.DEFAULT_DISPLAY)
        return JSONObject()
            .put("ok", true)
            .put("tool", "present_to_user")
            .put("canvas", CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName)
            .put("display_id", Display.DEFAULT_DISPLAY)
            .put("presented", presented)
            .apply {
                if (vdSnapshot.appName.isNotBlank()) put("app_name", vdSnapshot.appName)
                if (vdSnapshot.packageName.isNotBlank()) put("package_name", vdSnapshot.packageName)
                if (reason.isNotBlank()) put("reason", reason)
            }
            .toString()
    }

    fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvedActivity = context.packageManager.resolveActivity(intent, 0)
            ?: return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        if (virtualDisplayEnabled() && !rootAvailable()) {
            RootAccess.awaitOrRetryIfPreviouslyGranted(context)
        }
        val resolvedPkg = resolvedActivity.activityInfo?.packageName
        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val launchPlan = CanvasRoutingPolicy.decideLaunchPlan(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = CanvasRoutingPolicy.RequestedDisplay.AUTO,
            targetPackage = resolvedPkg,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )
        val virtualId = if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.openUriOnVirtualDisplay(
                context = context,
                intent = intent,
                targetLabel = uri.host ?: uri.scheme.orEmpty(),
                rootAvailable = rootAvailable,
                checkCancellation = checkCancellation,
            )
        } else {
            null
        }
        val finalDisplayId = if (virtualId != null && CanvasRoutingPolicy.isVirtualDisplay(virtualId)) {
            setActiveDisplayId(virtualId)
            virtualId
        } else {
            checkCancellation()
            setActiveDisplayId(Display.DEFAULT_DISPLAY)
            context.startActivity(intent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        val canvasReason = if (finalCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName) {
            launchPlan.reason
        } else if (launchPlan.target == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            "virtual_create_failed"
        } else {
            launchPlan.reason
        }
        logger.info("Agent local tool action=open_uri outcome=started canvas=$finalCanvas reason=$canvasReason")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .put("canvas", finalCanvas)
            .put("canvas_reason", canvasReason)
            .put("display_id", finalDisplayId)
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L),
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                ),
            )
        }
        return apps.values.toList()
    }

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp),
                )
            }
        }

    private fun String.normalized(): String = trim().lowercase(Locale.ROOT)

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false,
    )
}
