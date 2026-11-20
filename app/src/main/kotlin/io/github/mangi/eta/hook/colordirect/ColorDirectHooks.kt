package io.github.mangi.eta.hook.colordirect

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.DexKitTargets
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.system.CircleToSearchInvoker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Parcelable
import android.os.SystemClock
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

internal object ColorDirectHooks {
    private const val SOURCE = "ColorDirectActivity"
    private const val DIRECT_EXT_FINGER_TRIGGER = "fingerTrigger"
    private const val DIRECT_EXT_TOUCH_INFO = "touchInfo"
    private const val DIRECT_EXT_FINGER_COUNT = "fingerCount"

    @Volatile
    private var lastHandledUptime = 0L

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ColorDirect")
        return hooks.install {
            hookCollectInfoActivity(
                hooks, classLoader, applicationInfo, module.moduleApplicationInfo,
            )
        }
    }

    private fun hookCollectInfoActivity(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
        moduleApplicationInfo: ApplicationInfo,
    ) {
        val logger = hooks.logger
        val activityClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_COLLECT_ACTIVITY_CLASS
        )
        if (activityClass == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity Intent 处理入口",
                detail = "未找到 CollectInfoActivity，无法接管双指识屏 Activity 入口"
            )
            return
        }
        val startInfoClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_START_INFO_CLASS
        )

        val method = DexKitTargets(
            apkPath = applicationInfo.sourceDir,
            classLoader = classLoader,
            logger = logger,
            cacheDirectory = applicationInfo.dataDir?.let { File(it, "cache/eta-dexkit") },
            moduleNativeLibraryDirectory = moduleApplicationInfo.nativeLibraryDir,
            moduleApkPath = moduleApplicationInfo.sourceDir,
        ).use { targets ->
            ColorDirectTargets.findCollectIntent(targets, activityClass)
        }
        if (method == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity Intent 处理入口",
                detail = "未唯一定位 CollectInfoActivity Intent 处理方法，保留系统双指识屏"
            )
            return
        }

        hooks.intercept(
            id = "colordirect.collect-info",
            executable = method,
            description = "CollectInfoActivity Intent 处理入口"
        ) { chain ->
            // 开关关闭则走原双指识屏逻辑。
            if (!Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val activity = chain.getThisObject() as? Activity
            val intent = chain.getArg(0) as? Intent
            if (activity == null || !isDoubleFingerCollectIntent(intent, startInfoClass)) {
                return@intercept chain.proceed()
            }

            if (tryStartCircleToSearch(activity, logger)) {
                finishColorDirectActivity(activity)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun tryStartCircleToSearch(context: Context, logger: ModuleLogger): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$SOURCE: 命中去重窗口，吞掉重复双指识屏" }
            return true
        }

        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "回退小布双指识屏")) {
            return false
        }

        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE 双指识屏")) {
            return false
        }

        lastHandledUptime = now
        logger.debug { "$SOURCE: 命中双指识屏，已转发 Circle to Search" }
        return true
    }

    private fun finishColorDirectActivity(activity: Activity) {
        activity.finishAndRemoveTask()
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun isDoubleFingerCollectIntent(intent: Intent?, startInfoClass: Class<*>?): Boolean {
        val directExt = resolveDirectExt(intent, startInfoClass) ?: return false
        return runCatching {
            val json = JSONObject(directExt)
            json.optBoolean(DIRECT_EXT_FINGER_TRIGGER, false) &&
                json.optJSONObject(DIRECT_EXT_TOUCH_INFO)
                    ?.optInt(DIRECT_EXT_FINGER_COUNT, 0) == ModuleConfig.COLOR_DIRECT_DOUBLE_FINGER_COUNT
        }.getOrDefault(false)
    }

    private fun resolveDirectExt(intent: Intent?, startInfoClass: Class<*>?): String? {
        if (intent == null) return null
        intent.getStringExtra(ModuleConfig.COLOR_DIRECT_EXTRA_DIRECT_EXT)?.let { return it }
        val startInfo = resolveStartInfo(intent, startInfoClass) ?: return null
        return HookSupport.invokeNoArgs(startInfo, "getDirectExt") as? String
    }

    @Suppress("DEPRECATION")
    private fun resolveStartInfo(intent: Intent, startInfoClass: Class<*>?): Any? {
        if (startInfoClass != null && Parcelable::class.java.isAssignableFrom(startInfoClass)) {
            @Suppress("UNCHECKED_CAST")
            val typedStartInfoClass = startInfoClass as Class<Parcelable>
            intent.getParcelableExtra(
                ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO,
                typedStartInfoClass
            )?.let { return it }
        }

        return runCatching {
            intent.extras?.get(ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO)
        }.getOrNull()
    }
}
