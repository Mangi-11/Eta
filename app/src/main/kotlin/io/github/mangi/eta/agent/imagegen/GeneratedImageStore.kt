package io.github.mangi.eta.agent.imagegen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.util.AtomicFile
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class GeneratedImageRecord(
    val id: String,
    val filePath: String,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0L,
    val prompt: String = "",
    val model: String = "",
    val backend: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

internal data class GeneratedImageBatchInput(
    val bytes: ByteArray,
    val mimeType: String,
    val prompt: String,
    val model: String,
    val backend: String,
)

/**
 * 管理 AI 生图落盘原图、元数据索引、相册导出与系统分享。
 *
 * 多张生成结果先全部写入临时文件，再在 `indexLock` 内整批原子 rename + `AtomicFile` 提交索引，
 * 避免与「清空」/「删除」或取消交错产生半截入库或悬空卡片。
 */
internal object GeneratedImageStore {
    private const val DIR_NAME = "generated_images"
    private const val SHARED_CACHE_DIR = "shared_images"
    private const val INDEX_FILE = "index.json"
    private const val MAX_INDEX_RECORDS = 500
    private val indexLock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 仅用于无 Room conversationId 的入口（例如助手浮层、定时任务）的最近生成图追踪。 */
    private val latestBySession = ConcurrentHashMap<String, String>()

    fun storageDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun isManagedGeneratedFile(context: Context, file: File): Boolean = runCatching {
        val root = storageDir(context).canonicalFile
        val target = file.canonicalFile
        target.isFile &&
            target.path.startsWith(root.path + File.separator) &&
            target.name != INDEX_FILE &&
            !target.name.endsWith(".tmp") &&
            !target.name.endsWith(".bak")
    }.getOrDefault(false)

    fun saveGeneratedImage(
        context: Context,
        bytes: ByteArray,
        mimeType: String,
        prompt: String,
        model: String,
        backend: String,
        sessionKey: String? = null,
    ): GeneratedImageRecord = saveGeneratedImagesBatch(
        context = context,
        inputs = listOf(GeneratedImageBatchInput(bytes, mimeType, prompt, model, backend)),
        sessionKey = sessionKey,
    ).single()

    /**
     * 整批原子提交：先将全部图片写到临时文件，再在单一锁边界内一次性发布原图并原子更新索引；
     * 任一环节失败或取消时完整回滚并清理所有临时与已移动文件。
     */
    fun saveGeneratedImagesBatch(
        context: Context,
        inputs: List<GeneratedImageBatchInput>,
        sessionKey: String? = null,
        beforeCommit: () -> Unit = {},
    ): List<GeneratedImageRecord> {
        if (inputs.isEmpty()) return emptyList()
        val dir = storageDir(context)
        val staged = mutableListOf<Triple<File, File, GeneratedImageRecord>>()
        val committedFiles = mutableListOf<File>()
        try {
            inputs.forEach { input ->
                beforeCommit()
                val normalizedMime = normalizeMimeType(input.mimeType, input.bytes)
                val ext = extensionForMime(normalizedMime)
                val id = "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                val tempFile = File(dir, ".$id.tmp")
                val targetFile = File(dir, "$id.$ext")
                tempFile.writeBytes(input.bytes)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size, bounds)
                val record = GeneratedImageRecord(
                    id = id,
                    filePath = targetFile.absolutePath,
                    mimeType = normalizedMime,
                    width = bounds.outWidth.coerceAtLeast(0),
                    height = bounds.outHeight.coerceAtLeast(0),
                    bytes = input.bytes.size.toLong(),
                    prompt = input.prompt.trim().take(1200),
                    model = input.model.trim(),
                    backend = input.backend.trim(),
                    createdAt = System.currentTimeMillis(),
                )
                staged += Triple(tempFile, targetFile, record)
            }

            synchronized(indexLock) {
                beforeCommit()
                if (!dir.exists()) dir.mkdirs()
                staged.forEach { (tempFile, targetFile, _) ->
                    if (!tempFile.renameTo(targetFile)) {
                        throw IOException(
                            AppStrings.get(
                                R.string.imagegen_store_error_publish_failed,
                                "无法原子发布生成图片：%1\$s",
                                targetFile.name,
                            ),
                        )
                    }
                    committedFiles += targetFile
                }
                val newRecords = staged.map { it.third }
                val newIds = newRecords.mapTo(HashSet()) { it.id }
                val updated = (newRecords.asReversed() + readIndexLocked(dir).filterNot { it.id in newIds })
                    .take(MAX_INDEX_RECORDS)
                writeIndexAtomicLocked(dir, updated)
                if (!sessionKey.isNullOrBlank()) {
                    latestBySession[sessionKey] = newRecords.last().id
                }
                return newRecords
            }
        } catch (throwable: Throwable) {
            staged.forEach { (tempFile, _, _) -> runCatching { if (tempFile.exists()) tempFile.delete() } }
            committedFiles.forEach { file -> runCatching { if (file.exists()) file.delete() } }
            if (throwable is RuntimeException) throw throwable
            throw IOException(
                AppStrings.get(R.string.imagegen_store_error_save_local_failed, "保存生成图片到本地失败"),
                throwable,
            )
        }
    }

    fun latestGeneratedId(sessionKey: String?): String? {
        if (sessionKey.isNullOrBlank()) return null
        return latestBySession[sessionKey]
    }

    fun listRecords(context: Context): List<GeneratedImageRecord> {
        val dir = storageDir(context)
        return synchronized(indexLock) {
            val indexed = readIndexLocked(dir).filter { File(it.filePath).isFile }
            val knownPaths = indexed.mapTo(HashSet()) { it.filePath }
            val orphanFiles = dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name != INDEX_FILE &&
                        !it.name.endsWith(".tmp") &&
                        !it.name.endsWith(".bak") &&
                        !it.name.endsWith(".new") &&
                        it.absolutePath !in knownPaths
                }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
                .map { file ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
                    GeneratedImageRecord(
                        id = file.nameWithoutExtension,
                        filePath = file.absolutePath,
                        mimeType = mimeForExtension(file.extension),
                        width = bounds.outWidth.coerceAtLeast(0),
                        height = bounds.outHeight.coerceAtLeast(0),
                        bytes = file.length(),
                        createdAt = file.lastModified(),
                    )
                }
            (indexed + orphanFiles).sortedByDescending { it.createdAt }
        }
    }

    fun findById(context: Context, id: String): GeneratedImageRecord? {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return null
        val rec = listRecords(context).firstOrNull { it.id == trimmed } ?: return null
        return rec.takeIf { isManagedGeneratedFile(context, File(it.filePath)) }
    }

    fun deleteRecord(context: Context, filePath: String): Boolean {
        val dir = storageDir(context)
        return synchronized(indexLock) {
            val file = File(filePath)
            val deleted = if (file.exists()) file.delete() else true
            if (!deleted) return@synchronized false
            val removedIds = readIndexLocked(dir).filter { it.filePath == filePath }.mapTo(HashSet()) { it.id }
            val remaining = readIndexLocked(dir).filterNot { it.filePath == filePath }
            runCatching { writeIndexAtomicLocked(dir, remaining) }.isSuccess.also { ok ->
                if (ok) latestBySession.entries.removeIf { it.value in removedIds }
            }
        }
    }

    fun clearAll(context: Context): Int {
        val dir = storageDir(context)
        return synchronized(indexLock) {
            var removed = 0
            dir.listFiles()?.forEach { file ->
                if (file.isFile &&
                    file.name != INDEX_FILE &&
                    !file.name.endsWith(".tmp") &&
                    !file.name.endsWith(".bak") &&
                    !file.name.endsWith(".new") &&
                    file.delete()
                ) {
                    removed += 1
                }
            }
            val survivors = dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name != INDEX_FILE &&
                        !it.name.endsWith(".tmp") &&
                        !it.name.endsWith(".bak") &&
                        !it.name.endsWith(".new")
                }
                ?.mapTo(HashSet()) { it.absolutePath }
                .orEmpty()
            val remaining = readIndexLocked(dir).filter { it.filePath in survivors }
            val survivorIds = remaining.mapTo(HashSet()) { it.id }
            runCatching { writeIndexAtomicLocked(dir, remaining) }
            latestBySession.entries.removeIf { it.value !in survivorIds }
            removed
        }
    }

    fun totalStorageBytes(context: Context): Long {
        val dir = storageDir(context)
        return synchronized(indexLock) {
            dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name != INDEX_FILE &&
                        !it.name.endsWith(".tmp") &&
                        !it.name.endsWith(".bak") &&
                        !it.name.endsWith(".new")
                }
                ?.sumOf { it.length() }
                ?: 0L
        }
    }

    fun saveToSystemGallery(context: Context, source: String): Result<Uri> = runCatching {
        val (bytes, mimeType) = decodeSourceBytes(source)
            ?: error(AppStrings.get(R.string.imagegen_store_error_read_image_failed, "无法读取图片数据"))
        val ext = extensionForMime(mimeType)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "Eta_${timestamp}_${UUID.randomUUID().toString().take(4)}.$ext"
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Eta")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error(AppStrings.get(R.string.imagegen_store_error_gallery_write_failed, "无法写入系统相册"))
        try {
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: error(AppStrings.get(R.string.imagegen_store_error_gallery_stream_failed, "无法打开相册输出流"))
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            val updatedRows = resolver.update(uri, values, null, null)
            if (updatedRows <= 0) {
                error(AppStrings.get(R.string.imagegen_store_error_gallery_publish_failed, "相册发布状态更新失败"))
            }
            uri
        } catch (throwable: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw throwable
        }
    }

    fun shareImage(context: Context, source: String): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val authority = "${appContext.packageName}.fileprovider"
        val (shareFile, mimeType) = prepareShareableFile(appContext, source)
            ?: error(AppStrings.get(R.string.imagegen_store_error_share_prepare_failed, "无法准备待分享图片"))
        val contentUri = FileProvider.getUriForFile(appContext, authority, shareFile)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            context.getString(R.string.imagegen_share_chooser_title),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }.onFailure { throwable ->
        AndroidAgentLogger.warn("GeneratedImageStore share failed: type=${throwable.safeLogType()}")
    }

    private fun prepareShareableFile(context: Context, source: String): Pair<File, String>? {
        val rawPath = source.removePrefix("file://")
        if (!source.startsWith("data:", ignoreCase = true)) {
            val file = File(rawPath)
            if (isManagedGeneratedFile(context, file)) {
                return file.canonicalFile to mimeForExtension(file.extension)
            }
        }
        val (bytes, mimeType) = decodeSourceBytes(source) ?: return null
        val cacheDir = File(context.cacheDir, SHARED_CACHE_DIR).apply { mkdirs() }
        cacheDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(6)
            ?.forEach { runCatching { it.delete() } }
        val ext = extensionForMime(mimeType)
        val tempFile = File(cacheDir, "share_${System.currentTimeMillis()}.$ext")
        tempFile.writeBytes(bytes)
        return tempFile to mimeType
    }

    internal fun decodeSourceBytes(source: String): Pair<ByteArray, String>? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val mime = trimmed.substringAfter("data:").substringBefore(';').trim()
                .takeIf { it.startsWith("image/") } ?: "image/png"
            val base64 = trimmed.substringAfter("base64,", "")
            if (base64.isBlank()) return null
            val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                .getOrNull() ?: return null
            return bytes to normalizeMimeType(mime, bytes)
        }
        val file = File(trimmed.removePrefix("file://"))
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes to normalizeMimeType(mimeForExtension(file.extension), bytes)
    }

    internal fun normalizeMimeType(mimeType: String, bytes: ByteArray): String {
        val sniffed = sniffMimeType(bytes)
        if (sniffed != null) return sniffed
        val lower = mimeType.trim().lowercase()
        return when {
            lower == "image/jpg" -> "image/jpeg"
            lower.startsWith("image/") -> lower
            else -> "image/png"
        }
    }

    private fun sniffMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) return "image/jpeg"
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return "image/webp"
        return null
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun readIndexLocked(dir: File): List<GeneratedImageRecord> {
        val atomic = AtomicFile(File(dir, INDEX_FILE))
        if (!atomic.baseFile.isFile && !File(dir, "$INDEX_FILE.bak").isFile) return emptyList()
        return runCatching {
            val bytes = atomic.readFully()
            json.decodeFromString<List<GeneratedImageRecord>>(bytes.toString(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeIndexAtomicLocked(dir: File, records: List<GeneratedImageRecord>) {
        val atomic = AtomicFile(File(dir, INDEX_FILE))
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(records).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (throwable: Throwable) {
            atomic.failWrite(out)
            throw throwable
        }
    }
}
