package io.github.mangi.eta.agent.device

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

/** 流式复制授权文件：支持显式字节预算与基于分区安全水位的物理磁盘护栏。 */
internal object BoundedFileCopy {
    private const val COPY_BUFFER_BYTES = 64 * 1024

    internal data class StorageSpace(
        val totalBytes: Long,
        val usableBytes: Long,
    )

    internal data class StorageReservePolicy(
        val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
        val maxReserveBytes: Long = DEFAULT_MAX_RESERVE_BYTES,
        val minFreeRatioNumerator: Long = DEFAULT_MIN_FREE_RATIO_NUMERATOR,
        val minFreeRatioDenominator: Long = 100L,
        val checkIntervalBytes: Long = DEFAULT_CHECK_INTERVAL_BYTES,
    ) {
        init {
            require(minFreeBytes >= 0L)
            require(maxReserveBytes >= minFreeBytes)
            require(minFreeRatioNumerator >= 0L)
            require(minFreeRatioDenominator > 0L)
            require(checkIntervalBytes > 0L)
        }

        fun requiredReserveBytes(totalBytes: Long): Long {
            val ratioReserve = if (totalBytes > 0L) {
                (totalBytes / minFreeRatioDenominator) * minFreeRatioNumerator
            } else {
                0L
            }
            return ratioReserve.coerceIn(minFreeBytes, maxReserveBytes)
        }

        companion object {
            const val DEFAULT_MIN_FREE_BYTES = 512L * 1024L * 1024L
            const val DEFAULT_MAX_RESERVE_BYTES = 1024L * 1024L * 1024L
            const val DEFAULT_MIN_FREE_RATIO_NUMERATOR = 2L
            const val DEFAULT_CHECK_INTERVAL_BYTES = 1024L * 1024L
        }
    }

    fun availableWriteAllowance(
        targetDirectory: File,
        policy: StorageReservePolicy = StorageReservePolicy(),
    ): Long? {
        val space = probeStorage(targetDirectory)
        if (!space.isMeasurable()) return null
        val reserve = policy.requiredReserveBytes(space.totalBytes)
        return (space.usableBytes - reserve).coerceAtLeast(0L)
    }

    /** 按显式字节上限流式复制，不信任文档提供方声明的文件大小。 */
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0L)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val read = input.read(buffer)
            if (read < 0) return copied
            if (copied + read > maxBytes) throw TooLargeException()
            output.write(buffer, 0, read)
            copied += read
        }
    }

    /**
     * 按分区安全水位流式复制：不对正常文件施加任意业务体积上限，
     * 仅确保目标分区始终保有系统安全余量，且在写入过程中周期复查真实剩余空间。
     */
    fun copyWithStorageReserve(
        input: InputStream,
        output: OutputStream,
        targetDirectory: File,
        declaredBytes: Long = -1L,
        policy: StorageReservePolicy = StorageReservePolicy(),
    ): Long = copyWithStorageReserve(
        input = input,
        output = output,
        declaredBytes = declaredBytes,
        policy = policy,
        storageProbe = { probeStorage(targetDirectory) },
    )

    internal fun copyWithStorageReserve(
        input: InputStream,
        output: OutputStream,
        declaredBytes: Long = -1L,
        policy: StorageReservePolicy = StorageReservePolicy(),
        storageProbe: () -> StorageSpace,
    ): Long {
        val initialSpace = storageProbe()
        if (initialSpace.isMeasurable()) {
            val reserve = policy.requiredReserveBytes(initialSpace.totalBytes)
            val usable = initialSpace.usableBytes
            if (usable <= reserve) throw InsufficientStorageException()
            if (declaredBytes > 0L && usable - reserve < declaredBytes) {
                throw InsufficientStorageException()
            }
        }

        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var bytesSinceCheck = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val read = input.read(buffer)
            if (read < 0) return copied
            output.write(buffer, 0, read)
            copied += read
            bytesSinceCheck += read
            if (bytesSinceCheck >= policy.checkIntervalBytes) {
                bytesSinceCheck = 0L
                val currentSpace = storageProbe()
                if (currentSpace.isMeasurable()) {
                    val reserve = policy.requiredReserveBytes(currentSpace.totalBytes)
                    if (currentSpace.usableBytes < reserve) {
                        throw InsufficientStorageException()
                    }
                }
            }
        }
    }

    private fun probeStorage(directory: File): StorageSpace {
        val probe = generateSequence(directory) { it.parentFile }
            .firstOrNull(File::exists)
            ?: directory
        return StorageSpace(
            totalBytes = probe.totalSpace,
            usableBytes = probe.usableSpace,
        )
    }

    private fun StorageSpace.isMeasurable(): Boolean = totalBytes > 0L || usableBytes > 0L

    class TooLargeException : IOException("文件超过大小限制")

    class InsufficientStorageException : IOException("设备可用存储空间不足")
}
