package fuck.andes.agent.runtime

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Base64InputStream
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AndroidAgentLogger
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
 * 发送端只在自己的缓存目录暂存图片，并通过只读文件描述符交给 Runtime；接收端在后台读取后，
 * 才恢复成模型协议需要的 data URL。远程 HTTP(S) URL 不落盘，直接透传。
 */
internal object AgentRuntimeImageTransfer {
    private const val MAX_IMAGE_COUNT = 8
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_TOTAL_IMAGE_BYTES = 32 * 1024 * 1024
    private const val MAX_REMOTE_URL_CHARS = 16 * 1024
    private const val MAX_ENCODED_IMAGE_CHARS = MAX_IMAGE_BYTES * 2
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
            throw ImageTransferException("一次最多支持 $MAX_IMAGE_COUNT 张图片")
        }

        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY)
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw ImageTransferException("无法创建图片传输缓存")
        }
        cleanupStaleFiles(cacheDirectory)

        val files = mutableListOf<File>()
        val wireImages = mutableListOf<AgentRuntimeWire.WireImage>()
        var totalBytes = 0L
        try {
            images.forEach { image ->
                if (image.reference.isRemoteUrl()) {
                    if (image.reference.length > MAX_REMOTE_URL_CHARS) {
                        throw ImageTransferException("远程图片链接过长")
                    }
                    wireImages += image.toWireImage(remoteUrl = image.reference)
                    return@forEach
                }

                val directDescriptor = openDirectDescriptor(context, image.reference)
                val directSize = directDescriptor?.statSize ?: -1L
                if (directSize > MAX_IMAGE_BYTES) {
                    directDescriptor?.close()
                    throw ImageTransferException("单张图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MiB")
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
                    copyReferenceToFile(context, image.reference, transferFile)
                    imageBytes = transferFile.length()
                    descriptor = ParcelFileDescriptor.open(
                        transferFile,
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                }
                if (imageBytes <= 0L) {
                    descriptor.close()
                    throw ImageTransferException("图片内容为空")
                }
                if (imageBytes > MAX_IMAGE_BYTES) {
                    descriptor.close()
                    throw ImageTransferException("单张图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                totalBytes += imageBytes
                if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                    descriptor.close()
                    throw ImageTransferException("图片总大小不能超过 ${MAX_TOTAL_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                wireImages += image.toWireImage(
                    fileDescriptor = descriptor,
                    bytes = imageBytes.toInt(),
                )
            }
            return PreparedImages(wireImages, files)
        } catch (throwable: Throwable) {
            PreparedImages(wireImages, files).close()
            if (throwable is ImageTransferException) throw throwable
            throw ImageTransferException("无法读取待发送图片", throwable)
        }
    }

    /** 必须在 Runtime 后台线程调用；会消费并关闭 [incoming] 持有的文件描述符。 */
    fun materialize(
        incoming: AgentRuntimeWire.IncomingRunRequest,
    ): AgentRuntimeWire.RunRequest = incoming.use { request ->
        if (request.images.size > MAX_IMAGE_COUNT) {
            throw ImageTransferException("一次最多支持 $MAX_IMAGE_COUNT 张图片")
        }

        var totalBytes = 0L
        val images = request.images.mapIndexed { index, image ->
            image.fileDescriptor?.let { descriptor ->
                val startedAt = SystemClock.elapsedRealtime()
                val statSize = descriptor.statSize
                if (statSize <= 0L) {
                    throw ImageTransferException("图片文件描述符无有效大小")
                }
                if (statSize > MAX_IMAGE_BYTES) {
                    throw ImageTransferException("单张图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readBytesLimited(MAX_IMAGE_BYTES)
                }
                if (bytes.size.toLong() != statSize) {
                    throw ImageTransferException("图片传输不完整")
                }
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                    throw ImageTransferException("图片总大小不能超过 ${MAX_TOTAL_IMAGE_BYTES / 1024 / 1024} MiB")
                }
                return@mapIndexed AgentImageCodec.fromAttachmentBytes(
                    bytes = bytes,
                    source = image.source,
                    mimeHint = image.mimeType,
                ).also { materialized ->
                    AndroidAgentLogger.debug {
                        "Agent image action=materialize index=$index " +
                            "input_bytes=${bytes.size} output_bytes=${materialized.bytes} " +
                            "output=${materialized.width}x${materialized.height} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
                    }
                }
            }

            val reference = image.remoteUrl.orEmpty()
            if (reference.isRemoteUrl()) {
                if (reference.length > MAX_REMOTE_URL_CHARS) {
                    throw ImageTransferException("远程图片链接过长")
                }
                return@mapIndexed AgentModelClient.ModelImage(
                    reference = reference,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = image.source,
                )
            }
            if (reference.length > MAX_ENCODED_IMAGE_CHARS) {
                throw ImageTransferException("内联图片数据过大")
            }
            AgentImageCodec.fromReference(
                context = null,
                value = reference,
                source = image.source,
            ) ?: throw ImageTransferException("无法读取旧协议中的图片")
        }
        request.request.copy(images = images)
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
    )

    private fun copyReferenceToFile(
        context: Context,
        reference: String,
        outputFile: File,
    ) {
        val input = when {
            reference.startsWith("data:image/", ignoreCase = true) ->
                reference.openDataUrlStream()

            Uri.parse(reference).scheme in setOf("content", "file") ->
                context.contentResolver.openInputStream(Uri.parse(reference))
                    ?: throw ImageTransferException("无法打开本地图片")

            else -> {
                val file = File(reference)
                if (!file.isFile) throw ImageTransferException("图片引用不可读")
                file.inputStream()
            }
        }
        input.use { source ->
            FileOutputStream(outputFile).use { target ->
                source.copyToLimited(target, MAX_IMAGE_BYTES)
            }
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
            throw ImageTransferException("不支持的内联图片格式")
        }
        val encoded = substring(separator + 1)
        if (encoded.length > MAX_ENCODED_IMAGE_CHARS) {
            throw ImageTransferException("内联图片数据过大")
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
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) {
                throw ImageTransferException("单张图片不能超过 ${maxBytes / 1024 / 1024} MiB")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            total += read
            if (total > maxBytes) {
                throw ImageTransferException("单张图片不能超过 ${maxBytes / 1024 / 1024} MiB")
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
