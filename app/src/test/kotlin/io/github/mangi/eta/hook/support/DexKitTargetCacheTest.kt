package io.github.mangi.eta.hook.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.github.mangi.eta.core.AgentLogger

class DexKitTargetCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unchangedApkReusesDescriptorAcrossCacheInstances() {
        val apk = temporaryFolder.newFile("host.apk")
        writeApk(apk, "dex-one")
        val directory = temporaryFolder.newFolder("cache")

        cache(apk, directory).write("entry.v1", DESCRIPTOR)

        assertEquals(DESCRIPTOR, cache(apk, directory).read("entry.v1"))
        assertNull(cache(apk, directory).read("entry.v2"))
    }

    @Test
    fun changedDexInvalidatesCacheEvenWhenApkSizeAndTimeStayTheSame() {
        val apk = temporaryFolder.newFile("host.apk")
        writeApk(apk, "dex-one")
        val directory = temporaryFolder.newFolder("cache")
        cache(apk, directory).write("entry.v1", DESCRIPTOR)
        val previousSize = apk.length()
        val previousTime = apk.lastModified()

        writeApk(apk, "dex-two")
        assertTrue(apk.setLastModified(previousTime))
        assertEquals(previousSize, apk.length())
        assertEquals(previousTime, apk.lastModified())

        assertNull(cache(apk, directory).read("entry.v1"))
    }

    @Test
    fun damagedCacheIsAMissAndCanBeReplaced() {
        val apk = temporaryFolder.newFile("host.apk")
        writeApk(apk, "dex-one")
        val directory = temporaryFolder.newFolder("cache")
        cache(apk, directory).write("entry.v1", DESCRIPTOR)
        directory.listFiles()!!.single().writeText("source=\\uXXXX")

        assertNull(cache(apk, directory).read("entry.v1"))
        cache(apk, directory).write("entry.v1", DESCRIPTOR)

        assertEquals(DESCRIPTOR, cache(apk, directory).read("entry.v1"))
    }

    @Test
    fun unavailableCacheDirectoryDoesNotThrow() {
        val apk = temporaryFolder.newFile("host.apk")
        writeApk(apk, "dex-one")
        val unavailableDirectory = temporaryFolder.newFile("cache")
        val targetCache = cache(apk, unavailableDirectory)

        targetCache.write("entry.v1", DESCRIPTOR)

        assertNull(targetCache.read("entry.v1"))
        assertTrue(unavailableDirectory.isFile)
    }

    private fun cache(apk: File, directory: File) = DexKitTargetCache(apk.path, directory, logger)

    private fun writeApk(file: File, content: String) {
        val bytes = content.toByteArray()
        ZipOutputStream(file.outputStream()).use { zip ->
            val entry = ZipEntry("classes.dex").apply {
                method = ZipEntry.STORED
                time = 0L
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    private val logger = object : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val DESCRIPTOR = "Lexample/Host;->handle()V"
    }
}
