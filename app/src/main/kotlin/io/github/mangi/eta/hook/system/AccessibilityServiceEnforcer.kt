package io.github.mangi.eta.hook.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionProtocol
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在 system_server 存活期间按用户选择保持 Eta 无障碍服务可用。
 *
 * 保护默认关闭；开启后只维护 owner 用户中的 Eta 组件与总开关，始终保留其他服务。
 * 所有工作复用 Android 的 BackgroundThread，不创建额外线程，也不做周期轮询。
 */
internal class AccessibilityServiceEnforcer(
    private val handler: Handler,
    private val logger: ModuleLogger,
) {
    private val started = AtomicBoolean()
    private val workScheduled = AtomicBoolean()
    private val rerunRequested = AtomicBoolean()
    private val registrationRetryScheduled = AtomicBoolean()
    private val healthCheckScheduled = AtomicBoolean()
    private val runtimeRecoveryScheduled = AtomicBoolean()
    private val repairInProgress = AtomicBoolean()
    private val restoreBackoff = AccessibilityRestoreBackoff()
    private val repairLimiter = AccessibilityRepairLimiter()
    private val activeSettingUris = mutableSetOf<Uri>()

    private var controlSettingObserver: ContentObserver? = null
    private var activeSettingsObserver: ContentObserver? = null
    private var controlReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var lifecycleReceiver: BroadcastReceiver? = null
    private var controlSettingObserverRegistered = false
    private var controlReceiverRegistered = false
    private var packageReceiverRegistered = false
    private var lifecycleReceiverRegistered = false
    private var registrationRetryIndex = 0

    @Volatile
    private var lastRestoreLogAt = 0L

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (!started.compareAndSet(false, true)) return
        if (!schedule(context, reason = "system_ready", delayMs = 0L)) {
            started.set(false)
        }
    }

    private fun reconcile(context: Context, reason: String) {
        val enabled = isEnforcementEnabled(context)
        ensureObserversRegistered(context, enabled)
        if (!enabled) {
            restoreBackoff.reset()
            repairLimiter.reset()
            return
        }
        if (repairInProgress.get()) return
        enforce(context, reason)
        if (
            shouldScheduleAccessibilityHealthCheck(
                ownerUnlocked = isOwnerUnlocked(context),
                serviceConfigured = isExpectedServiceConfigured(context),
            )
        ) {
            scheduleHealthCheck(
                context = context,
                reason = reason,
                delayMs = SERVICE_REBIND_GRACE_MS,
            )
        }
    }

    private fun ensureObserversRegistered(
        context: Context,
        enforcementEnabled: Boolean,
    ) {
        ensureControlChannelRegistered(context)
        if (enforcementEnabled) {
            ensureActiveObserversRegistered(context)
        } else {
            unregisterActiveObservers(context)
        }

        val controlReady =
            controlSettingObserverRegistered && controlReceiverRegistered
        val activeReady = if (enforcementEnabled) {
            activeSettingUris.size == ACTIVE_SETTING_URIS.size &&
                packageReceiverRegistered &&
                lifecycleReceiverRegistered
        } else {
            activeSettingUris.isEmpty() &&
                !packageReceiverRegistered &&
                !lifecycleReceiverRegistered
        }
        if (controlReady && activeReady) {
            registrationRetryIndex = 0
        } else {
            scheduleRegistrationRetry(context)
        }
    }

    private fun ensureControlChannelRegistered(context: Context) {
        val observer = controlSettingObserver ?: object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                schedule(context, "control_setting_changed", delayMs = 0L)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                schedule(context, "control_setting_changed", delayMs = 0L)
            }
        }.also { controlSettingObserver = it }

        if (!controlSettingObserverRegistered) {
            try {
                context.contentResolver.registerContentObserver(
                    CONTROL_SETTING_URI,
                    false,
                    observer,
                )
                controlSettingObserverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法监听无障碍保护开关", failure)
            }
        }

        if (!controlReceiverRegistered) {
            val receiver = controlReceiver ?: createControlReceiver().also {
                controlReceiver = it
            }
            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(AccessibilityProtectionProtocol.ACTION_SET)
                        addAction(AccessibilityProtectionProtocol.ACTION_RECOVER)
                    },
                    AccessibilityProtectionProtocol.PERMISSION,
                    handler,
                    Context.RECEIVER_EXPORTED,
                )
                controlReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法注册无障碍保护控制入口", failure)
            }
        }
    }

    private fun ensureActiveObserversRegistered(context: Context) {
        val observer = activeSettingsObserver ?: object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                schedule(context, "accessibility_settings_changed")
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                schedule(context, "accessibility_settings_changed")
            }
        }.also { activeSettingsObserver = it }

        val resolver = context.contentResolver
        ACTIVE_SETTING_URIS.forEach { uri ->
            if (uri in activeSettingUris) return@forEach
            try {
                resolver.registerContentObserver(uri, false, observer)
                activeSettingUris += uri
            } catch (failure: RuntimeException) {
                logFailure("无法监听无障碍设置", failure)
            }
        }

        if (!packageReceiverRegistered) {
            val receiver = packageReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.data?.schemeSpecificPart == APP_PACKAGE) {
                        schedule(receiverContext, "package_changed")
                    }
                }
            }.also { packageReceiver = it }
            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addDataScheme("package")
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                packageReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法监听 Eta 包变化", failure)
            }
        }

        if (!lifecycleReceiverRegistered) {
            val receiver = lifecycleReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    schedule(
                        context = receiverContext,
                        reason = when (intent.action) {
                            Intent.ACTION_USER_UNLOCKED -> "owner_unlocked"
                            Intent.ACTION_USER_PRESENT -> "owner_present"
                            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
                            else -> "locked_boot_completed"
                        },
                        delayMs = 0L,
                    )
                }
            }.also { lifecycleReceiver = it }
            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                        addAction(Intent.ACTION_BOOT_COMPLETED)
                        addAction(Intent.ACTION_USER_UNLOCKED)
                        // 回到解锁态时确认一次真实连接，稳定期间不轮询。
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                lifecycleReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("无法监听 owner 用户生命周期", failure)
            }
        }
    }

    private fun unregisterActiveObservers(context: Context) {
        if (activeSettingUris.isNotEmpty()) {
            try {
                activeSettingsObserver?.let(context.contentResolver::unregisterContentObserver)
                activeSettingUris.clear()
            } catch (failure: RuntimeException) {
                logFailure("无法停止监听无障碍设置", failure)
            }
        }
        if (packageReceiverRegistered) {
            try {
                packageReceiver?.let(context::unregisterReceiver)
                packageReceiverRegistered = false
            } catch (failure: RuntimeException) {
                logFailure("无法注销 Eta 包监听", failure)
            }
        }
        if (lifecycleReceiverRegistered) {
            try {
                lifecycleReceiver?.let(context::unregisterReceiver)
                lifecycleReceiverRegistered = false
            } catch (failure: RuntimeException) {
                logFailure("无法注销 owner 生命周期监听", failure)
            }
        }
    }

    private fun createControlReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            // 保护控制依赖系统附带的发送者身份，旧系统不接受降级后的身份校验。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            val senderUid = sentFromUid
            val ordered = isOrderedBroadcast
            val action = intent.action
            val protocolVersion = intent.getIntExtra(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                -1,
            )
            val requestedEnabled = intent.getBooleanExtra(
                AccessibilityProtectionProtocol.EXTRA_ENABLED,
                AccessibilityProtectionProtocol.DEFAULT_ENABLED,
            )
            val pendingResult = goAsync()
            if (
                !post {
                    var resultCode = AccessibilityProtectionProtocol.RESULT_UNAVAILABLE
                    var actualEnabled = false
                    try {
                        resultCode = applyControlRequest(
                            context = receiverContext,
                            action = action,
                            ordered = ordered,
                            protocolVersion = protocolVersion,
                            senderUid = senderUid,
                            requestedEnabled = requestedEnabled,
                        )
                        actualEnabled = isEnforcementEnabled(receiverContext)
                    } catch (failure: RuntimeException) {
                        logFailure("无障碍保护控制请求失败", failure)
                    }
                    completeControlRequest(
                        pendingResult = pendingResult,
                        resultCode = resultCode,
                        enabled = actualEnabled,
                    )
                }
            ) {
                completeControlRequest(
                    pendingResult = pendingResult,
                    resultCode = AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                    enabled = false,
                )
            }
        }
    }

    private fun applyControlRequest(
        context: Context,
        action: String?,
        ordered: Boolean,
        protocolVersion: Int,
        senderUid: Int,
        requestedEnabled: Boolean,
    ): Int {
        if (
            action !in CONTROL_ACTIONS ||
            !isControlCallerTrusted(context, ordered, protocolVersion, senderUid)
        ) {
            return AccessibilityProtectionProtocol.RESULT_REJECTED
        }

        if (action == AccessibilityProtectionProtocol.ACTION_RECOVER) {
            if (!isEnforcementEnabled(context)) {
                return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE
            }
            if (!isExpectedServiceValid(context)) {
                return AccessibilityProtectionProtocol.RESULT_REJECTED
            }
            return if (scheduleRuntimeRecovery(context)) {
                AccessibilityProtectionProtocol.RESULT_APPLIED
            } else {
                AccessibilityProtectionProtocol.RESULT_UNAVAILABLE
            }
        }

        if (requestedEnabled && !isExpectedServiceValid(context)) {
            return AccessibilityProtectionProtocol.RESULT_REJECTED
        }
        val stored = Settings.Global.putInt(
            context.contentResolver,
            AccessibilityProtectionProtocol.SETTING_NAME,
            if (requestedEnabled) ENABLED else DISABLED,
        )
        if (!stored) return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE

        restoreBackoff.reset()
        reconcile(context, "user_control")
        logger.info("无障碍保护开关已设置为 $requestedEnabled")
        return AccessibilityProtectionProtocol.RESULT_APPLIED
    }

    private fun scheduleRuntimeRecovery(context: Context): Boolean {
        if (!runtimeRecoveryScheduled.compareAndSet(false, true)) return true
        val posted = post {
            runtimeRecoveryScheduled.set(false)
            try {
                verifyServiceConnection(
                    context = context,
                    reason = "runtime_unavailable",
                    restoreMissingImmediately = true,
                )
            } catch (failure: RuntimeException) {
                logFailure("Runtime 请求的无障碍恢复失败", failure)
            }
        }
        if (!posted) {
            runtimeRecoveryScheduled.set(false)
        }
        return posted
    }

    private fun completeControlRequest(
        pendingResult: BroadcastReceiver.PendingResult,
        resultCode: Int,
        enabled: Boolean,
    ) {
        try {
            pendingResult.setResultCode(resultCode)
            pendingResult.setResultExtras(
                Bundle().apply {
                    putBoolean(AccessibilityProtectionProtocol.EXTRA_ENABLED, enabled)
                },
            )
        } catch (failure: RuntimeException) {
            logFailure("无法返回无障碍保护控制结果", failure)
        } finally {
            pendingResult.finish()
        }
    }

    private fun scheduleRegistrationRetry(context: Context) {
        if (
            registrationRetryIndex >= REGISTRATION_RETRY_DELAYS_MS.size ||
            !registrationRetryScheduled.compareAndSet(false, true)
        ) {
            return
        }
        val delayMs = REGISTRATION_RETRY_DELAYS_MS[registrationRetryIndex++]
        if (
            !post(delayMs) {
                registrationRetryScheduled.set(false)
                schedule(context, "observer_registration_retry", delayMs = 0L)
            }
        ) {
            registrationRetryScheduled.set(false)
            logFailure("无法调度无障碍监听注册重试")
        }
    }

    private fun scheduleHealthCheck(
        context: Context,
        reason: String,
        delayMs: Long,
    ) {
        if (!healthCheckScheduled.compareAndSet(false, true)) return
        if (
            !post(delayMs) {
                healthCheckScheduled.set(false)
                try {
                    verifyServiceConnection(context, reason)
                } catch (failure: RuntimeException) {
                    logFailure("无障碍连接检查失败", failure)
                }
            }
        ) {
            healthCheckScheduled.set(false)
            logFailure("无法调度无障碍连接检查")
        }
    }

    private fun verifyServiceConnection(
        context: Context,
        reason: String,
        restoreMissingImmediately: Boolean = false,
    ) {
        if (
            !isEnforcementEnabled(context) ||
            repairInProgress.get() ||
            !isOwnerUnlocked(context) ||
            !isExpectedServiceValid(context)
        ) {
            return
        }
        val currentServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (!containsAccessibilityService(currentServices, SERVICE_COMPONENT.flattenToString())) {
            if (restoreMissingImmediately) {
                // GUI 工具已经在等待本次恢复，不能被此前排队的 30 秒设置退避拖到超时。
                // 这里只响应经过协议版本与 UID 校验的显式请求；常规设置争抢仍遵守退避。
                enforce(context, "runtime_recovery_missing_service")
                if (isExpectedServiceConfigured(context)) {
                    scheduleHealthCheck(
                        context = context,
                        reason = "runtime_recovery_setting_restored",
                        delayMs = SERVICE_REBIND_GRACE_MS,
                    )
                }
            } else {
                schedule(context, "health_check_missing_service", delayMs = 0L)
            }
            return
        }

        when (queryServiceConnection(context)) {
            AccessibilityConnectionStatus.CONNECTED -> repairLimiter.reset()
            AccessibilityConnectionStatus.DISCONNECTED -> {
                val attempt = repairLimiter.nextAttempt(SystemClock.elapsedRealtime())
                if (attempt == null) {
                    logFailure("无障碍服务连续重绑失败，已进入冷却")
                } else {
                    beginServiceRepair(context, attempt, reason)
                }
            }
            AccessibilityConnectionStatus.UNKNOWN ->
                logFailure("无法确认无障碍服务连接状态")
        }
    }

    private fun queryServiceConnection(context: Context): AccessibilityConnectionStatus {
        val response = try {
            context.contentResolver.call(
                AccessibilityProtectionProtocol.HEALTH_URI,
                AccessibilityProtectionProtocol.HEALTH_METHOD,
                null,
                AccessibilityProtectionProtocol.request(),
            )
        } catch (failure: RuntimeException) {
            logFailure("无障碍健康检查 Provider 调用失败", failure)
            return AccessibilityConnectionStatus.UNKNOWN
        }
        return accessibilityConnectionStatus(
            protocolVersion = response?.getInt(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                -1,
            ),
            status = response?.getString(AccessibilityProtectionProtocol.HEALTH_STATUS),
        )
    }

    private fun beginServiceRepair(
        context: Context,
        attempt: AccessibilityRepairAttempt,
        reason: String,
    ) {
        if (!repairInProgress.compareAndSet(false, true)) return
        val resolver = context.contentResolver
        val servicesWithoutTarget = removeLatestAccessibilitySetting(resolver)
        if (servicesWithoutTarget == null) {
            repairInProgress.set(false)
            schedule(context, "repair_target_missing", delayMs = 0L)
            return
        }
        if (
            !Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                servicesWithoutTarget,
            )
        ) {
            repairInProgress.set(false)
            logFailure("无法临时关闭 Eta 无障碍服务")
            return
        }

        logger.info(
            "开始重绑无障碍服务: reason=$reason attempt=${attempt.number}",
        )
        if (
            !post(attempt.disabledDurationMs) {
                completeServiceRepair(context, attempt)
            }
        ) {
            repairInProgress.set(false)
            logFailure("无法调度 Eta 无障碍服务重启")
            schedule(context, "repair_schedule_failed", delayMs = 0L)
        }
    }

    private fun completeServiceRepair(
        context: Context,
        attempt: AccessibilityRepairAttempt,
    ) {
        try {
            if (isEnforcementEnabled(context) && isExpectedServiceValid(context)) {
                enforce(context, "repair_${attempt.number}")
            }
        } catch (failure: RuntimeException) {
            logFailure("无法重新启用 Eta 无障碍服务", failure)
        } finally {
            repairInProgress.set(false)
        }
        if (isEnforcementEnabled(context)) {
            scheduleHealthCheck(
                context = context,
                reason = "repair_${attempt.number}_confirmation",
                delayMs = SERVICE_REBIND_GRACE_MS,
            )
        }
    }

    private fun schedule(
        context: Context,
        reason: String,
        delayMs: Long? = null,
    ): Boolean {
        rerunRequested.set(true)
        if (!workScheduled.compareAndSet(false, true)) return true
        val resolvedDelayMs = delayMs ?: restoreBackoff.delayFor(SystemClock.elapsedRealtime())
        val posted = post(resolvedDelayMs) {
            rerunRequested.set(false)
            try {
                reconcile(context, reason)
            } catch (failure: RuntimeException) {
                logFailure("无障碍保护校验失败", failure)
            } finally {
                workScheduled.set(false)
                if (rerunRequested.get()) schedule(context, "late_change")
            }
        }
        if (!posted) {
            workScheduled.set(false)
            logFailure("无法调度无障碍保护校验")
        }
        return posted
    }

    private fun enforce(context: Context, reason: String) {
        if (!isEnforcementEnabled(context) || !isExpectedServiceValid(context)) return
        val resolver = context.contentResolver
        val mergedServices = mergeLatestAccessibilitySetting(resolver)
        var restoredServices = false
        var restoredMasterSwitch = false
        if (mergedServices != null) {
            restoredServices = Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                mergedServices,
            )
            if (!restoredServices) {
                logFailure("无法恢复无障碍服务列表")
            }
        }
        val serviceIsEnabled = mergedServices == null || restoredServices
        if (
            serviceIsEnabled &&
            Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                DISABLED,
            ) != ENABLED
        ) {
            restoredMasterSwitch = Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                ENABLED,
            )
            if (!restoredMasterSwitch) {
                logFailure("无法恢复无障碍总开关")
            }
        }
        if (restoredServices || restoredMasterSwitch) {
            restoreBackoff.recordRestore(SystemClock.elapsedRealtime())
            logRestore(reason, restoredServices, restoredMasterSwitch)
        }
    }

    private fun isEnforcementEnabled(context: Context): Boolean {
        val defaultValue = if (AccessibilityProtectionProtocol.DEFAULT_ENABLED) {
            ENABLED
        } else {
            DISABLED
        }
        return Settings.Global.getInt(
            context.contentResolver,
            AccessibilityProtectionProtocol.SETTING_NAME,
            defaultValue,
        ) == ENABLED
    }

    private fun isOwnerUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (failure: RuntimeException) {
        logFailure("无法读取 owner 用户状态", failure)
        false
    }

    private fun isExpectedServiceConfigured(context: Context): Boolean {
        val resolver = context.contentResolver
        if (
            Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                DISABLED,
            ) != ENABLED
        ) {
            return false
        }
        return containsAccessibilityService(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    /**
     * Settings 没有公开 CAS；缺失时再读一次最新快照，尽量不覆盖同时启用的其他服务。
     */
    private fun mergeLatestAccessibilitySetting(resolver: ContentResolver): String? {
        val initialValue = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (
            appendAccessibilityServiceIfMissing(
                initialValue,
                SERVICE_COMPONENT.flattenToString(),
            ) == null
        ) {
            return null
        }
        return appendAccessibilityServiceIfMissing(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    private fun removeLatestAccessibilitySetting(resolver: ContentResolver): String? {
        val initialValue = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (
            removeAccessibilityServiceIfPresent(
                initialValue,
                SERVICE_COMPONENT.flattenToString(),
            ) == null
        ) {
            return null
        }
        return removeAccessibilityServiceIfPresent(
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    private fun isExpectedServiceValid(context: Context): Boolean {
        val directBootFlags = directBootFlags()
        val serviceInfo = try {
            context.packageManager.getServiceInfo(
                SERVICE_COMPONENT,
                PackageManager.ComponentInfoFlags.of(directBootFlags.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (failure: RuntimeException) {
            logFailure("无法校验 Eta 无障碍服务组件", failure)
            return false
        }
        val applicationInfo = serviceInfo.applicationInfo
        val validComponent =
            serviceInfo.enabled &&
                applicationInfo.enabled &&
                serviceInfo.exported &&
                serviceInfo.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        return validComponent
    }

    private fun isControlCallerTrusted(
        context: Context,
        ordered: Boolean,
        protocolVersion: Int,
        senderUid: Int,
    ): Boolean {
        val directBootFlags = directBootFlags()
        val applicationInfo = try {
            context.packageManager.getApplicationInfo(
                APP_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(directBootFlags.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (failure: RuntimeException) {
            logFailure("无法校验无障碍保护控制调用方", failure)
            return false
        }
        return applicationInfo.enabled &&
            isAccessibilityControlRequestValid(
                ordered = ordered,
                protocolVersion = protocolVersion,
                senderUid = senderUid,
                appUid = applicationInfo.uid,
            )
    }

    private fun directBootFlags(): Int =
        PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE

    private fun post(delayMs: Long = 0L, block: () -> Unit): Boolean =
        try {
            handler.postDelayed(block, delayMs)
        } catch (failure: RuntimeException) {
            logger.warnThrottled("accessibility_handler_rejected", LOG_INTERVAL_MS) {
                "无障碍保护后台 Handler 拒绝任务: type=${failure.safeLogType()}"
            }
            false
        }

    private fun logFailure(message: String, failure: Throwable? = null) {
        logger.warnThrottled("accessibility_failure", LOG_INTERVAL_MS) {
            if (failure == null) {
                message
            } else {
                "$message: type=${failure.safeLogType()}"
            }
        }
    }

    private fun logRestore(
        reason: String,
        restoredServices: Boolean,
        restoredMasterSwitch: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastRestoreLogAt != 0L && now - lastRestoreLogAt < LOG_INTERVAL_MS) return
        lastRestoreLogAt = now
        logger.info(
            "已恢复 Eta 无障碍: reason=$reason " +
                "serviceList=$restoredServices masterSwitch=$restoredMasterSwitch",
        )
    }

    private companion object {
        const val APP_PACKAGE = "io.github.mangi.eta"
        const val SERVICE_CLASS =
            "io.github.mangi.eta.agent.accessibility.AgentAccessibilityService"
        const val DISABLED = 0
        const val ENABLED = 1
        const val LOG_INTERVAL_MS = 10_000L
        const val SERVICE_REBIND_GRACE_MS = 4_000L
        val REGISTRATION_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SERVICE_COMPONENT = ComponentName(APP_PACKAGE, SERVICE_CLASS)
        val CONTROL_SETTING_URI =
            Settings.Global.getUriFor(AccessibilityProtectionProtocol.SETTING_NAME)
        val ACTIVE_SETTING_URIS = listOf(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
        )
        val CONTROL_ACTIONS = setOf(
            AccessibilityProtectionProtocol.ACTION_SET,
            AccessibilityProtectionProtocol.ACTION_RECOVER,
        )
    }
}

internal enum class AccessibilityConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

internal fun accessibilityConnectionStatus(
    protocolVersion: Int?,
    status: String?,
): AccessibilityConnectionStatus {
    if (protocolVersion != AccessibilityProtectionProtocol.VERSION) {
        return AccessibilityConnectionStatus.UNKNOWN
    }
    return when (status) {
        AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED ->
            AccessibilityConnectionStatus.CONNECTED
        AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED ->
            AccessibilityConnectionStatus.DISCONNECTED
        else -> AccessibilityConnectionStatus.UNKNOWN
    }
}

internal data class AccessibilityRepairAttempt(
    val number: Int,
    val disabledDurationMs: Long,
)

/**
 * 连续失败时最多尝试三轮，再冷却一分钟，避免服务持续崩溃时形成无限拉起循环。
 */
internal class AccessibilityRepairLimiter(
    disabledDurationsMs: LongArray = longArrayOf(500L, 1_000L, 2_000L),
    private val cooldownMs: Long = 60_000L,
) {
    private val disabledDurationsMs = disabledDurationsMs.copyOf()
    private var attempts = 0
    private var lastAttemptAt: Long? = null

    init {
        require(this.disabledDurationsMs.isNotEmpty())
        require(this.disabledDurationsMs.all { it >= 0L })
        require(cooldownMs > 0L)
    }

    @Synchronized
    fun nextAttempt(now: Long): AccessibilityRepairAttempt? {
        val lastAttempt = lastAttemptAt
        if (
            attempts >= disabledDurationsMs.size &&
            lastAttempt != null &&
            now - lastAttempt < cooldownMs
        ) {
            return null
        }
        if (attempts >= disabledDurationsMs.size) attempts = 0
        return AccessibilityRepairAttempt(
            number = attempts + 1,
            disabledDurationMs = disabledDurationsMs[attempts],
        ).also {
            attempts += 1
            lastAttemptAt = now
        }
    }

    @Synchronized
    fun reset() {
        attempts = 0
        lastAttemptAt = null
    }
}

/**
 * OEM 持续反删设置时逐步退避到 30 秒；稳定一分钟后恢复快速响应。
 */
internal class AccessibilityRestoreBackoff(
    delaysMs: LongArray = longArrayOf(300L, 1_000L, 5_000L, 30_000L),
    private val stableWindowMs: Long = 60_000L,
) {
    private val delaysMs = delaysMs.copyOf()
    private var level = 0
    private var lastRestoreAt: Long? = null

    init {
        require(this.delaysMs.isNotEmpty())
        require(this.delaysMs.all { it >= 0L })
        require(stableWindowMs > 0L)
    }

    @Synchronized
    fun delayFor(now: Long): Long {
        resetIfStable(now)
        return delaysMs[level]
    }

    @Synchronized
    fun recordRestore(now: Long) {
        resetIfStable(now)
        level = (level + 1).coerceAtMost(delaysMs.lastIndex)
        lastRestoreAt = now
    }

    @Synchronized
    fun reset() {
        level = 0
        lastRestoreAt = null
    }

    private fun resetIfStable(now: Long) {
        val previous = lastRestoreAt ?: return
        if (now >= previous && now - previous >= stableWindowMs) {
            level = 0
            lastRestoreAt = null
        }
    }
}

internal fun isAccessibilityControlRequestValid(
    ordered: Boolean,
    protocolVersion: Int,
    senderUid: Int,
    appUid: Int,
): Boolean =
    ordered &&
        protocolVersion == AccessibilityProtectionProtocol.VERSION &&
        senderUid >= 0 &&
        senderUid == appUid

internal fun shouldScheduleAccessibilityHealthCheck(
    ownerUnlocked: Boolean,
    serviceConfigured: Boolean,
): Boolean = ownerUnlocked && serviceConfigured

internal fun appendAccessibilityServiceIfMissing(
    currentValue: String?,
    componentName: String,
): String? {
    val entries = accessibilityServiceEntries(currentValue)
    val targetIdentity = flattenedComponentIdentity(componentName)
    if (
        entries.any { entry ->
            entry == componentName ||
                (
                    targetIdentity != null &&
                        flattenedComponentIdentity(entry) == targetIdentity
                )
        }
    ) {
        return null
    }
    return (entries + componentName).joinToString(":")
}

internal fun removeAccessibilityServiceIfPresent(
    currentValue: String?,
    componentName: String,
): String? {
    val entries = accessibilityServiceEntries(currentValue)
    val targetIdentity = flattenedComponentIdentity(componentName)
    var removed = false
    val kept = entries.filter { entry ->
        val matches =
            entry == componentName ||
                (
                    targetIdentity != null &&
                        flattenedComponentIdentity(entry) == targetIdentity
                )
        if (matches) removed = true
        !matches
    }
    return if (removed) kept.joinToString(":") else null
}

internal fun containsAccessibilityService(
    currentValue: String?,
    componentName: String,
): Boolean {
    val targetIdentity = flattenedComponentIdentity(componentName)
    return accessibilityServiceEntries(currentValue).any { entry ->
        entry == componentName ||
            (
                targetIdentity != null &&
                    flattenedComponentIdentity(entry) == targetIdentity
                )
    }
}

private fun accessibilityServiceEntries(value: String?): List<String> =
    value.orEmpty()
        .split(':')
        .map(String::trim)
        .filter(String::isNotEmpty)

private fun flattenedComponentIdentity(value: String): Pair<String, String>? {
    val separator = value.indexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return null
    val packageName = value.substring(0, separator)
    val rawClassName = value.substring(separator + 1)
    val className = if (rawClassName.startsWith('.')) {
        packageName + rawClassName
    } else {
        rawClassName
    }
    if (packageName.isBlank() || className.isBlank()) return null
    return packageName to className
}
