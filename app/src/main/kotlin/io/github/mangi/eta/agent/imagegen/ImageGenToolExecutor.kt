package io.github.mangi.eta.agent.imagegen

import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ImageGenAspectRatio
import io.github.mangi.eta.data.model.ImageGenResolution
import io.github.mangi.eta.data.repository.ImageGenSettingsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行 `generate_image` 工具调用：
 * 1. 读取最新生图运行时配置并校验能力约束；
 * 2. 严格按当前会话可见历史解析授权用户附件、受管生成资产（按资产 ID 验证 canonical path）或公共相册图片；
 * 3. 调用对应渠道生图并在单一原子边界内整批发布到本地存储；
 * 4. 通过 `ToolResult.command` 回传结构化图片展示负载，同时给模型返回精简元信息（省 Token）。
 */
internal class ImageGenToolExecutor(
    context: Context,
    private val conversationId: String?,
    private val sessionKey: String,
    private val currentTurnUserImages: () -> List<AgentModelClient.ModelImage>,
    private val runController: AgentRunController,
) {
    private val appContext = context.applicationContext

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        runController.throwIfCancelled()
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse {
            return failure("INVALID_ARGUMENTS", "生图工具参数不是合法的 JSON")
        }
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) {
            return failure("EMPTY_PROMPT", "缺少 prompt 提示词参数")
        }
        val requestedRatio = ImageGenAspectRatio.fromWireValue(args.optString("aspect_ratio"))
        val requestedCount = args.optInt("count", 1).coerceIn(1, 4)

        val config = runBlocking { ImageGenSettingsRepository.resolveRuntimeConfig(appContext) }
        if (!config.enabled) {
            return failure("IMAGE_GEN_DISABLED", "AI 生图功能尚未启用，请前往「设置 → AI 生图」开启")
        }
        if (config.apiKey.isBlank()) {
            return failure("IMAGE_GEN_MISSING_API_KEY", "AI 生图尚未配置 API Key，请前往「设置 → AI 生图」填写或关联已有服务商")
        }

        val capability = config.capability
        val rawResolution = args.optString("resolution").trim()
            .takeIf { it.isNotEmpty() }
            ?.let(ImageGenResolution::fromWireValue)
            ?: config.effectiveDefaultResolution
        val resolution = capability.clampResolution(rawResolution)
        val aspectRatio = if (requestedRatio == ImageGenAspectRatio.AUTO) config.defaultAspectRatio else requestedRatio

        val rawRefs = args.optJSONArray("reference_images")
        val refList = try {
            resolveReferenceImages(rawRefs, capability)
        } catch (error: ImageGenException) {
            return failure(
                code = error.code,
                message = error.message,
                payload = GeneratedImageActivityPayload(
                    prompt = prompt.take(600),
                    aspectRatio = aspectRatio.wireValue,
                    resolution = resolution.wireValue,
                    model = config.model,
                    backend = config.backend.wireValue,
                    requestedCount = requestedCount,
                    referenceCount = rawRefs?.length() ?: 0,
                    errorMessage = error.message,
                ),
            )
        }

        return try {
            runController.throwIfCancelled()
            val generated = ImageGenClient.generate(
                config = config,
                request = ImageGenRequest(
                    prompt = prompt,
                    aspectRatio = aspectRatio,
                    resolution = resolution,
                    count = requestedCount,
                    referenceImages = refList,
                ),
                runController = runController,
            )
            runController.throwIfCancelled()
            val savedRecords = GeneratedImageStore.saveGeneratedImagesBatch(
                context = appContext,
                inputs = generated.map { output ->
                    GeneratedImageBatchInput(
                        bytes = output.bytes,
                        mimeType = output.mimeType,
                        prompt = output.revisedPrompt ?: prompt,
                        model = config.model,
                        backend = config.backend.wireValue,
                    )
                },
                sessionKey = sessionKey,
                beforeCommit = { runController.throwIfCancelled() },
            )
            val payload = GeneratedImageActivityPayload(
                prompt = prompt.take(600),
                aspectRatio = aspectRatio.wireValue,
                resolution = resolution.wireValue,
                model = config.model,
                backend = config.backend.wireValue,
                requestedCount = requestedCount,
                referenceCount = refList.size,
                images = savedRecords.map { rec ->
                    GeneratedImageActivityPayload.GeneratedImageItem(
                        id = rec.id,
                        filePath = rec.filePath,
                        mimeType = rec.mimeType,
                        width = rec.width,
                        height = rec.height,
                    )
                },
            )
            val imagesJson = JSONArray()
            savedRecords.forEach { rec ->
                imagesJson.put(
                    JSONObject()
                        .put("id", rec.id)
                        .put("width", rec.width)
                        .put("height", rec.height)
                        .put("mime_type", rec.mimeType),
                )
            }
            val resultJson = JSONObject()
                .put("ok", true)
                .put("count", savedRecords.size)
                .put("model", config.model)
                .put("backend", config.backend.wireValue)
                .put("images", imagesJson)
                .put("display_note", "图片已直接渲染在用户聊天界面中，无需输出 Markdown 图片链接或本地路径；如需继续改图请传 reference_images=[\"latest_generated_image\"] 或具体图片 id。")
                .toString()
            AgentModelClient.ToolResult(
                content = resultJson,
                command = payload.encode(),
            )
        } catch (error: ImageGenException) {
            AndroidAgentLogger.warn("generate_image failed: code=${error.code}, http=${error.httpCode}")
            failure(
                code = error.code,
                message = error.message,
                payload = GeneratedImageActivityPayload(
                    prompt = prompt.take(600),
                    aspectRatio = aspectRatio.wireValue,
                    resolution = resolution.wireValue,
                    model = config.model,
                    backend = config.backend.wireValue,
                    requestedCount = requestedCount,
                    referenceCount = refList.size,
                    errorMessage = error.message,
                ),
            )
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AndroidAgentLogger.warn("generate_image unexpected error: type=${throwable.safeLogType()}")
            val msg = "生图执行失败（${throwable.javaClass.simpleName}）"
            failure(
                code = "IMAGE_GEN_ERROR",
                message = msg,
                payload = GeneratedImageActivityPayload(
                    prompt = prompt.take(600),
                    aspectRatio = aspectRatio.wireValue,
                    resolution = resolution.wireValue,
                    model = config.model,
                    backend = config.backend.wireValue,
                    requestedCount = requestedCount,
                    referenceCount = refList.size,
                    errorMessage = msg,
                ),
            )
        }
    }

    private fun resolveReferenceImages(
        array: JSONArray?,
        capability: ImageGenModelCapability,
    ): List<ImageGenInputImage> {
        if (array == null || array.length() == 0) return emptyList()
        if (!capability.supportsEdit) {
            throw ImageGenException("IMAGE_GEN_EDIT_UNSUPPORTED", "当前生图模型不支持图生图编辑，无法使用参考图")
        }
        val resolved = mutableListOf<ImageGenInputImage>()
        for (i in 0 until array.length()) {
            runController.throwIfCancelled()
            val raw = array.optString(i).trim()
            if (raw.isEmpty()) continue
            if (raw.startsWith("data:", ignoreCase = true)) {
                throw ImageGenException("UNSUPPORTED_REFERENCE_FORMAT", "参考图请传 latest_user_image、latest_generated_image、图片 ID 或有效本地路径，不要内联 data URL")
            }
            when (raw.lowercase()) {
                "latest_user_image", "user_image", "latest_user_images" -> {
                    val userImgs = loadLatestConversationUserImages()
                    if (userImgs.isEmpty()) {
                        throw ImageGenException("REFERENCE_IMAGE_NOT_FOUND", "当前会话中未找到用户发送的图片，请先发送图片后再编辑")
                    }
                    resolved += userImgs
                }
                "latest_generated_image", "previous_generated_image", "last_generated_image" -> {
                    val record = loadLatestConversationGeneratedRecord()
                        ?: throw ImageGenException("REFERENCE_IMAGE_NOT_FOUND", "当前会话中未找到最近生成的图片，请先生成图片或指定图片 ID")
                    resolved += decodeAllowedLocalImage(record.filePath)
                }
                else -> {
                    val targetPath = if (raw.startsWith("img_")) {
                        GeneratedImageStore.findById(appContext, raw)?.filePath
                            ?: throw ImageGenException("REFERENCE_IMAGE_NOT_FOUND", "找不到指定的生成图片资产（$raw）")
                    } else {
                        raw
                    }
                    resolved += decodeAllowedLocalImage(targetPath)
                }
            }
            if (resolved.size > capability.maxReferenceImages) {
                throw ImageGenException(
                    "TOO_MANY_REFERENCE_IMAGES",
                    "当前生图模型最多支持 ${capability.maxReferenceImages} 张参考图，请减少参考图数量",
                )
            }
        }
        if (resolved.isEmpty()) {
            throw ImageGenException("REFERENCE_IMAGE_NOT_FOUND", "未能解析到有效的参考图片")
        }
        return resolved
    }

    /** 优先取本轮用户主动附的图，否则定向查询当前会话 Room 中最近一条含图用户消息，不限消息条数。 */
    private fun loadLatestConversationUserImages(): List<ImageGenInputImage> {
        val currentTurn = currentTurnUserImages()
        if (currentTurn.isNotEmpty()) {
            return currentTurn.mapNotNull { attachment ->
                val ref = attachment.reference.trim()
                val decoded = if (ref.startsWith("data:image/", ignoreCase = true)) {
                    GeneratedImageStore.decodeSourceBytes(ref)
                } else {
                    null
                }
                if (decoded != null) {
                    ImageGenClient.prepareReferenceImage(decoded.first, decoded.second)
                } else {
                    runCatching { decodeAllowedLocalImage(ref) }.getOrNull()
                }
            }
        }
        val convId = conversationId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val latestUserWithImages = runBlocking {
            runCatching {
                EtaDatabase.get(appContext).conversationDao().latestUserMessageWithImages(convId)
            }.getOrNull()
        } ?: return emptyList()
        val arr = runCatching { JSONArray(latestUserWithImages.imagesJson) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ImageGenInputImage>()
        for (i in 0 until arr.length()) {
            runController.throwIfCancelled()
            val ref = arr.optString(i).trim()
            if (ref.isEmpty()) continue
            if (ref.startsWith("data:image/", ignoreCase = true)) {
                val decoded = GeneratedImageStore.decodeSourceBytes(ref) ?: continue
                out += ImageGenClient.prepareReferenceImage(decoded.first, decoded.second)
            } else {
                // 防止备份导入伪造私有目录路径：非 data URL 历史引用必须经过白名单路径校验。
                runCatching { decodeAllowedLocalImage(ref) }.getOrNull()?.let { out += it }
            }
        }
        return out
    }

    /**
     * 解析当前会话最近生成的受管资产：
     * - 主聊天会话（有 conversationId）直接以 Room 当前可见历史为唯一事实源，避免重生成裁剪后命中过期缓存；
     * - 无 Room 会话 ID 的入口（助手浮层/定时任务）按稳定 sessionKey 查找；
     * - 始终通过资产 ID 走 `GeneratedImageStore.findById` 校验 canonical path，不信任载荷里的任意路径字符串。
     */
    private fun loadLatestConversationGeneratedRecord(): GeneratedImageRecord? {
        val convId = conversationId?.takeIf { it.isNotBlank() }
        if (convId != null) {
            val latestTool = runBlocking {
                runCatching {
                    EtaDatabase.get(appContext).conversationDao()
                        .latestSuccessfulToolMessage(convId, ImageGenToolCatalog.TOOL_NAME)
                }.getOrNull()
            } ?: return null
            val payload = GeneratedImageActivityPayload.decode(latestTool.content) ?: return null
            for (item in payload.images.asReversed()) {
                val managed = GeneratedImageStore.findById(appContext, item.id)
                if (managed != null) return managed
            }
            return null
        }
        val sessionLatestId = GeneratedImageStore.latestGeneratedId(sessionKey) ?: return null
        return GeneratedImageStore.findById(appContext, sessionLatestId)
    }

    /** 每次读取均验证 canonical path：仅允许受管生成图目录或公共外部存储里的合法图片文件。 */
    private fun decodeAllowedLocalImage(path: String): ImageGenInputImage {
        val file = File(path.removePrefix("file://").trim())
        if (!file.isFile || !file.canRead()) {
            throw ImageGenException("REFERENCE_IMAGE_NOT_FOUND", "找不到指定的参考图片文件")
        }
        val canonical = runCatching { file.canonicalFile }.getOrNull()
            ?: throw ImageGenException("REFERENCE_IMAGE_NOT_FOUND", "参考图片路径无效")
        val managed = GeneratedImageStore.isManagedGeneratedFile(appContext, canonical)
        val inPublicStorage = isPublicSharedStoragePath(canonical.path)
        if (!managed && !inPublicStorage) {
            throw ImageGenException("REFERENCE_PATH_FORBIDDEN", "出于隐私保护，不允许将应用私有目录文件作为参考图上传")
        }
        if (canonical.length() <= 0L || canonical.length() > MAX_LOCAL_REF_BYTES) {
            throw ImageGenException("REFERENCE_IMAGE_TOO_LARGE", "参考图片体积超出限制（最大 20MB）")
        }
        val bytes = runCatching { canonical.readBytes() }.getOrNull()
            ?: throw ImageGenException("REFERENCE_IMAGE_UNREADABLE", "读取参考图片失败")
        return ImageGenClient.prepareReferenceImage(bytes, "image/png")
    }

    private fun isPublicSharedStoragePath(canonicalPath: String): Boolean {
        val normalized = canonicalPath.trim()
        if (normalized.startsWith("/data/") || normalized.startsWith("/proc/") || normalized.startsWith("/sys/")) {
            return false
        }
        return normalized.startsWith("/storage/emulated/") ||
            normalized.startsWith("/sdcard/") ||
            normalized.startsWith("/mnt/sdcard/")
    }

    private fun failure(
        code: String,
        message: String,
        payload: GeneratedImageActivityPayload? = null,
    ): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        content = JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString(),
        command = payload?.encode(),
    )

    companion object {
        private const val MAX_LOCAL_REF_BYTES = 20L * 1024L * 1024L
    }
}
