package io.github.mangi.eta.hook.system

import io.github.mangi.eta.hook.support.HookSupport
import io.github.mangi.eta.core.AssistantContextFlags
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

internal object AssistantManager {
    private const val BOOT_COMPLETED_PHASE = 1_000
    private const val SHOW_SOURCE_PUSH_TO_TALK = 1 shl 5
    private const val DEFAULT_SHOW_FLAGS = SHOW_SOURCE_PUSH_TO_TALK
    private const val CONFIG_VERIFY_COOLDOWN_MS = 15_000L
    // Android 37 RoleControllerManager 自身超时为 15 秒；本地 watchdog 只能晚于它做最终状态核验。
    private const val ROLE_OPERATION_WATCHDOG_MS = 17_000L
    private const val RECOVERY_COOLDOWN_MS = 5_000L

    private val systemHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    private data class ConfigurationKey(
        val userId: Int,
        val target: PowerAssistantTarget,
    )

    private val coordinator = AssistantConfigurationCoordinator()

    @Volatile
    private var lastRecoveryUptime = 0L

    @Volatile
    private var lastRecoveryTarget: PowerAssistantTarget? = null

    @Volatile
    private var lastRecoveryUserId = UserHandleHidden.USER_NULL

    @Volatile
    private var lastVerifiedUserId = UserHandleHidden.USER_NULL

    @Volatile
    private var lastVerifiedTarget: PowerAssistantTarget? = null

    @Volatile
    private var lastVerifiedUptime = 0L

    @Volatile
    private var voiceInteractionManagerStub: Any? = null

