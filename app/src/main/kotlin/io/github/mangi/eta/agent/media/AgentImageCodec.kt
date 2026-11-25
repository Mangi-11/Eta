package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal object AgentImageCodec {
    private const val STAGED_ATTACHMENT_DIR = "agent-attachments/images"
    private const val STALE_STAGED_ATTACHMENT_AGE_MILLIS = 72L * 60 * 60 * 1_000L
    private const val HEADER_MAGIC_BYTES = 16

    internal sealed interface StageResult {
        data class Success(val image: AgentModelClient.ModelImage) : StageResult
        data object InsufficientStorage : StageResult
        data object InvalidImage : StageResult
    }

    fun fromBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_SCREEN_IMAGE_BYTES) { "图片数据过大：${bytes.size}" }
        // 已编码图片只读取尺寸和格式，正文保留原始字节。
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val recognizedImage = bytes.hasSupportedImageMagic()
        val mime = opts.outMimeType
            ?.takeIf { recognizedImage && it.isNotBlank() }
            ?: mimeHint.normalizedAgentImageMimeType()
        return AgentModelClient.ModelImage(
            reference = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            mimeType = mime,
            bytes = bytes.size,
            width = opts.outWidth.takeIf { recognizedImage && it > 0 },
            height = opts.outHeight.takeIf { recognizedImage && it > 0 },
            source = source,
        )
    }

    /** 用户附件统一流式规范化为模型兼容的 JPEG，并严格收敛到目标预算以内。 */
    fun fromAttachmentBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage =
        AgentModelImageEncoder.attachment(bytes, source, mimeHint, budget)
            ?: error("无法解码图片")

    fun fromAttachmentFile(
        file: File,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage =
        AgentModelImageEncoder.encodeFileForModel(file, source, mimeHint, budget)
            ?: error("无法解码图片")

    fun fromAttachmentDescriptor(
        inputStream: FileInputStream,
        source: String,
        mimeHint: String = "image/jpeg",
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage =
        AgentModelImageEncoder.encodeSeekableDescriptorForModel(inputStream, source, mimeHint, budget)
            ?: error("无法解码图片")

    /** 已有截图编码直接透传，不解码像素、缩放或转换格式。 */
    fun fromScreenBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/png",
    ): AgentModelClient.ModelImage =
        fromBytes(bytes, source, mimeHint).copy(preserveOriginal = true)

    fun fromScreenBitmap(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage = AgentModelImageEncoder.screen(bitmap, source)

    /** 助理与 GUI 截图共用原始像素合同。 */
    fun fromScreenContextBitmap(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage = fromScreenBitmap(bitmap, source)

    /**
     * 为聊天列表生成独立的小预览。模型仍从 [image.reference] 读取原图，预览不会参与模型输入。
     */
    fun previewFromReference(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = AgentModelImageEncoder.preview(context, image)

    /** 文件工具图片在发送模型前流式采样并压缩，不拒绝高像素大原图。 */
    fun fromToolFile(
        file: File,
        source: String,
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? =
        AgentModelImageEncoder.toolVision(file, source, budget = budget)

    /**
     * 用户选图 / 系统分享的摄入层：流式暂存至私有文件（仅受磁盘安全水位保护，不限原文件大小），
     * 避免在 UI 进程堆内存中囤积大原图 Base64，也脱离 ROM Photo Picker URI 授权生命周期。
     */
    fun stageAttachment(
        context: Context,
        value: String,
        source: String,
    ): StageResult {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return StageResult.InvalidImage
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return StageResult.Success(
                AgentModelClient.ModelImage(
                    reference = trimmed,
                    mimeType = "image/*",
                    bytes = 0,
                    source = source,
                ),
            )
        }
        val appContext = context.applicationContext
        val stagingDirectory = File(appContext.filesDir, STAGED_ATTACHMENT_DIR)
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            return StageResult.InvalidImage
        }
        cleanupStaleStagedAttachments(stagingDirectory)

        val stagedFile = File(stagingDirectory, "att-${UUID.randomUUID()}.img")
        var completed = false
        return try {
            val mimeHint = when {
                trimmed.startsWith("data:image/", ignoreCase = true) -> {
                    val decoded = decodeDataUrlBytes(trimmed) ?: return StageResult.InvalidImage
                    stagedFile.outputStream().use { output ->
                        BoundedFileCopy.copyWithStorageReserve(
                            input = decoded.inputStream(),
                            output = output,
                            targetDirectory = stagingDirectory,
                            declaredBytes = decoded.size.toLong(),
                        )
                    }
                    trimmed.substring("data:".length).substringBefore(';')
                }
                else -> {
                    val uri = Uri.parse(trimmed)
                    when (uri.scheme) {
                        "content" -> {
                            copyContentUriToFile(appContext, uri, stagedFile, stagingDirectory)
                                ?: return StageResult.InvalidImage
                            runCatching { appContext.contentResolver.getType(uri) }.getOrNull().orEmpty()
                        }
                        "file" -> {
                            val file = uri.path?.let(::File)?.takeIf(File::isFile)
                                ?: return StageResult.InvalidImage
                            file.inputStream().use { input ->
                                stagedFile.outputStream().use { output ->
                                    BoundedFileCopy.copyWithStorageReserve(
                                        input = input,
                                        output = output,
                                        targetDirectory = stagingDirectory,
                                        declaredBytes = file.length(),
                                    )
                                }
                            }
                            ""
                        }
                        else -> {
                            val file = File(trimmed).takeIf(File::isFile)
                                ?: return StageResult.InvalidImage
                            file.inputStream().use { input ->
                                stagedFile.outputStream().use { output ->
                                    BoundedFileCopy.copyWithStorageReserve(
                                        input = input,
                                        output = output,
                                        targetDirectory = stagingDirectory,
                                        declaredBytes = file.length(),
                                    )
                                }
                            }
                            ""
                        }
                    }
                }
            }
            val inspected = inspectRecognizedImageFile(stagedFile, stagedFile.absolutePath, source, mimeHint)
                ?: return StageResult.InvalidImage
            completed = true
            StageResult.Success(inspected)
        } catch (_: BoundedFileCopy.InsufficientStorageException) {
            StageResult.InsufficientStorage
        } catch (_: IOException) {
            StageResult.InvalidImage
        } catch (_: SecurityException) {
            StageResult.InvalidImage
        } catch (_: RuntimeException) {
            StageResult.InvalidImage
        } finally {
            if (!completed) stagedFile.delete()
        }
    }

    fun fromReference(
        context: Context?,
        value: String,
        source: String,
        preserveOriginal: Boolean = false,
        budget: ModelImageBudget = ModelImageBudget.DEFAULT,
    ): AgentModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return AgentModelClient.ModelImage(
                reference = trimmed,
                mimeType = "image/*",
                bytes = 0,
                source = source,
                preserveOriginal = preserveOriginal,
            )
        }
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val raw = decodeDataUrlBytes(trimmed) ?: return null
            val mimeHint = trimmed.substring("data:".length).substringBefore(";")
            return if (preserveOriginal) {
                fromScreenBytes(raw, source, mimeHint)
            } else {
                AgentModelImageEncoder.attachment(raw, source, mimeHint, budget)
            }
        }

        if (context != null) {
            val uri = Uri.parse(trimmed)
            if (uri.scheme == "content") {
                return readContentUri(context, uri, source, preserveOriginal, budget)
            }
            if (uri.scheme == "file") {
                val file = uri.path?.let(::File)?.takeIf(File::isFile) ?: return null
                return readLocalFile(file, source, preserveOriginal, budget)
            }
            val file = File(trimmed)
            if (file.isFile) {
                return readLocalFile(file, source, preserveOriginal, budget)
            }
        }

        return runCatching {
            if (!trimmed.looksLikeBase64()) return@runCatching null
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            if (!decoded.hasSupportedImageMagic()) return@runCatching null
            if (preserveOriginal) {
                fromScreenBytes(decoded, source)
            } else {
                AgentModelImageEncoder.attachment(decoded, source, "image/jpeg", budget)
            }
        }.getOrNull()
    }

    private fun readLocalFile(
        file: File,
        source: String,
        preserveOriginal: Boolean,
        budget: ModelImageBudget,
    ): AgentModelClient.ModelImage? = runCatching {
        if (preserveOriginal) {
            fromBytes(file.readBytesLimited(MAX_SCREEN_IMAGE_BYTES), source).copy(preserveOriginal = true)
        } else {
            AgentModelImageEncoder.encodeFileForModel(file, source, budget = budget)
        }
    }.getOrNull()

    /**
     * 部分 ROM 的 Photo Picker 只实现 typed asset 或文件描述符读取。
     * 依次尝试标准流、typed asset 和文件描述符，流式暂存后再解码，避免选图失败或大原图 OOM。
     */
    private fun readContentUri(
        context: Context,
        uri: Uri,
        source: String,
        preserveOriginal: Boolean,
        budget: ModelImageBudget,
    ): AgentModelClient.ModelImage? {
        val tempDir = context.cacheDir
        val tempFile = runCatching { File.createTempFile("eta-image-read-", ".img", tempDir) }.getOrNull()
            ?: return null
        return try {
            copyContentUriToFile(context, uri, tempFile, tempDir) ?: return null
            val mimeHint = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            if (preserveOriginal) {
                runCatching {
                    fromBytes(tempFile.readBytesLimited(MAX_SCREEN_IMAGE_BYTES), source, mimeHint)
                        .copy(preserveOriginal = true)
                }.getOrNull()
            } else {
                runCatching {
                    AgentModelImageEncoder.encodeFileForModel(tempFile, source, mimeHint, budget)
                }.getOrNull()
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun copyContentUriToFile(
        context: Context,
        uri: Uri,
        destination: File,
        targetDirectory: File,
    ): Long? {
        val resolver = context.contentResolver
        val declaredSize = queryDeclaredContentSize(context, uri)
        val streamCandidates: List<() -> InputStream?> = listOf(
            { resolver.openInputStream(uri) },
            { resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.createInputStream() },
            { resolver.openFileDescriptor(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) } },
        )
        for (opener in streamCandidates) {
            try {
                val stream = opener() ?: continue
                stream.use { input ->
                    destination.outputStream().use { output ->
                        val copied = BoundedFileCopy.copyWithStorageReserve(
                            input = input,
                            output = output,
                            targetDirectory = targetDirectory,
                            declaredBytes = declaredSize,
                        )
                        if (copied > 0L) return copied
                    }
                }
            } catch (storage: BoundedFileCopy.InsufficientStorageException) {
                throw storage
            } catch (_: IOException) {
                continue
            } catch (_: SecurityException) {
                continue
            } catch (_: RuntimeException) {
                continue
            }
        }
        return null
    }

    private fun queryDeclaredContentSize(context: Context, uri: Uri): Long {
        val fromQuery = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            }
        }.getOrNull() ?: -1L
        if (fromQuery > 0L) return fromQuery
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
    }

    /**
     * 为跨进程请求解析图片。远程 URL 与已有 data URL 直接保留；本地 URI/路径只读取元数据，
     * 不再对原图体积设固定上限，正文由 [io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeImageTransfer]
     * 通过只读文件描述符零拷贝传输，并在接收端按目标渠道预算流式解码压缩。
     */
    fun fromTransferReference(
        context: Context?,
        value: String,
        source: String,
    ): AgentModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:image/", ignoreCase = true)
        ) {
            return fromReference(context, trimmed, source)
        }
        if (context == null) return fromReference(null, trimmed, source)

        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return inspectContentUri(context, uri, trimmed, source)
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return inspectFile(File(path), trimmed, source)
        }
        val file = File(trimmed)
        if (file.isFile) return inspectFile(file, trimmed, source)
        return fromReference(context, trimmed, source)
    }

    private fun inspectContentUri(
        context: Context,
        uri: Uri,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val length = queryDeclaredContentSize(context, uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
        val mime = options.outMimeType
            ?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: "image/*"
        AgentModelClient.ModelImage(
            reference = reference,
            mimeType = mime,
            bytes = length.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
            width = options.outWidth,
            height = options.outHeight,
            source = source,
        )
    }.getOrNull()

    private fun inspectFile(
        file: File,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val length = file.length()
        if (!file.isFile || length <= 0L) return@runCatching null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
        AgentModelClient.ModelImage(
            reference = reference,
            mimeType = options.outMimeType?.takeIf { it.isNotBlank() } ?: "image/*",
            bytes = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            width = options.outWidth,
            height = options.outHeight,
            source = source,
        )
    }.getOrNull()

    private fun inspectRecognizedImageFile(
        file: File,
        reference: String,
        source: String,
        mimeHint: String,
    ): AgentModelClient.ModelImage? {
        val length = file.length()
        if (!file.isFile || length <= 0L) return null
        val header = file.inputStream().use { input ->
            val buf = ByteArray(HEADER_MAGIC_BYTES)
            var offset = 0
            while (offset < buf.size) {
                val read = input.read(buf, offset, buf.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset == 0) null else buf.copyOf(offset)
        } ?: return null
        if (!header.hasSupportedImageMagic()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val mime = options.outMimeType
            ?.takeIf { it.isNotBlank() }
            ?: mimeHint.normalizedAgentImageMimeType()
        return AgentModelClient.ModelImage(
            reference = reference,
            mimeType = mime,
            bytes = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            width = options.outWidth,
            height = options.outHeight,
            source = source,
        )
    }

    private fun decodeDataUrlBytes(dataUrl: String): ByteArray? {
        val markerIndex = dataUrl.indexOf("base64,", ignoreCase = true)
        if (markerIndex < 0) return null
        val encoded = dataUrl.substring(markerIndex + "base64,".length)
        if (encoded.isBlank() || encoded.length > MAX_SCREEN_IMAGE_BYTES * 2) return null
        val raw = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > MAX_SCREEN_IMAGE_BYTES) return null
        return raw
    }

    private fun File.readBytesLimited(maxBytes: Int): ByteArray {
        require(length() <= maxBytes.toLong()) { "图片文件过大：${length()}" }
        return readBytes()
    }

    private fun String.looksLikeBase64(): Boolean {
        if (length < 64 || length > MAX_SCREEN_IMAGE_BYTES * 2) return false
        return all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' || it == '\r' }
    }

    private fun cleanupStaleStagedAttachments(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_STAGED_ATTACHMENT_AGE_MILLIS
        runCatching {
            directory.listFiles().orEmpty()
                .asSequence()
                .filter { file -> file.isFile && file.lastModified() < cutoff }
                .forEach { file -> file.delete() }
        }
    }
}
