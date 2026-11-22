package io.github.mangi.eta.agent.device

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedFileCopyTest {
    @Test
    fun acceptsExactLimitAndRejectsExcessWithoutWritingPastLimit() {
        val content = ByteArray(16_384) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        BoundedFileCopy.copy(ByteArrayInputStream(content), output, content.size.toLong())
        assertArrayEquals(content, output.toByteArray())

        val bounded = ByteArrayOutputStream()
        try {
            BoundedFileCopy.copy(ByteArrayInputStream(content), bounded, 8_193L)
            throw AssertionError("超限文件必须被拒绝")
        } catch (_: BoundedFileCopy.TooLargeException) {
            assertTrue(bounded.size() <= 8_193)
        }
    }

    @Test
    fun copyWithStorageReserveRejectsBeforeWriteWhenDeclaredSizeViolatesReserve() {
        val policy = BoundedFileCopy.StorageReservePolicy(
            minFreeBytes = 100_000L,
            minFreeRatioNumerator = 0L,
            checkIntervalBytes = 16_384L,
        )
        val output = ByteArrayOutputStream()
        try {
            BoundedFileCopy.copyWithStorageReserve(
                input = ByteArrayInputStream(ByteArray(40_000)),
                output = output,
                declaredBytes = 60_000L,
                policy = policy,
                storageProbe = {
                    BoundedFileCopy.StorageSpace(totalBytes = 1_000_000L, usableBytes = 150_000L)
                },
            )
            throw AssertionError("超出可用安全余量时应快失败")
        } catch (_: BoundedFileCopy.InsufficientStorageException) {
            assertEquals(0, output.size())
        }
    }

    @Test
    fun copyWithStorageReserveAbortsMidStreamWhenRealUsableSpaceDropsBelowReserve() {
        val policy = BoundedFileCopy.StorageReservePolicy(
            minFreeBytes = 100_000L,
            minFreeRatioNumerator = 0L,
            checkIntervalBytes = 64 * 1024L,
        )
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        var usable = 220_000L
        try {
            BoundedFileCopy.copyWithStorageReserve(
                input = ByteArrayInputStream(content),
                output = output,
                declaredBytes = -1L,
                policy = policy,
                storageProbe = {
                    val current = usable
                    usable -= 80_000L
                    BoundedFileCopy.StorageSpace(totalBytes = 1_000_000L, usableBytes = current)
                },
            )
            throw AssertionError("运行中触碰存储安全水位必须立即中止")
        } catch (_: BoundedFileCopy.InsufficientStorageException) {
            assertTrue("应在完整写出前中止", output.size() < content.size)
        }
    }

    @Test
    fun copyWithStorageReserveStreamsLargePayloadWhenHeadroomIsSufficient() {
        val policy = BoundedFileCopy.StorageReservePolicy(
            minFreeBytes = 512L * 1024L * 1024L,
            minFreeRatioNumerator = 2L,
            checkIntervalBytes = 128 * 1024L,
        )
        val content = ByteArray(300 * 1024) { (it % 199).toByte() }
        val output = ByteArrayOutputStream()
        val copied = BoundedFileCopy.copyWithStorageReserve(
            input = ByteArrayInputStream(content),
            output = output,
            declaredBytes = content.size.toLong(),
            policy = policy,
            storageProbe = {
                BoundedFileCopy.StorageSpace(
                    totalBytes = 128L * 1024L * 1024L * 1024L,
                    usableBytes = 20L * 1024L * 1024L * 1024L,
                )
            },
        )
        assertEquals(content.size.toLong(), copied)
        assertArrayEquals(content, output.toByteArray())
        assertEquals(
            BoundedFileCopy.StorageReservePolicy.DEFAULT_MAX_RESERVE_BYTES,
            policy.requiredReserveBytes(1024L * 1024L * 1024L * 1024L),
        )
    }
}
