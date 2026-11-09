package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.HandlerThread
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/** 已分离的资源只在工作线程回收，不再持有 Controller 的状态锁。 */
internal class VirtualDisplaySessionResources(
    val reader: ImageReader?,
    val frameWorker: HandlerThread?,
    val virtualDisplay: VirtualDisplay?,
    val bridge: RootVirtualDisplayBridge?,
    val manager: DisplayManager?,
    val listener: DisplayManager.DisplayListener?,
) {
    fun close() {
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (exception: Exception) {
                AndroidAgentLogger.warnThrottled("virtual_display_cleanup_failed") {
                    "Virtual display cleanup failed: type=${exception.safeLogType()}"
                }
            }
        }
        closePart { listener?.let { manager?.unregisterDisplayListener(it) } }
        closePart { reader?.setOnImageAvailableListener(null, null) }
        closePart { reader?.close() }
        closePart { frameWorker?.quitSafely() }
        closePart { virtualDisplay?.release() }
        closePart { bridge?.close() }
    }
}

internal class RootVirtualDisplayBridge private constructor(
    val displayId: Int,
    val process: Process,
    private val supervisor: ShellProcessSupervisor,
    private val disconnected: AtomicBoolean,
) {
    val isAlive: Boolean
        get() = !disconnected.get() && process.isAlive

    fun close() {
        try {
            process.outputStream.write("release\n".toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            process.outputStream.close()
        } catch (_: Exception) {
            // 断开的管道交给下面的进程树回收处理。
        }
        supervisor.beginClosing()
        try {
            if (process.isAlive) supervisor.terminateAndReap(process) else supervisor.reapProcess(process)
        } finally {
            supervisor.unregisterProcess(process)
        }
    }

    companion object {
        fun create(
            appContext: Context,
            surface: android.view.Surface,
            width: Int,
            height: Int,
            densityDpi: Int,
            rootAvailable: () -> Boolean,
            onDisconnected: (Process) -> Unit,
        ): RootVirtualDisplayBridge? {
            val token = VirtualDisplaySurfaceRegistry.register(surface)
            val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
            var process: Process? = null
            var accepted = false
            try {
                val apkPath = appContext.applicationInfo.sourceDir
                val command = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
                    "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
                val started = supervisor.startShellProcess("root", command, mergeStderr = true) ?: return null
                process = started
                val readyLatch = CountDownLatch(1)
                val disconnected = AtomicBoolean(false)
                var resolvedDisplayId: Int? = null
                val payload = JSONObject()
                    .put("action", "hold_display")
                    .put("user_id", DeviceToolContract.appUserId(appContext))
                    .put("surface_token", token)
                    .put("width", width)
                    .put("height", height)
                    .put("density_dpi", densityDpi)
                    .toString() + "\n"

                thread(name = "eta-vd-bridge-stdout", isDaemon = true) {
                    try {
                        BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.startsWith(VirtualDisplayCommandMain.READY_PREFIX)) {
                                    resolvedDisplayId = line.removePrefix(VirtualDisplayCommandMain.READY_PREFIX)
                                        .trim().toIntOrNull()
                                    readyLatch.countDown()
                                } else if (line.startsWith(VirtualDisplayCommandMain.RESULT_PREFIX)) {
                                    readyLatch.countDown()
                                }
                            }
                        }
                    } catch (_: java.io.IOException) {
                        // 管道关闭与子进程退出共用失效路径。
                    } finally {
                        disconnected.set(true)
                        readyLatch.countDown()
                        onDisconnected(started)
                    }
                }
                started.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                started.outputStream.flush()
                val awaited = try {
                    readyLatch.await(4, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                val id = resolvedDisplayId
                if (!awaited || id == null || !CanvasRoutingPolicy.isVirtualDisplay(id) ||
                    disconnected.get() || !started.isAlive
                ) return null
                accepted = true
                return RootVirtualDisplayBridge(id, started, supervisor, disconnected)
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("Virtual display bridge failed: type=${exception.safeLogType()}")
                return null
            } finally {
                VirtualDisplaySurfaceRegistry.unregister(token)
                if (!accepted) {
                    supervisor.beginClosing()
                    process?.let { failed ->
                        try {
                            if (failed.isAlive) supervisor.terminateAndReap(failed) else supervisor.reapProcess(failed)
                        } finally {
                            supervisor.unregisterProcess(failed)
                        }
                    }
                }
            }
        }
    }
}
