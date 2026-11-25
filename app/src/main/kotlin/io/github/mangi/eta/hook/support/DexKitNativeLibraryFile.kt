package io.github.mangi.eta.hook.support

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 仅从已安装的模块 APK 提取固定库名，缓存内容必须与 APK 中的字节一致。 */
internal object DexKitNativeLibraryFile {
    fun extract(moduleApkPath: String, cacheDirectory: File, supportedAbis: List<String>): File {
        val (abi, bytes) = ZipFile(moduleApkPath).use { zip ->
            val match = supportedAbis.asSequence()
                .filter(ABI_PATTERN::matches)
                .mapNotNull { abi -> zip.getEntry("lib/$abi/libdexkit.so")?.let { abi to it } }
                .firstOrNull() ?: error("模块 APK 缺少当前架构的 DexKit 原生库")
            val entry = match.second
            check(entry.size in 1..MAX_LIBRARY_BYTES.toLong()) { "DexKit 原生库大小无效" }
            val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_LIBRARY_BYTES + 1) }
            check(bytes.size.toLong() == entry.size) { "DexKit 原生库内容不完整" }
            match.first to bytes
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val directory = File(cacheDirectory, "native/$abi-$hash")
        check(directory.isDirectory || directory.mkdirs()) { "DexKit 原生库缓存目录不可用" }
        val library = File(directory, "libdexkit.so")
        if (!Files.isSymbolicLink(library.toPath()) && library.isFile && library.length() == bytes.size.toLong()) {
            if (library.inputStream().use { it.readNBytes(bytes.size + 1) }.contentEquals(bytes)) {
                check(library.setReadOnly()) { "DexKit 原生库无法设为只读" }
                return library
            }
        }
        val temporary = File.createTempFile("eta-dexkit-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.setReadOnly()) { "DexKit 原生库无法设为只读" }
            Files.move(
                temporary.toPath(), library.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            return library
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private const val MAX_LIBRARY_BYTES = 8 * 1024 * 1024
    private val ABI_PATTERN = Regex("[a-zA-Z0-9_-]+")
}
