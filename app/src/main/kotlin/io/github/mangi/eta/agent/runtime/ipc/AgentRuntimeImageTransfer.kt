package io.github.mangi.eta.agent.runtime.ipc

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Base64InputStream
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_SCREEN_IMAGE_BYTES
import io.github.mangi.eta.agent.media.ModelImageBudget
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把模型图片正文从 Messenger Bundle 中移出。
 *
 * 发送端对本地原图走只读文件描述符零拷贝直传（不对未压缩原图施加 12 MiB 硬上限）；
 * 接收端在后台按目标模型渠道预算流式采样解码并自适应编码，保真截图通道仍逐字节透传。
 * 远程 HTTP(S) URL 不落盘，直接透传。
 */
internal object AgentRuntimeImageTransfer {
    private const val MAX_IMAGE_COUNT = 8
    private const val MAX_TOTAL_MATERIALIZED_IMAGE_BYTES = 64 * 1024 * 1024
    private const val MAX_REMOTE_URL_CHARS = 16 * 1024
    private const val MAX_ENCODED_IMAGE_CHARS = MAX_SCREEN_IMAGE_BYTES * 2
    private const val CACHE_DIRECTORY = "agent-runtime-transfer"
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    class ImageTransferException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    class PreparedImages internal constructor(
        val images: List<AgentRuntimeWire.WireImage>,
        private val files: List<File>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            images.forEach { image -> runCatching { image.fileDescriptor?.close() } }
            files.forEach { file -> runCatching { file.delete() } }
        }
    }

    fun prepare(
        context: Context,
        images: List<AgentModelClient.ModelImage>,
    ): PreparedImages {
        if (images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException(
                AppStrings.get(
                    R.string.runtime_err_image_max_count,
                    "一次最多支持 %1\$d 张图片",
                    MAX_IMAGE_COUNT,
                ),
            )
        }

        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw ImageTransferException(
                AppStrings.get(R.string.runtime_err_image_cache_create, "无法创建图片传输缓存"),
            )
        }
        cleanupStaleFiles(cacheDirectory)

        val files = mutableListOf<File>()
        val wireImages = mutableListOf<AgentRuntimeWire.WireImage>()
        try {
            images.forEach { image ->
                if (image.reference.isRemoteUrl()) {
                    if (image.reference.length > MAX_REMOTE_URL_CHARS) {
                        throw ImageTransferException(
                            AppStrings.get(R.string.runtime_err_image_remote_url_long, "远程图片链接过长"),
                        )
                    }
                    wireImages += image.toWireImage(remoteUrl = image.reference)
                    return@forEach
                }

                val directDescriptor = openDirectDescriptor(context, image.reference)
                val directSize = directDescriptor?.statSize ?: -1L
                if (image.preserveOriginal && directSize > MAX_SCREEN_IMAGE_BYTES) {
                    directDescriptor?.close()
                    throw ImageTransferException(
                        AppStrings.get(
                            R.string.runtime_err_image_single_too_large,
                            "单张图片不能超过 %1\$d MiB",
                            MAX_SCREEN_IMAGE_BYTES / 1024 / 1024,
                        ),
                    )
                }
                val descriptor: ParcelFileDescriptor
                val imageBytes: Long
                if (directDescriptor != null && directSize > 0L) {
                    descriptor = directDescriptor
                    imageBytes = directSize
                } else {
                    directDescriptor?.close()
                    val transferFile = File(
                        cacheDirectory,
                        "image-${UUID.randomUUID()}.bin",
                    )
                    files += transferFile
                    copyReferenceToFile(
                        context = context,
                        reference = image.reference,
                        outputFile = transferFile,
                        cacheDirectory = cacheDirectory,
                        preserveOriginal = image.preserveOriginal,
                    )
                    imageBytes = transferFile.length()
                    descriptor = ParcelFileDescriptor.open(
                        transferFile,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                }
                if (imageBytes <= 0L) {
                    descriptor.close()
                    throw ImageTransferException(
                        AppStrings.get(R.string.runtime_err_image_empty, "图片内容为空"),
                    )
                }
                if (image.preserveOriginal && imageBytes > MAX_SCREEN_IMAGE_BYTES) {
                    descriptor.close()
                    throw ImageTransferException(
                        AppStrings.get(
                            R.string.runtime_err_image_single_too_large,
                            "单张图片不能超过 %1\$d MiB",
                            MAX_SCREEN_IMAGE_BYTES / 1024 / 1024,
                        ),
                    )
                }
                wireImages += image.toWireImage(
                    fileDescriptor = descriptor,
                    bytes = imageBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
            return PreparedImages(wireImages, files)
        } catch (throwable: Throwable) {
            PreparedImages(wireImages, files).close()
            if (throwable is ImageTransferException) throw throwable
            throw ImageTransferException(
                AppStrings.get(R.string.runtime_err_image_read_outgoing, "无法读取待发送图片"),
                throwable,
            )
        }
    }

    /** 必须在 Runtime 后台线程调用；会消费并关闭 [incoming] 持有的文件描述符。 */
    fun materialize(
        incoming: AgentRuntimeWire.IncomingRunRequest,
    ): AgentRuntimeWire.RunRequest = incoming.use { request ->
        if (request.images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException(
                AppStrings.get(
                    R.string.runtime_err_image_max_count,
                    "一次最多支持 %1\$d 张图片",
                    MAX_IMAGE_COUNT,
                ),
            )
        }

        val budget = ModelImageBudget.forProviderType(request.request.config.providerType)
        var totalMaterializedBytes = 0L
        val images = request.images.mapIndexed { index, image ->
            image.fileDescriptor?.let { descriptor ->
                val startedAt = SystemClock.elapsedRealtime()
                val statSize = descriptor.statSize
                if (statSize <= 0L) {
                    throw ImageTransferException(
                        AppStrings.get(
                            R.string.runtime_err_image_fd_invalid_size,
                            "图片文件描述符无有效大小",
                        ),
                    )
                }
                val materialized = if (image.preserveOriginal) {
                    if (statSize > MAX_SCREEN_IMAGE_BYTES) {
                        throw ImageTransferException(
                            AppStrings.get(
                                R.string.runtime_err_image_single_too_large,
                                "单张图片不能超过 %1\$d MiB",
                                MAX_SCREEN_IMAGE_BYTES / 1024 / 1024,
                            ),
                        )
                    }
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        input.readBytesLimited(MAX_SCREEN_IMAGE_BYTES)
                    }
                    if (bytes.size.toLong() != statSize) {
                        throw ImageTransferException(
                            AppStrings.get(R.string.runtime_err_image_incomplete, "图片传输不完整"),
                        )
                    }
                    AgentImageCodec.fromScreenBytes(bytes, image.source, image.mimeType)
                } else {
                    materializeAttachmentDescriptor(
                        descriptor = descriptor,
                        expectedSize = statSize,
                        source = image.source,
                        mimeHint = image.mimeType,
                        budget = budget,
                    )
                }
                totalMaterializedBytes += materialized.bytes
                if (totalMaterializedBytes > MAX_TOTAL_MATERIALIZED_IMAGE_BYTES) {
                    throw ImageTransferException(
                        AppStrings.get(
                            R.string.runtime_err_image_total_too_large,
                            "图片总大小不能超过 %1\$d MiB",
                            MAX_TOTAL_MATERIALIZED_IMAGE_BYTES / 1024 / 1024,
                        ),
                    )
                }
                return@mapIndexed materialized.also {
                    AndroidAgentLogger.debug {
                        "Agent image action=materialize index=$index " +
                            "input_bytes=$statSize output_bytes=${materialized.bytes} " +
                            "output=${materialized.width}x${materialized.height} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                    }
                }
            }

            val reference = image.remoteUrl.orEmpty()
            if (reference.isRemoteUrl()) {
                if (reference.length > MAX_REMOTE_URL_CHARS) {
                    throw ImageTransferException(
                        AppStrings.get(R.string.runtime_err_image_remote_url_long, "远程图片链接过长"),
                    )
                }
                return@mapIndexed AgentModelClient.ModelImage(
                    reference = reference,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = image.source,
                    preserveOriginal = image.preserveOriginal,
                )
            }
            if (reference.length > MAX_ENCODED_IMAGE_CHARS) {
                throw ImageTransferException(
                    AppStrings.get(R.string.runtime_err_image_inline_too_large, "内联图片数据过大"),
                )
            }
            val materialized = AgentImageCodec.fromReference(
                context = null,
                value = reference,
                source = image.source,
                preserveOriginal = image.preserveOriginal,
                budget = budget,
            ) ?: throw ImageTransferException(
                AppStrings.get(R.string.runtime_err_image_legacy_unreadable, "无法读取旧协议中的图片"),
            )
            totalMaterializedBytes += materialized.bytes
            if (totalMaterializedBytes > MAX_TOTAL_MATERIALIZED_IMAGE_BYTES) {
                throw ImageTransferException(
                    AppStrings.get(
                        R.string.runtime_err_image_total_too_large,
                        "图片总大小不能超过 %1\$d MiB",
                        MAX_TOTAL_MATERIALIZED_IMAGE_BYTES / 1024 / 1024,
                    ),
                )
            }
            materialized
        }
        request.materializeText().copy(images = images)
    }

    private fun materializeAttachmentDescriptor(
        descriptor: ParcelFileDescriptor,
        expectedSize: Long,
        source: String,
        mimeHint: String,
        budget: ModelImageBudget,
    ): AgentModelClient.ModelImage = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { fis ->
        val seekable = runCatching {
            fis.channel.position(0L)
            true
        }.getOrDefault(false)
        if (seekable) {
            val actualSize = runCatching { fis.channel.size() }.getOrDefault(-1L)
            if (actualSize >= 0L && actualSize != expectedSize) {
                throw ImageTransferException(
                    AppStrings.get(R.string.runtime_err_image_incomplete, "图片传输不完整"),
                )
            }
            return@use runCatching {
                AgentImageCodec.fromAttachmentDescriptor(fis, source, mimeHint, budget)
            }.getOrElse { throwable ->
                throw ImageTransferException(
                    AppStrings.get(R.string.runtime_err_read_image_failed, "Agent Runtime 无法读取图片"),
                    throwable,
                )
            }
        }

        val tempFile = File.createTempFile("eta-materialize-", ".img")
        try {
            val copied = tempFile.outputStream().use { output ->
                BoundedFileCopy.copyWithStorageReserve(
                    input = fis,
                    output = output,
                    targetDirectory = tempFile.parentFile ?: File("/tmp"),
                    declaredBytes = expectedSize,
                )
            }
            if (copied != expectedSize) {
                throw ImageTransferException(
                    AppStrings.get(R.string.runtime_err_image_incomplete, "图片传输不完整"),
                )
            }
            runCatching {
                AgentImageCodec.fromAttachmentFile(tempFile, source, mimeHint, budget)
            }.getOrElse { throwable ->
                throw ImageTransferException(
                    AppStrings.get(R.string.runtime_err_read_image_failed, "Agent Runtime 无法读取图片"),
                    throwable,
                )
            }
        } catch (storage: BoundedFileCopy.InsufficientStorageException) {
            throw ImageTransferException(
                AppStrings.get(
                    R.string.capability_import_insufficient_storage,
                    "设备可用存储空间不足，无法导入该文件",
                ),
                storage,
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun AgentModelClient.ModelImage.toWireImage(
        remoteUrl: String? = null,
        fileDescriptor: ParcelFileDescriptor? = null,
        bytes: Int = this.bytes,
    ): AgentRuntimeWire.WireImage = AgentRuntimeWire.WireImage(
        remoteUrl = remoteUrl,
        fileDescriptor = fileDescriptor,
        mimeType = mimeType,
        bytes = bytes,
        width = width,
        height = height,
        source = source,
        preserveOriginal = preserveOriginal,
    )

    private fun copyReferenceToFile(
        context: Context,
        reference: String,
        outputFile: File,
        cacheDirectory: File,
        preserveOriginal: Boolean,
    ) {
        val isDataUrl = reference.startsWith("data:image/", ignoreCase = true)
        val input = when {
            isDataUrl -> reference.openDataUrlStream()

            Uri.parse(reference).scheme in setOf("content", "file") ->
                context.contentResolver.openInputStream(Uri.parse(reference))
                    ?: throw ImageTransferException(
                        AppStrings.get(R.string.runtime_err_image_open_local, "无法打开本地图片"),
                    )

            else -> {
                val file = File(reference)
                if (!file.isFile) {
                    throw ImageTransferException(
                        AppStrings.get(R.string.runtime_err_image_ref_unreadable, "图片引用不可读"),
                    )
                }
                file.inputStream()
            }
        }
        try {
            input.use { source ->
                FileOutputStream(outputFile).use { target ->
                    if (isDataUrl || preserveOriginal) {
                        source.copyToLimited(target, MAX_SCREEN_IMAGE_BYTES)
                    } else {
                        BoundedFileCopy.copyWithStorageReserve(
                            input = source,
                            output = target,
                            targetDirectory = cacheDirectory,
                        )
                    }
                }
            }
        } catch (storage: BoundedFileCopy.InsufficientStorageException) {
            throw ImageTransferException(
                AppStrings.get(
                    R.string.capability_import_insufficient_storage,
                    "设备可用存储空间不足，无法导入该文件",
                ),
                storage,
            )
        }
    }

    private fun openDirectDescriptor(
        context: Context,
        reference: String,
    ): ParcelFileDescriptor? = runCatching {
        val uri = Uri.parse(reference)
        when (uri.scheme) {
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")
            "file" -> uri.path
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            null, "" -> File(reference)
                .takeIf(File::isFile)
                ?.let { file ->
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            else -> null
        }
    }.getOrNull()

    private fun String.openDataUrlStream(): InputStream {
        val separator = indexOf(',')
        if (separator <= 0 || !substring(0, separator).endsWith(";base64", ignoreCase = true)) {
            throw ImageTransferException(
                AppStrings.get(R.string.runtime_err_image_inline_unsupported, "不支持的内联图片格式"),
            )
        }
        val encoded = substring(separator + 1)
        if (encoded.length > MAX_ENCODED_IMAGE_CHARS) {
            throw ImageTransferException(
                AppStrings.get(R.string.runtime_err_image_inline_too_large, "内联图片数据过大"),
            )
        }
        return Base64InputStream(
            ByteArrayInputStream(encoded.toByteArray(Charsets.US_ASCII)),
            Base64.DEFAULT,
        )
    }

    private fun InputStream.copyToLimited(
        output: FileOutputStream,
        maxBytes: Int,
    ) {
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) {
                throw ImageTransferException(
                    AppStrings.get(
                        R.string.runtime_err_image_single_too_large,
                        "单张图片不能超过 %1\$d MiB",
                        maxBytes / 1024 / 1024,
                    ),
                )
            }
            output.write(buffer, 0, read)
        }
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            total += read
            if (total > maxBytes) {
                throw ImageTransferException(
                    AppStrings.get(
                        R.string.runtime_err_image_single_too_large,
                        "单张图片不能超过 %1\$d MiB",
                        maxBytes / 1024 / 1024,
                    ),
                )
            }
            output.write(buffer, 0, read)
        }
    }

    private fun String.isRemoteUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        runCatching {
            directory.listFiles().orEmpty()
                .asSequence()
                .filter { file -> file.isFile && file.lastModified() < cutoff }
                .forEach { file -> file.delete() }
        }
    }
}
