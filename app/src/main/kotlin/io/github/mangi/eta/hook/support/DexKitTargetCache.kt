package io.github.mangi.eta.hook.support

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType

/** 缓存只保存当前 APK 的描述符，反射签名仍由 Hook 目标解析器校验。 */
internal class DexKitTargetCache(
    private val apkPath: String,
    private val directory: File,
    private val logger: AgentLogger,
) {
    private val sourceIdentity: String? by lazy { readSourceIdentity() }

    fun read(key: String): String? {
        val identity = sourceIdentity ?: return null
        return try {
            val file = cacheFile(key)
            if (!file.isFile || file.length() > MAX_CACHE_BYTES) return null
            val values = Properties().apply { file.inputStream().use(::load) }
            if (values.getProperty("source") == identity) values.getProperty("method") else null
        } catch (exception: Exception) {
            logger.warn("DexKit 目标缓存读取失败: key=$key, type=${exception.safeLogType()}")
            null
        }
    }

    fun write(key: String, descriptor: String) {
        val identity = sourceIdentity ?: return
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                logger.warn("DexKit 目标缓存目录不可用")
                return
            }
            temporary = File.createTempFile("eta-target-", ".tmp", directory)
            val values = Properties().apply {
                setProperty("source", identity)
                setProperty("method", descriptor)
            }
            temporary.outputStream().use { values.store(it, null) }
            // 独立临时文件配合原子替换，避免不同宿主进程读到半份描述符。
            Files.move(
                temporary.toPath(),
                cacheFile(key).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (exception: Exception) {
            logger.warn("DexKit 目标缓存写入失败: key=$key, type=${exception.safeLogType()}")
        } finally {
            temporary?.let {
                if (it.exists() && !it.delete()) logger.warn("DexKit 临时缓存清理失败")
            }
        }
    }

    private fun cacheFile(key: String): File = File(directory, "${digest(key)}.properties")

    private fun readSourceIdentity(): String? = try {
        val apk = File(apkPath)
        // ROM 更新可能保留版本号与文件时间；中央目录中的 DEX 校验值也参与失效判断。
        val dexEntries = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { DEX_ENTRY_PATTERN.matches(it.name) }
                .map { "${it.name}:${it.crc}:${it.size}" }
                .sorted()
                .toList()
        }
        if (dexEntries.isEmpty()) {
            null
        } else {
            digest(listOf("1", apkPath, apk.length(), apk.lastModified(), dexEntries).joinToString("|"))
        }
    } catch (exception: Exception) {
        logger.warn("DexKit 无法读取 APK 标识: type=${exception.safeLogType()}")
        null
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_CACHE_BYTES = 16L * 1024L
        val DEX_ENTRY_PATTERN = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
    }
}
