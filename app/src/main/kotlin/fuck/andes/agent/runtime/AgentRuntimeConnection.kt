package fuck.andes.agent.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.SystemClock
import fuck.andes.core.AgentLogger
import fuck.andes.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 进程内共享的 Runtime Binder 连接。
 *
 * 活跃调用共享同一连接，最后一个调用结束后短暂保活，以覆盖连续对话和结果确认；
 * 空闲超时后主动解绑，避免入口进程长期拉住 Eta。
 */
internal object AgentRuntimeConnection {
    class Lease internal constructor(
        val messenger: Messenger,
        private val releaseAction: () -> Unit,
    ) : AutoCloseable {
        val binder: IBinder get() = messenger.binder

        private var released = false

        override fun close() {
            synchronized(this) {
                if (released) return
                released = true
            }
            releaseAction()
        }
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var logger: AgentLogger? = null
    private var messenger: Messenger? = null
    private var bound = false
    private var binding = false
    private var activeLeases = 0
    private var connectionLatch = CountDownLatch(0)
    private var bindStartedAt = 0L

    private val idleUnbind = Runnable { unbindIfIdle() }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                messenger = Messenger(service)
                binding = false
                bound = true
                connectionLatch.countDown()
                logger?.debug {
                    "Agent runtime service connected: elapsed_ms=" +
                        (SystemClock.elapsedRealtime() - bindStartedAt)
                }
                scheduleIdleUnbindLocked()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                messenger = null
                // 普通断线由系统自动重连当前 binding；不要叠加第二次 bindService。
                binding = true
                connectionLatch = CountDownLatch(1)
                bindStartedAt = SystemClock.elapsedRealtime()
            }
        }

        override fun onBindingDied(name: ComponentName) {
            resetDeadBinding()
        }

        override fun onNullBinding(name: ComponentName) {
            resetDeadBinding()
        }
    }

    fun acquire(context: Context, callLogger: AgentLogger): Lease? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Agent Runtime 同步客户端不能在主线程调用"
        }

        val shouldBind: Boolean
        val latch: CountDownLatch
        synchronized(lock) {
            mainHandler.removeCallbacks(idleUnbind)
            messenger?.let { connected ->
                activeLeases += 1
                return Lease(connected, ::release)
            }

            appContext = context.applicationContext
            logger = callLogger
            shouldBind = !binding
            if (shouldBind) {
                binding = true
                connectionLatch = CountDownLatch(1)
                bindStartedAt = SystemClock.elapsedRealtime()
            }
            latch = connectionLatch
        }

        if (shouldBind) {
            mainHandler.post { bind() }
        }
        if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null

        return synchronized(lock) {
            messenger?.let { connected ->
                mainHandler.removeCallbacks(idleUnbind)
                activeLeases += 1
                Lease(connected, ::release)
            }
        }
    }

    private fun bind() {
        val context = synchronized(lock) { appContext } ?: return failBinding()
        val succeeded = runCatching {
            context.bindService(
                AgentRuntimeWire.serviceIntent(),
                serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_INCLUDE_CAPABILITIES,
            )
        }.onFailure { throwable ->
            synchronized(lock) {
                logger?.warn("Agent runtime service bind failed: type=${throwable.safeLogType()}")
            }
        }.getOrDefault(false)

        synchronized(lock) {
            if (!succeeded) {
                bound = false
                binding = false
                connectionLatch.countDown()
            } else if (binding || messenger != null) {
                // onNullBinding/onBindingDied 可能紧邻 bindService 返回；不要覆盖其清理结果。
                bound = true
            }
        }
    }

    private fun failBinding() {
        synchronized(lock) {
            binding = false
            connectionLatch.countDown()
        }
    }

    private fun release() {
        synchronized(lock) {
            activeLeases = (activeLeases - 1).coerceAtLeast(0)
            scheduleIdleUnbindLocked()
        }
    }

    private fun scheduleIdleUnbindLocked() {
        if (activeLeases != 0 || !bound) return
        mainHandler.removeCallbacks(idleUnbind)
        mainHandler.postDelayed(idleUnbind, IDLE_UNBIND_DELAY_MS)
    }

    private fun unbindIfIdle() {
        val context = synchronized(lock) {
            if (activeLeases != 0 || !bound) return
            val currentContext = appContext
            messenger = null
            binding = false
            bound = false
            currentContext
        } ?: return
        runCatching { context.unbindService(serviceConnection) }
            .onFailure { throwable ->
                synchronized(lock) {
                    logger?.warn("Agent runtime service unbind failed: type=${throwable.safeLogType()}")
                }
            }
    }

    private fun resetDeadBinding() {
        val context = synchronized(lock) {
            val currentContext = appContext.takeIf { bound }
            messenger = null
            binding = false
            bound = false
            connectionLatch.countDown()
            currentContext
        }
        if (context != null) {
            runCatching { context.unbindService(serviceConnection) }
        }
    }

    private const val BIND_TIMEOUT_SECONDS = 8L
    private const val IDLE_UNBIND_DELAY_MS = 30_000L
}
