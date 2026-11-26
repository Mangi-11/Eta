package io.github.mangi.eta.hook.originos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport
import io.github.mangi.eta.hook.system.CircleToSearchInvoker
import java.lang.reflect.Field

internal object OriginOsContextualSearch {
    private const val DESCRIPTOR = "android.app.contextualsearch.IContextualSearchManager"
    private const val SEARCH_PERMISSION = "android.permission.ACCESS_CONTEXTUAL_SEARCH"

    // 单独的事务编号让未加载 Eta Hook 的服务拒绝请求，避免客户端误发原有 oneway 调用。
    private const val START_GESTURE_SEARCH = 0x00455441
    private const val START_SUCCESS = 0
    private const val START_TASK_TO_FRONT = 2
    private const val START_DELIVERED_TO_TOP = 3

    enum class Gesture(
        val entryPoint: Int,
        val preferenceKey: String,
    ) {
        NAVIGATION_HANDLE(1, Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
        DOUBLE_FINGER(9, Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH),
    }

    private class LaunchReceipt(
        var started: Boolean = false,
    )

    private val pendingLaunch = ThreadLocal<LaunchReceipt>()

    fun tryStart(
        context: Context,
        gesture: Gesture,
        logger: ModuleLogger,
    ): Boolean {
        if (context.checkSelfPermission(SEARCH_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            logger.warnThrottled("originos_search_permission") { "OriginOS: 缺少 Contextual Search 权限，保留系统行为" }
            return false
        }
        val binder =
            CircleToSearchInvoker.findAvailableBinder(context, logger, "OriginOS", "保留系统行为")
                ?: return false
        return requestSearch(binder, gesture, logger)
    }

    private fun requestSearch(
        binder: IBinder,
        gesture: Gesture,
        logger: ModuleLogger,
    ): Boolean {
        val request = Parcel.obtain(binder)
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(gesture.entryPoint)
            if (!binder.transact(START_GESTURE_SEARCH, request, reply, 0)) {
                logger.warnThrottled("originos_search_reply_unavailable") { "OriginOS: 一圈即搜系统请求未被处理，保留系统行为" }
                return false
            }
            reply.readException()
            val started = reply.readBoolean()
            if (!started) {
                logger.warnThrottled("originos_search_launch_failed") { "OriginOS: 一圈即搜未启动，保留系统行为" }
            }
            started
        } catch (exception: RemoteException) {
            logger.warnThrottled("originos_search_binder_failed") {
                "OriginOS: 一圈即搜 Binder 调用失败，保留系统行为，type=${exception.safeLogType()}"
            }
            false
        } catch (exception: SecurityException) {
            logger.warnThrottled("originos_search_denied") {
                "OriginOS: 系统拒绝一圈即搜请求，保留系统行为，type=${exception.safeLogType()}"
            }
            false
        } finally {
            reply.recycle()
            request.recycle()
        }
    }

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        loader: ClassLoader,
    ): HookInstallation =
        HookRegistrar(module, logger, "OriginOsContextualSearch").install {
            if (HookSupport.findClassOrNull(loader, "com.android.server.policy.VivoInputPolicy") != null) {
                installSynchronousRequest(loader)
            }
        }

    private fun HookRegistrar.installSynchronousRequest(loader: ClassLoader) {
        val frameworkStub = HookSupport.findClassOrNull(loader, "$DESCRIPTOR\$Stub")
        val serverStub = HookSupport.findClassOrNull(loader, "${ModuleConfig.CONTEXTUAL_SEARCH_CLASS}\$ContextualSearchManagerStub")
        val intType = Int::class.javaPrimitiveType!!
        val onTransact =
            frameworkStub?.let {
                HookSupport.findMethod(it, "onTransact", intType, Parcel::class.java, Parcel::class.java, intType)
            }
        val startCode = frameworkStub?.let { HookSupport.findField(it, "TRANSACTION_startContextualSearch")?.getInt(null) }
        val hookId = "originos.contextual-search-request"
        val description = "IContextualSearchManager.Stub.onTransact"
        val startSearch = serverStub?.let { HookSupport.findMethod(it, "startContextualSearch", intType) }
        if (onTransact == null || startCode == null || serverStub == null || startSearch?.returnType != Void.TYPE ||
            onTransact.returnType != Boolean::class.javaPrimitiveType
        ) {
            missing(hookId, description, "OriginOS: 一圈即搜 Binder 签名不匹配")
            return
        }
        if (!installLaunchReceipt(loader)) return

        intercept(hookId, onTransact, description) { chain ->
            if (chain.getArg(0) != START_GESTURE_SEARCH || !serverStub.isInstance(chain.getThisObject())) {
                chain.proceed()
            } else {
                respondToGestureSearch(chain, startCode)
            }
        }
    }

