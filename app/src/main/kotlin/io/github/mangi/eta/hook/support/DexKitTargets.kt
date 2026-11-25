package io.github.mangi.eta.hook.support

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType

/** 单次 Hook 安装共享 APK 解析资源；查询语义变化时，调用方必须更新 key 的版本。 */
internal class DexKitTargets(
    private val apkPath: String,
    private val classLoader: ClassLoader,
    private val logger: AgentLogger,
    private val cacheDirectory: File? = null,
    private val moduleNativeLibraryDirectory: String? = null,
    private val moduleApkPath: String,
) : Closeable {
    private var bridge: DexKitBridge? = null
    private var bridgeUnavailable = false
    private var closed = false
    private val resolvedMethods = mutableMapOf<String, Method?>()
    private val cache = cacheDirectory?.let { DexKitTargetCache(apkPath, it, logger) }

    fun findMethod(
        key: String,
        validate: (Method) -> Boolean,
        query: DexKitBridge.() -> List<MethodData>,
    ): Method? {
        check(!closed) { "DexKit 目标解析器已关闭" }
        require(KEY_PATTERN.matches(key)) { "DexKit 目标标识无效" }
        if (resolvedMethods.containsKey(key)) return resolvedMethods[key]
        val cached = readCachedMethod(key, validate)
        if (cached != null) {
            resolvedMethods[key] = cached
            logger.debug { "DexKit 命中目标缓存: $key" }
            return cached
        }

        val result = try {
            val activeBridge = getBridge() ?: return null
            val matches = query(activeBridge).distinctBy { it.descriptor }
            val match = matches.singleOrNull()
            if (match == null) {
                logger.warn("DexKit 未找到唯一目标: key=$key, matches=${matches.size}")
                null
            } else {
                val method = match.getMethodInstance(classLoader)
                if (!validate(method)) {
                    logger.warn("DexKit 目标签名校验失败: key=$key")
                    null
                } else {
                    cache?.write(key, match.descriptor)
                    logger.debug { "DexKit 已定位目标: $key" }
                    method
                }
            }
        } catch (exception: Exception) {
            logger.warn("DexKit 目标查询失败: key=$key, type=${exception.safeLogType()}")
            null
        } catch (error: LinkageError) {
            logger.warn("DexKit 目标链接失败: key=$key, type=${error.safeLogType()}")
            null
        }
        resolvedMethods[key] = result
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        val activeBridge = bridge
        bridge = null
        resolvedMethods.clear()
        activeBridge?.close()
    }

    private fun getBridge(): DexKitBridge? {
        bridge?.let { return it }
        if (bridgeUnavailable) return null
        bridgeUnavailable = true
        try {
            DexKitNativeLibrary.load(
                moduleApkPath,
                moduleNativeLibraryDirectory,
                requireNotNull(cacheDirectory) { "DexKit 原生库缓存目录不可用" },
                logger,
            )
        } catch (error: UnsatisfiedLinkError) {
            logger.warn("DexKit 原生库加载失败: reason=${nativeLoadFailureReason(error)}")
            return null
        } catch (exception: Exception) {
            logger.warn("DexKit 原生库准备失败: type=${exception.safeLogType()}")
            return null
        }
        try {
            val created = DexKitBridge.create(apkPath)
            if (!created.isValid) {
                created.close()
                logger.warn("DexKit 无法解析目标 APK")
                return null
            }
            bridge = created
            return created
        } catch (exception: Exception) {
            logger.warn("DexKit 初始化失败: type=${exception.safeLogType()}")
        } catch (error: LinkageError) {
            logger.warn("DexKit JNI 初始化失败: type=${error.safeLogType()}")
        }
        return null
    }

    private fun readCachedMethod(key: String, validate: (Method) -> Boolean): Method? {
        val descriptor = cache?.read(key) ?: return null
        return try {
            DexMethod(descriptor).getMethodInstance(classLoader).takeIf(validate)
        } catch (exception: Exception) {
            logger.warn("DexKit 目标缓存无效: key=$key, type=${exception.safeLogType()}")
            null
        } catch (error: LinkageError) {
            logger.warn("DexKit 缓存目标链接失败: key=$key, type=${error.safeLogType()}")
            null
        }
    }

    private companion object {
        val KEY_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
