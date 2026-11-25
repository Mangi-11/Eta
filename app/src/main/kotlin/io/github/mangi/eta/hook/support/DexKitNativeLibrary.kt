package io.github.mangi.eta.hook.support

import android.os.Build
import android.os.Process
import java.io.File
import io.github.mangi.eta.core.AgentLogger

/** 原生库必须由模块的 ClassLoader 加载，宿主只提供私有缓存目录。 */
internal object DexKitNativeLibrary {
    private var loaded = false

    @Synchronized
    fun load(moduleApkPath: String, nativeDirectory: String?, cacheDirectory: File, logger: AgentLogger) {
        if (loaded) return
        try {
            System.loadLibrary("dexkit")
            loaded = true
            return
        } catch (error: UnsatisfiedLinkError) {
            logger.debug { "DexKit 标准加载不可用: reason=${nativeLoadFailureReason(error)}" }
        }
        if (!nativeDirectory.isNullOrBlank()) {
            try {
                System.load(File(nativeDirectory, "libdexkit.so").absolutePath)
                loaded = true
                return
            } catch (error: UnsatisfiedLinkError) {
                logger.debug { "DexKit 安装目录加载不可用: reason=${nativeLoadFailureReason(error)}" }
            }
        }
        // 压缩 APK 条目无法直接 dlopen；框架提供的安装目录也可能不可用于当前进程。
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val library = DexKitNativeLibraryFile.extract(moduleApkPath, cacheDirectory, abis.toList())
        System.load(library.absolutePath)
        loaded = true
        logger.info("DexKit 已从模块 APK 加载原生库")
    }
}

/** 只记录固定错误分类，避免把 linker 消息中的文件路径写进日志。 */
internal fun nativeLoadFailureReason(error: UnsatisfiedLinkError): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        "namespace" in message -> "namespace"
        "permission denied" in message -> "permission_denied"
        "already loaded" in message -> "classloader_conflict"
        "no implementation found" in message -> "jni_binding"
        "not found" in message || "couldn't find" in message || "no such file" in message -> "not_found"
        "elf" in message || "32-bit" in message || "64-bit" in message -> "abi_mismatch"
        else -> "linker_error"
    }
}