    private fun respondToGestureSearch(
        chain: XposedInterface.Chain,
        startCode: Int,
    ): Boolean {
        val reply = chain.getArg(2) as? Parcel ?: return false
        val flags = chain.getArg(3) as Int
        if ((flags and IBinder.FLAG_ONEWAY) != 0 || Binder.getCallingUid() != Process.myUid() || pendingLaunch.get() != null) {
            return false
        }
        val request = chain.getArg(1) as Parcel
        val gesture = readGesture(request) ?: return false
        if (!Prefs.isEnabled(gesture.preferenceKey)) return false

        val receipt = LaunchReceipt()
        pendingLaunch.set(receipt)
        try {
            // 复用原 AIDL 的权限校验和系统截图流程。
            if (chain.proceed(arrayOf(startCode, request, reply, flags)) != true) return false
            reply.writeNoException()
            reply.writeBoolean(receipt.started)
            return true
        } finally {
            pendingLaunch.remove()
        }
    }

    private fun readGesture(request: Parcel): Gesture? {
        val position = request.dataPosition()
        return try {
            request.enforceInterface(DESCRIPTOR)
            val entryPoint = request.readInt()
            Gesture.entries.firstOrNull { it.entryPoint == entryPoint }
        } finally {
            request.setDataPosition(position)
        }
    }

    private fun HookRegistrar.installLaunchReceipt(loader: ClassLoader): Boolean {
        val starter = HookSupport.findClassOrNull(loader, "com.android.server.wm.ActivityStarter")
        val request = HookSupport.findClassOrNull(loader, "com.android.server.wm.ActivityStarter\$Request")
        val executeRequest =
            if (starter != null && request != null) {
                HookSupport.findMethod(starter, "executeRequest", request)
            } else {
                null
            }
        val launchReason = request?.let { HookSupport.findField(it, "reason") }
        val launchIntent = request?.let { HookSupport.findField(it, "intent") }
        val hookId = "originos.contextual-search-launch-result"
        val description = "ActivityStarter.executeRequest"
        if (executeRequest == null || executeRequest.returnType != Int::class.javaPrimitiveType ||
            launchReason == null || launchReason.type != String::class.java ||
            launchIntent == null || launchIntent.type != Intent::class.java
        ) {
            missing(hookId, description, "OriginOS: 无法读取一圈即搜 Activity 启动结果")
            return false
        }

        return intercept(hookId, executeRequest, description) { chain ->
            captureScreenshotLaunch(chain, launchReason, launchIntent)
        } != null
    }

    private fun captureScreenshotLaunch(
        chain: XposedInterface.Chain,
        launchReason: Field,
        launchIntent: Field,
    ): Any? {
        val receipt = pendingLaunch.get() ?: return chain.proceed()
        val launchRequest = chain.getArg(0)
        val searchIntent = launchIntent.get(launchRequest) as? Intent
        if (launchReason.get(launchRequest) != "startActivityWithScreenshot" ||
            searchIntent?.component?.packageName != ModuleConfig.GOOGLE_PACKAGE
        ) {
            return chain.proceed()
        }

        // execute() 会把 START_ABORTED 转成 START_SUCCESS。这里读取转换前的结果。
        val startResult = chain.proceed() as Int
        receipt.started =
            when (startResult) {
                START_SUCCESS, START_TASK_TO_FRONT, START_DELIVERED_TO_TOP -> true
                else -> false
            }
        return startResult
    }
}