    @Volatile
    private var systemContext: Context? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.Keys.POWER_KEY_ASSISTANT_TARGET &&
            key != Prefs.Keys.ASSISTANT_AUTO_CONFIG
        ) {
            return@OnSharedPreferenceChangeListener
        }
        val context = systemContext ?: return@OnSharedPreferenceChangeListener
        invalidateVerificationCache()
        schedulePreferenceSelection(
            context = context,
            logger = preferenceLogger ?: return@OnSharedPreferenceChangeListener,
            isRecovery = false,
            rebuildWhenVerified = false,
        )
    }

    @Volatile
    private var preferenceLogger: ModuleLogger? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "AssistantManager")
        val logger = hooks.logger
        preferenceLogger = logger
        if (!Prefs.registerRemoteListener(preferenceListener)) {
            logger.warn("AssistantManager: RemotePreferences 不可用，无法监听助理选择变化")
        }
        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.VOICE_INTERACTION_MANAGER_SERVICE_CLASS
            )
            val onBootPhaseMethod = serviceClass?.let {
                HookSupport.findMethod(it, "onBootPhase", Int::class.javaPrimitiveType!!)
            }
            if (serviceClass == null) {
                hooks.skipped(
                    id = "system.assistant-boot-phase",
                    description = "VoiceInteractionManagerService.onBootPhase",
                    detail = "未找到 VoiceInteractionManagerService，跳过 onBootPhase Hook"
                )
            } else if (onBootPhaseMethod == null) {
                hooks.missing(
                    id = "system.assistant-boot-phase",
                    description = "VoiceInteractionManagerService.onBootPhase",
                    detail = "未找到 VoiceInteractionManagerService.onBootPhase(int)"
                )
            } else {
                hooks.intercept(
                    id = "system.assistant-boot-phase",
                    executable = onBootPhaseMethod,
                    description = "VoiceInteractionManagerService.onBootPhase"
                ) { chain ->
                    val phase = chain.getArg(0) as Int
                    val result = chain.proceed()
                    val service = chain.getThisObject()
                    captureVoiceInteractionManagerStub(service)
                    captureSystemContext(service)
                    if (phase == BOOT_COMPLETED_PHASE) {
                        val context = HookSupport.getFieldValue(service, "mContext") as? Context
                        if (context == null) {
                            logger.warnThrottled("assistant_boot_missing_context") {
                                "AssistantManager: boot completed 时无法取得 mContext"
                            }
                        } else {
                            schedulePreferenceSelection(
                                context = context,
                                logger = logger,
                                userId = null,
                                isRecovery = false,
                                rebuildWhenVerified = false,
                            )
                        }
                    }
                    result
                }
            }

            hookUserLifecycleSelfHeal(hooks, serviceClass, "onUserUnlocking", 1)
            hookUserLifecycleSelfHeal(hooks, serviceClass, "onUserSwitching", 2)
            hookXiaomiAssistantSettingGuard(hooks)
        }
    }

    fun scheduleAssistantRecovery(
        context: Context,
        logger: ModuleLogger,
        handler: Handler,
        forceRefresh: Boolean,
    ): Boolean = scheduleAssistantConfiguration(
        context = context,
        userId = null,
        logger = logger,
        handler = handler,
        isRecovery = forceRefresh,
        rebuildWhenVerified = true,
    )

    fun showAssistantSession(
        context: Context,
        target: PowerAssistantTarget,
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean = false
    ): Boolean {
        val binding = assistantBindingFor(target) ?: return false
        val userId = resolveCurrentUserId()
        if (!hasAssistantSettings(context, userId, binding)) {
            logShowSessionFailure(
                logger,
                "${source}_${target.persistedValue}_not_active",
                logFailures,
            ) { "$source: ${binding.displayName} 尚未成为当前用户的默认助理" }
            return false
        }
        val service = resolveVoiceInteractionService(logger, source, logFailures) ?: return false

        if (isKeyguardLocked(context)) {
            val launchFromKeyguardMethod = service.javaClass.methods.firstOrNull {
                it.name == "launchVoiceAssistFromKeyguard" && it.parameterTypes.isEmpty()
            }
            val supportsLaunchMethod = service.javaClass.methods.firstOrNull {
                it.name == "activeServiceSupportsLaunchFromKeyguard" && it.parameterTypes.isEmpty()
            }
            val supportsLaunch = runCatching {
                supportsLaunchMethod?.invoke(service) as? Boolean ?: false
            }.getOrDefault(false)
            if (supportsLaunch && launchFromKeyguardMethod != null) {
                return runCatching {
                    launchFromKeyguardMethod.invoke(service)
                    logger.debug {
                        "$source: 已通过 voiceinteraction 从锁屏启动 ${binding.displayName}"
                    }
                    true
                }.getOrElse { throwable ->
                    logShowSessionFailure(
                        logger,
                        "${source}_launch_keyguard_failed",
                        logFailures
                    ) {
                        "$source: launchVoiceAssistFromKeyguard 失败，type=${throwable.safeLogType()}"
                    }
                    false
                }
            }
        }

        val showSessionMethod = service.javaClass.methods.firstOrNull {
            it.name == "showSessionForActiveService" && it.parameterTypes.size == 5
        }
        if (showSessionMethod == null) {
            logShowSessionFailure(
                logger,
                "${source}_voice_service_missing_show",
                logFailures
            ) { "$source: voiceinteraction 缺少 showSessionForActiveService" }
            return false
        }

        return runCatching {
            showSessionMethod.invoke(
                service,
                Bundle(),
                DEFAULT_SHOW_FLAGS or if (binding.target == PowerAssistantTarget.ETA) {
                    AssistantContextFlags.forSdk(Build.VERSION.SDK_INT)
                } else 0,
                null,
                null,
                null
            ) as? Boolean ?: false
        }.onFailure { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_voice_service_failed",
                logFailures
            ) {
                "$source: 调用 showSessionForActiveService 失败，type=${throwable.safeLogType()}"
            }
        }.getOrDefault(false).also { shown ->
            if (!shown) {
                logShowSessionFailure(
                    logger,
                    "${source}_voice_service_returned_false",
                    logFailures
                ) { "$source: showSessionForActiveService 返回 false" }
            }
        }
    }

    fun isAssistantConfigured(context: Context, target: PowerAssistantTarget): Boolean {
        val binding = assistantBindingFor(target) ?: return false
        return hasAssistantSettings(context, resolveCurrentUserId(), binding)
    }

    fun rebuildVoiceInteractionImplementation(
        logger: ModuleLogger,
        userId: Int = resolveCurrentUserId(),
        force: Boolean,
        logFailures: Boolean = false
    ): Boolean {
        val stub = voiceInteractionManagerStub ?: run {
            logShowSessionFailure(
                logger,
                "assistant_stub_missing",
                logFailures
            ) { "AssistantManager: mServiceStub 尚未就绪，无法重建 voice interaction 实现" }
            return false
        }
        val initForUserMethod = stub.javaClass.methods.firstOrNull {
            it.name == "initForUser" && it.parameterTypes.size == 1
        }
        val switchImplementationMethod = stub.javaClass.methods.firstOrNull {
            it.name == "switchImplementationIfNeeded" && it.parameterTypes.size == 1
        }
        if (initForUserMethod == null || switchImplementationMethod == null) {
            logShowSessionFailure(
                logger,
                "assistant_stub_methods_missing",
                logFailures
            ) { "AssistantManager: mServiceStub 缺少 initForUser/switchImplementationIfNeeded" }
            return false
        }

        return runCatching {
            initForUserMethod.invoke(stub, userId)
            switchImplementationMethod.invoke(stub, force)
            true
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "assistant_stub_rebuild_failed",
                logFailures
            ) {
                "AssistantManager: 重建 voice interaction 实现失败，type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun scheduleAssistantConfiguration(
        context: Context,
        userId: Int?,
        logger: ModuleLogger,
        handler: Handler,
        isRecovery: Boolean,
        rebuildWhenVerified: Boolean,
    ): Boolean = handler.post {
        try {
            // 请求入队后目标或开关可能变化，真正执行前必须重新读取 RemotePreferences。
            val target = Prefs.powerAssistantTarget()
            if (!shouldConfigureAssistant(
                    autoConfigEnabled = Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG),
                    target = target,
                )
            ) {
                return@post
            }
            val binding = assistantBindingFor(target) ?: return@post
            val resolvedUserId = userId ?: resolveCurrentUserId()
            configureAssistantForUser(
                context = context,
                userId = resolvedUserId,
                binding = binding,
                logger = logger,
                handler = handler,
                isRecovery = isRecovery,
                rebuildWhenVerified = rebuildWhenVerified,
            )
        } catch (exception: Exception) {
            logger.errorThrottled(
                key = "assistant_configuration_task_failed",
                throwable = exception
            ) { "AssistantManager: 默认助理后台任务异常" }
        }
    }

    private fun schedulePreferenceSelection(
        context: Context,
        logger: ModuleLogger,
        userId: Int? = null,
        isRecovery: Boolean = false,
        rebuildWhenVerified: Boolean = true,
    ) {
        val target = Prefs.powerAssistantTarget()
        val autoConfigEnabled = Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)
        when (assistantSelectionAction(autoConfigEnabled, target)) {
            AssistantSelectionAction.RESTORE_OEM -> scheduleOemAssistantRestoration(
                context = context,
                userId = userId,
                logger = logger,
                handler = systemHandler,
            )
            AssistantSelectionAction.CONFIGURE_MANAGED -> scheduleAssistantConfiguration(
                context = context,
                userId = userId,
                logger = logger,
                handler = systemHandler,
                isRecovery = isRecovery,
                rebuildWhenVerified = rebuildWhenVerified,
            )
            AssistantSelectionAction.NONE -> Unit
        }
    }

    private fun scheduleOemAssistantRestoration(
        context: Context,
        userId: Int?,
        logger: ModuleLogger,
        handler: Handler,
    ): Boolean = handler.post {
        if (Prefs.powerAssistantTarget() != PowerAssistantTarget.OEM) return@post
        restoreOemAssistantForUser(
            context = context,
            userId = userId ?: resolveCurrentUserId(),
            logger = logger,
            handler = handler,
        )
    }

    private fun restoreOemAssistantForUser(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        handler: Handler,
    ) {
        if (!beginConfiguration(userId)) {
            logger.debug { "AssistantManager: 已有校正任务，已记录系统默认恢复待追平" }
            return
        }
        var asyncDispatched = false
        try {
            val managedSettings = hasManagedAssistantSettings(context, userId)
            val managedRole = hasManagedAssistantRole(context, userId)
            if (!managedSettings && !managedRole) {
                return
            }

            if (managedSettings) {
                updateSecureString(
                    resolver = context.contentResolver,
                    key = ModuleConfig.SECURE_ASSISTANT,
                    targetValue = null,
                    userId = userId,
                )
                updateSecureString(
                    resolver = context.contentResolver,
                    key = ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                    targetValue = null,
                    userId = userId,
                )
            }

            if (!managedRole) {
                completeOemAssistantRestoration(
                    context = context,
                    userId = userId,
                    logger = logger,
                    roleChanged = false,
                    finishLock = false,
                )
                return
            }
            asyncDispatched = true
            clearAssistantRoleAsync(
                context = context,
                userId = userId,
                logger = logger,
                handler = handler,
                configurationKey = ConfigurationKey(userId, PowerAssistantTarget.OEM),
            ) { roleChanged ->
                completeOemAssistantRestoration(
                    context = context,
                    userId = userId,
                    logger = logger,
                    roleChanged = roleChanged,
                    finishLock = true,
                )
            }
        } catch (exception: Exception) {
            asyncDispatched = false
            logger.warnThrottled("assistant_oem_restoration_failed") {
                "AssistantManager: 恢复系统默认助理失败，type=${exception.safeLogType()}"
            }
        } finally {
            if (!asyncDispatched) {
                finishConfiguration(context, userId, logger)
            }
        }
    }

    private fun completeOemAssistantRestoration(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        roleChanged: Boolean,
        finishLock: Boolean,
    ) {
        try {
            if (Prefs.powerAssistantTarget() != PowerAssistantTarget.OEM) {
                invalidateVerificationCache()
                coordinator.markPendingResync(userId)
                return
            }
            invalidateVerificationCache()
            if (hasManagedAssistantRole(context, userId) ||
                hasManagedAssistantSettings(context, userId)
            ) {
                logger.warnThrottled("assistant_oem_restoration_incomplete") {
                    "AssistantManager: ColorOS 原生助理恢复后仍存在托管绑定"
                }
                return
            }
            if (roleChanged) {
                rebuildVoiceInteractionImplementation(
                    logger = logger,
                    userId = userId,
                    force = false,
                    logFailures = false,
                )
            }
            logger.debug { "AssistantManager: 已恢复 ColorOS 原生助理选择" }
        } finally {
            if (finishLock) {
                finishConfiguration(context, userId, logger)
            }
        }
    }

    private fun configureAssistantForUser(
        context: Context,
        userId: Int,
        binding: AssistantBinding,
        logger: ModuleLogger,
        handler: Handler,
        isRecovery: Boolean,
        rebuildWhenVerified: Boolean,
    ) {
        if (!beginConfiguration(userId)) {
            logger.debug { "AssistantManager: 已有校正任务，已记录 ${binding.displayName} 待追平" }
            return
        }

        var asyncDispatched = false
        try {
            val now = SystemClock.uptimeMillis()
            if (!isRecovery &&
                userId == lastVerifiedUserId &&
                binding.target == lastVerifiedTarget &&
                now - lastVerifiedUptime < CONFIG_VERIFY_COOLDOWN_MS
            ) {
                if (rebuildWhenVerified) {
                    rebuildVoiceInteractionImplementation(
                        logger = logger,
                        userId = userId,
                        force = false,
                        logFailures = false
                    )
                }
                return
            }

            if (isRecovery &&
                userId == lastRecoveryUserId &&
                binding.target == lastRecoveryTarget &&
                now - lastRecoveryUptime < RECOVERY_COOLDOWN_MS
            ) {
                return
            }

            // 先直接写入 Settings.Secure，让系统的 SettingsObserver 立即完成服务切换，不先写 null 触发两次解绑。
            val settingsChangedBeforeRole = updateAssistantSettings(
                context = context,
                userId = userId,
                binding = binding,
                logger = logger,
            )
            val roleOk = hasAssistantRole(context, userId, binding)

            if (roleOk) {
                if (!settingsChangedBeforeRole && isRecovery) {
                    lastRecoveryUptime = now
                    lastRecoveryTarget = binding.target
                    lastRecoveryUserId = userId
                    rebuildVoiceInteractionImplementation(
                        logger = logger,
                        userId = userId,
                        force = true,
                        logFailures = false
                    )
                } else if (!settingsChangedBeforeRole && rebuildWhenVerified) {
                    rebuildVoiceInteractionImplementation(
                        logger = logger,
                        userId = userId,
                        force = false,
                        logFailures = false
                    )
                }
                markVerified(userId, binding.target, SystemClock.uptimeMillis())
                return
            }

            if (isRecovery) {
                lastRecoveryUptime = now
                lastRecoveryTarget = binding.target
                lastRecoveryUserId = userId
            }
            asyncDispatched = true
            addAssistantRoleAsync(
                context = context,
                userId = userId,
                binding = binding,
                logger = logger,
                handler = handler,
            ) { roleChanged ->
                completeAssistantConfiguration(
                    context = context,
                    userId = userId,
                    binding = binding,
                    logger = logger,
                    isRecovery = isRecovery,
                    settingsChangedBeforeRole = settingsChangedBeforeRole,
                    roleChanged = roleChanged,
                )
            }
        } catch (exception: Exception) {
            asyncDispatched = false
            logger.warnThrottled("assistant_configuration_start_failed") {
                "AssistantManager: 启动默认助理校正失败，type=${exception.safeLogType()}"
            }
        } finally {
            if (!asyncDispatched) {
                finishConfiguration(context, userId, logger)
            }
        }
    }

    private fun completeAssistantConfiguration(
        context: Context,
        userId: Int,
        binding: AssistantBinding,
        logger: ModuleLogger,
        isRecovery: Boolean,
        settingsChangedBeforeRole: Boolean,
        roleChanged: Boolean,
    ) {
        try {
            // RoleManager 请求无法取消；回调到达时必须确认目标和开关仍与入队时一致。
            val autoConfigEnabled = Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)
            val currentTarget = Prefs.powerAssistantTarget()
            if (!isAssistantConfigurationCurrent(
                    autoConfigEnabled = autoConfigEnabled,
                    expectedTarget = binding.target,
                    currentTarget = currentTarget,
                )
            ) {
                invalidateVerificationCache()
                if (currentTarget == PowerAssistantTarget.OEM ||
                    shouldConfigureAssistant(autoConfigEnabled, currentTarget)
                ) {
                    coordinator.markPendingResync(userId)
                }
                return
            }
            val settingsChangedAfterRole = updateAssistantSettings(
                context = context,
                userId = userId,
                binding = binding,
                logger = logger
            )
            val targetAfterWrite = Prefs.powerAssistantTarget()
            val autoConfigAfterWrite = Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)
            if (!isAssistantConfigurationCurrent(
                    autoConfigEnabled = autoConfigAfterWrite,
                    expectedTarget = binding.target,
                    currentTarget = targetAfterWrite,
                )
            ) {
                invalidateVerificationCache()
                if (targetAfterWrite == PowerAssistantTarget.OEM ||
                    shouldConfigureAssistant(autoConfigAfterWrite, targetAfterWrite)
                ) {
                    coordinator.markPendingResync(userId)
                }
                return
            }
            val verified = hasAssistantRole(context, userId, binding) &&
                hasAssistantSettings(context, userId, binding)

            if (!verified) {
                invalidateVerificationCache()
                return
            }

            markVerified(userId, binding.target, SystemClock.uptimeMillis())
            if (settingsChangedAfterRole ||
                (isRecovery && !settingsChangedBeforeRole && roleChanged)
            ) {
                rebuildVoiceInteractionImplementation(
                    logger = logger,
                    userId = userId,
                    force = false,
                    logFailures = false
                )
            }
            if (settingsChangedBeforeRole || settingsChangedAfterRole || roleChanged) {
                logger.debug {
                    "AssistantManager: 已切换默认助理到 ${binding.displayName}"
                }
            }
        } catch (exception: Exception) {
            invalidateVerificationCache()
            logger.warnThrottled("assistant_configuration_complete_failed") {
                "AssistantManager: 完成默认助理校正失败，type=${exception.safeLogType()}"
            }
        } finally {
            finishConfiguration(context, userId, logger)
        }
    }

    private fun resolveVoiceInteractionService(
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean
    ): Any? {
        val binder = runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.invoke(null, ModuleConfig.VOICE_INTERACTION_SERVICE) as? IBinder
        }.getOrNull()
        if (binder == null) {
            logShowSessionFailure(
                logger,
                "${source}_voice_service_missing",
                logFailures
            ) { "$source: 无法取得 voiceinteraction binder" }
            return null
        }

        return runCatching {
            val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, binder)
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_voice_service_as_interface_failed",
                logFailures
            ) {
                "$source: 解析 IVoiceInteractionManagerService 失败，type=${throwable.safeLogType()}"
            }
            null
        }
    }

    private fun addAssistantRoleAsync(
        context: Context,
        userId: Int,
        binding: AssistantBinding,
        logger: ModuleLogger,
        handler: Handler,
        onFinished: (Boolean) -> Unit
    ) {
        mutateRoleHoldersAsync(
            context = context,
            userId = userId,
            methodName = "addRoleHolderAsUser",
            baseArgs = arrayOf(
                ModuleConfig.ASSISTANT_ROLE,
                binding.packageName,
                0,
                resolveUserHandle(userId)
            ),
            logger = logger,
            handler = handler,
            configurationKey = ConfigurationKey(userId, binding.target),
            onFinished = onFinished
        )
    }

    private fun clearAssistantRoleAsync(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        handler: Handler,
        configurationKey: ConfigurationKey,
        onFinished: (Boolean) -> Unit,
    ) {
        mutateRoleHoldersAsync(
            context = context,
            userId = userId,
            methodName = "clearRoleHoldersAsUser",
            baseArgs = arrayOf(
                ModuleConfig.ASSISTANT_ROLE,
                0,
                resolveUserHandle(userId),
            ),
            logger = logger,
            handler = handler,
            configurationKey = configurationKey,
            onFinished = onFinished,
        )
    }

    private fun mutateRoleHoldersAsync(
        context: Context,
        userId: Int,
        methodName: String,
        baseArgs: Array<Any>,
        logger: ModuleLogger,
        handler: Handler,
        configurationKey: ConfigurationKey,
        onFinished: (Boolean) -> Unit
    ) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager == null) {
            onFinished(false)
            return
        }
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == baseArgs.size + 2
        } ?: run {
            logger.warnThrottled("assistant_role_method_$methodName") {
                "AssistantManager: RoleManager 缺少 $methodName"
            }
            onFinished(false)
            return
        }

        val completed = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val complete: (Boolean) -> Unit = { success ->
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                try {
                    onFinished(success)
                } catch (exception: Exception) {
                    invalidateVerificationCache()
                    finishConfiguration(context, userId, logger)
                    logger.errorThrottled(
                        key = "assistant_role_completion_${methodName}_$userId",
                        throwable = exception
                    ) { "AssistantManager: $methodName 完成回调异常" }
                }
            }
        }
        timeout = Runnable {
            logger.warnThrottled("assistant_role_timeout_${methodName}_$userId") {
                "AssistantManager: $methodName 回调超过框架超时，核验最终角色状态"
            }
            complete(roleMutationReachedTarget(context, userId, configurationKey.target))
        }
        val executor = Executor { runnable ->
            if (!handler.post(runnable)) {
                logger.warnThrottled("assistant_role_callback_rejected_${methodName}_$userId") {
                    "AssistantManager: $methodName 回调无法投递到系统 Handler"
                }
                runnable.run()
            }
        }
        val callback = Consumer<Boolean> { result ->
            complete(result == true)
        }

        try {
            val args = arrayOfNulls<Any>(baseArgs.size + 2)
            baseArgs.copyInto(args, endIndex = baseArgs.size)
            args[baseArgs.size] = executor
            args[baseArgs.size + 1] = callback
            method.invoke(roleManager, *args)
            if (!handler.postDelayed(timeout, ROLE_OPERATION_WATCHDOG_MS)) {
                logger.warnThrottled("assistant_role_timeout_rejected_${methodName}_$userId") {
                    "AssistantManager: $methodName 超时兜底无法投递到系统 Handler"
                }
                complete(roleMutationReachedTarget(context, userId, configurationKey.target))
            }
        } catch (exception: Exception) {
            logger.warnThrottled("assistant_role_mutation_$methodName") {
                "AssistantManager: $methodName 失败，type=${exception.safeLogType()}"
            }
            complete(false)
        }
    }

    private fun updateAssistantSettings(
        context: Context,
        userId: Int,
        binding: AssistantBinding,
        logger: ModuleLogger
    ): Boolean {
        val resolver = context.contentResolver
        // 与系统 RoleObserver 统一写入 flattenToShortString() 形态，避免短类名与全类名互相覆写。
        val targetComponent = binding.shortComponentName
        val assistantChanged = updateSecureString(
            resolver,
            ModuleConfig.SECURE_ASSISTANT,
            targetComponent,
            userId,
        )
        val serviceChanged = updateSecureString(
            resolver,
            ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
            targetComponent,
            userId,
        )
        val changed = assistantChanged || serviceChanged
        if (changed) {
            logger.debug { "AssistantManager: 已写入 ${binding.displayName} 助理 secure 配置" }
        }
        return changed
    }

    private fun updateSecureString(
        resolver: ContentResolver,
        key: String,
        targetValue: String?,
        userId: Int,
    ): Boolean {
        val currentValue = getSecureStringForUser(resolver, key, userId)
        if (currentValue == targetValue) {
            return false
        }
        return putSecureStringForUser(resolver, key, targetValue, userId)
    }

    private fun hasAssistantRole(
        context: Context,
        userId: Int,
        binding: AssistantBinding,
    ): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == "getRoleHoldersAsUser" && it.parameterTypes.size == 2
        } ?: return false
        val holders = runCatching {
            @Suppress("UNCHECKED_CAST")
            method.invoke(roleManager, ModuleConfig.ASSISTANT_ROLE, resolveUserHandle(userId)) as? List<String>
        }.getOrNull().orEmpty()
        return holders.contains(binding.packageName)
    }

    private fun hasManagedAssistantRole(context: Context, userId: Int): Boolean =
        PowerAssistantTarget.entries.asSequence()
            .mapNotNull(::assistantBindingFor)
            .any { hasAssistantRole(context, userId, it) }

    private fun hasManagedAssistantSettings(context: Context, userId: Int): Boolean {
        val resolver = context.contentResolver
        val assistant = getSecureStringForUser(resolver, ModuleConfig.SECURE_ASSISTANT, userId)
        val voiceInteraction = getSecureStringForUser(
            resolver,
            ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
            userId,
        )
        return PowerAssistantTarget.entries.asSequence()
            .mapNotNull(::assistantBindingFor)
            .any {
                isSameComponentName(it.componentName, assistant) ||
                    isSameComponentName(it.componentName, voiceInteraction)
            }
    }

    private fun roleMutationReachedTarget(
        context: Context,
        userId: Int,
        target: PowerAssistantTarget,
    ): Boolean = assistantBindingFor(target)?.let { binding ->
        hasAssistantRole(context, userId, binding)
    } ?: !hasManagedAssistantRole(context, userId)

    private fun hasAssistantSettings(
        context: Context,
        userId: Int,
        binding: AssistantBinding,
    ): Boolean {
        val resolver = context.contentResolver
        return isSameComponentName(
            getSecureStringForUser(resolver, ModuleConfig.SECURE_ASSISTANT, userId),
            binding.componentName,
        ) && isSameComponentName(
            getSecureStringForUser(resolver, ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE, userId),
            binding.componentName,
        )
    }

    private fun getSecureStringForUser(
        resolver: ContentResolver,
        key: String,
        userId: Int
    ): String? =
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "getStringForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, key, userId) as? String
        }.getOrNull()

    private fun putSecureStringForUser(
        resolver: ContentResolver,
        key: String,
        value: String?,
        userId: Int
    ): Boolean =
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, key, value, userId) as? Boolean ?: false
        }.getOrDefault(false)

    private fun resolveUserHandle(userId: Int): Any =
        runCatching {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val ofMethod = userHandleClass.methods.firstOrNull {
                it.name == "of" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            if (ofMethod != null) {
                return@runCatching ofMethod.invoke(null, userId) ?: error("UserHandle.of 返回 null")
            }
            val constructor = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(userId) ?: error("UserHandle(int) 返回 null")
        }.getOrElse {
            error("无法构造 user=$userId 的 UserHandle")
        }

    private fun resolveCurrentUserId(): Int =
        runCatching {
            val activityManagerClass = Class.forName("android.app.ActivityManager")
            val method = activityManagerClass.getDeclaredMethod("getCurrentUser")
            method.invoke(null) as Int
        }.getOrDefault(0)

    private fun hookUserLifecycleSelfHeal(
        hooks: HookRegistrar,
        serviceClass: Class<*>?,
        methodName: String,
        parameterCount: Int
    ) {
        val logger = hooks.logger
        if (serviceClass == null) {
            hooks.skipped(
                id = "system.assistant-${methodName.removePrefix("on").lowercase()}",
                description = "VoiceInteractionManagerService.$methodName",
                detail = "未找到 VoiceInteractionManagerService，跳过 $methodName Hook"
            )
            return
        }
        val method = HookSupport.findPublicMethod(serviceClass) {
            it.name == methodName && it.parameterTypes.size == parameterCount
        }
        if (method == null) {
            hooks.missing(
                id = "system.assistant-${methodName.removePrefix("on").lowercase()}",
                description = "VoiceInteractionManagerService.$methodName",
                detail = "未找到 VoiceInteractionManagerService.$methodName/$parameterCount"
            )
            return
        }

        hooks.intercept(
            id = "system.assistant-${methodName.removePrefix("on").lowercase()}",
            executable = method,
            description = "VoiceInteractionManagerService.$methodName"
        ) { chain ->
            val targetUserId = when (methodName) {
                "onUserSwitching" -> resolveTargetUserId(chain.getArg(1))
                else -> resolveTargetUserId(chain.getArg(0))
            }
            val result = chain.proceed()
            val service = chain.getThisObject()
            captureVoiceInteractionManagerStub(service)
            captureSystemContext(service)
            val context = HookSupport.getFieldValue(service, "mContext") as? Context
            if (context != null) {
                schedulePreferenceSelection(
                    context = context,
                    userId = targetUserId,
                    logger = logger,
                    isRecovery = false,
                    rebuildWhenVerified = true,
                )
            }
            result
        }
    }

    private fun hookXiaomiAssistantSettingGuard(hooks: HookRegistrar) {
        val logger = hooks.logger
        val methods = Settings.Secure::class.java.declaredMethods.filter { method ->
            if (method.name != "putStringForUser") return@filter false
            val params = method.parameterTypes
            params.size >= 4 &&
                params[0] == ContentResolver::class.java &&
                params[1] == String::class.java &&
                params[2] == String::class.java
        }
        if (methods.isEmpty()) {
            hooks.missing(
                id = "system.assistant-settings-guard",
                description = "Settings.Secure.putStringForUser",
                detail = "未找到 Settings.Secure.putStringForUser，跳过 HyperOS 助理设置保护",
            )
            return
        }
        methods.forEachIndexed { index, method ->
            hooks.intercept(
                id = "system.assistant-settings-guard-$index",
                executable = method,
                description = "Settings.Secure.putStringForUser/${method.parameterTypes.size}",
            ) { chain ->
                val args = chain.args
                val key = args.getOrNull(1) as? String
                val value = args.getOrNull(2) as? String
                val replacement = rewriteManagedAssistantSettingIfOverwritten(
                    autoConfigEnabled = Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG),
                    target = Prefs.powerAssistantTarget(),
                    key = key,
                    value = value,
                )
                if (replacement != null) {
                    val updatedArgs = args.toTypedArray()
                    updatedArgs[2] = replacement
                    logger.debug { "AssistantManager: 已拦截对 $key 的小爱覆写并保持托管目标" }
                    chain.proceed(updatedArgs)
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun resolveTargetUserId(targetUser: Any?): Int? =
        targetUser?.let {
            runCatching {
                val method = it.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "getUserIdentifier" && candidate.parameterTypes.isEmpty()
                } ?: return@runCatching null
                method.invoke(it) as? Int
            }.getOrNull()
        }

    private fun captureVoiceInteractionManagerStub(serviceInstance: Any) {
        val stub = HookSupport.getFieldValue(serviceInstance, "mServiceStub") ?: return
        voiceInteractionManagerStub = stub
    }

    private fun captureSystemContext(serviceInstance: Any) {
        val context = HookSupport.getFieldValue(serviceInstance, "mContext") as? Context ?: return
        systemContext = context
    }

    private fun beginConfiguration(userId: Int): Boolean = coordinator.tryBegin(userId)

    private fun finishConfiguration(context: Context, userId: Int, logger: ModuleLogger) {
        if (coordinator.finish(userId)) {
            schedulePreferenceSelection(
                context = systemContext ?: context,
                logger = logger,
                userId = userId,
                isRecovery = false,
                rebuildWhenVerified = false,
            )
        }
    }

    private fun markVerified(userId: Int, target: PowerAssistantTarget, now: Long) {
        lastVerifiedUserId = userId
        lastVerifiedTarget = target
        lastVerifiedUptime = now
    }

    private fun invalidateVerificationCache() {
        lastVerifiedUserId = UserHandleHidden.USER_NULL
        lastVerifiedTarget = null
        lastVerifiedUptime = 0L
    }

    private fun logShowSessionFailure(
        logger: ModuleLogger,
        key: String,
        logFailures: Boolean,
        message: () -> String
    ) {
        if (!logFailures) return
        logger.warnThrottled(key, message = message)
    }

    private fun isKeyguardLocked(context: Context): Boolean =
        runCatching {
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            keyguardManager?.isKeyguardLocked == true
        }.getOrDefault(false)

    private object UserHandleHidden {
        const val USER_NULL = -10_000
    }
}
