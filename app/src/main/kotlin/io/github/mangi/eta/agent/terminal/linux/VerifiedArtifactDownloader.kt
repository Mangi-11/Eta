package io.github.mangi.eta.agent.terminal.linux

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class VerifiedArtifact(
    val id: String,
    val version: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** 在官方地址前尝试的镜像；镜像只改变传输路径，完整大小和摘要校验仍以制品清单为准。 */
    val preferredUrls: List<String> = emptyList(),
    val fallbackUrls: List<String> = emptyList(),
)

internal class VerifiedArtifactDownloader(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val logger: AgentLogger = AndroidAgentLogger,
) {
    suspend fun download(
        artifact: VerifiedArtifact,
        target: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean {
        target.parentFile?.mkdirs()
        if (verify(artifact, target)) return true
        val candidates = (artifact.preferredUrls + artifact.url + artifact.fallbackUrls).distinct()
        for ((attempt, url) in candidates.withIndex()) {
            target.delete()
            if (downloadOnce(artifact, target, url, attempt + 1, onProgress)) return true
        }
        target.delete()
        return false
    }

    private suspend fun downloadOnce(
        artifact: VerifiedArtifact,
        target: File,
        url: String,
        attempt: Int,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(url).get().build()
        val valid = try {
            httpClient.newCall(request).execute().use responseUse@ { response ->
                if (!response.isSuccessful) {
                    logger.warn(
                        "Verified artifact action=download id=${artifact.id} attempt=$attempt " +
                            "outcome=failed httpCode=${response.code}",
                    )
                    return@responseUse false
                }
                val declaredLength = response.body.contentLength()
                if (declaredLength > artifact.sizeBytes) return@responseUse false
                val digest = MessageDigest.getInstance("SHA-256")
                var bytesRead = 0L
                var lastReported = 0L
                var tooLarge = false
                target.outputStream().buffered().use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytesRead += count.toLong()
                            if (bytesRead > artifact.sizeBytes) {
                                tooLarge = true
                                break
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (bytesRead - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                lastReported = bytesRead
                                onProgress(bytesRead, artifact.sizeBytes)
                            }
                        }
                    }
                }
                if (tooLarge) return@responseUse false
                val actualSha256 = digest.digest().toHexString()
                val accepted = bytesRead == artifact.sizeBytes && actualSha256 == artifact.sha256
                logger.info(
                    "Verified artifact action=download id=${artifact.id} attempt=$attempt " +
                        "outcome=${if (accepted) "succeeded" else "rejected"} bytes=$bytesRead",
                )
                accepted
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.warn(
                "Verified artifact action=download id=${artifact.id} attempt=$attempt outcome=failed " +
                    "errorType=${throwable.safeLogType()}",
            )
            false
        }
        return valid
    }

    fun verify(artifact: VerifiedArtifact, file: File): Boolean {
        if (!file.isFile || file.length() != artifact.sizeBytes) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHexString() == artifact.sha256
        }.getOrDefault(false)
    }

    companion object {
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
