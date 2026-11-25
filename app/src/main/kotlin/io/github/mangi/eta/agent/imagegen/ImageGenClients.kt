package io.github.mangi.eta.agent.imagegen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.agent.model.provider.CustomHeaderFilter
import io.github.mangi.eta.agent.model.provider.ProviderUrls
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ImageGenAspectRatio
import io.github.mangi.eta.data.model.ImageGenBackend
import io.github.mangi.eta.data.model.ImageGenResolution
import io.github.mangi.eta.data.repository.ImageGenSettingsRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class ImageGenInputImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    val aspectRatio: Float?
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null

    fun toBase64(): String = Base64.getEncoder().encodeToString(bytes)
    fun toDataUrl(): String = "data:$mimeType;base64,${toBase64()}"
}

internal data class ImageGenRequest(
    val prompt: String,
    val aspectRatio: ImageGenAspectRatio = ImageGenAspectRatio.AUTO,
    val resolution: ImageGenResolution = ImageGenResolution.R_1K,
    val count: Int = 1,
    val referenceImages: List<ImageGenInputImage> = emptyList(),
)

internal data class ImageGenOutputImage(
    val bytes: ByteArray,
    val mimeType: String,
    val revisedPrompt: String? = null,
)

internal class ImageGenException(
    val code: String,
    override val message: String,
    val httpCode: Int? = null,
) : RuntimeException(message)

/**
 * 统一 AI 生图协议分发层。
 *
 * 支持 6 条独立清晰的官方协议通道：
 * - Google Gemini (Nano Banana): AI Studio Interactions API（steps[].model_output.content[]）与 Vertex AI generateContent
 * - OpenAI 及兼容生态: /images/generations 与 /images/edits
 * - xAI (Grok Imagine): /images/generations 与 JSON /images/edits（含 aspect_ratio 与 resolution）
 * - 豆包（火山方舟 Seedream）: /images/generations（文生图与多图参考同接口）
 * - 千问（阿里云百炼 Qwen-Image 2.0）: DashScope 同步 multimodal-generation 接口
 * - 硅基流动（SiliconFlow）: /images/generations（image_size + images[].url）
 */
internal object ImageGenClient {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val GEMINI_API_REVISION = "2026-05-20"
    private const val IMAGE_READ_TIMEOUT_MS = 180_000L
    private const val MAX_ERROR_BODY_BYTES = 16 * 1024
    private const val MAX_JSON_RESPONSE_BYTES = 48 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
    private const val MAX_BATCH_OUTPUT_BYTES = 48 * 1024 * 1024
    private const val MAX_REF_IMAGE_DIM = 2048
    private const val MAX_REF_IMAGE_BYTES = 4 * 1024 * 1024
    private val EXTRA_BLOCKED_HEADERS = setOf("api-revision", "content-type")
    private val SAFE_TOKEN_REGEX = Regex("[A-Za-z0-9._:-]{1,48}")

    /** 认证生图请求禁止任何重定向，防止跨源或降级转发 x-goog-api-key、自定义 Header 与 POST 正文。 */
    internal val authHttpClient: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .readTimeout(IMAGE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(60_000L, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 独立的无认证图片下载客户端，禁止 HTTPS 降级到 HTTP。 */
    internal val downloadHttpClient: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .readTimeout(IMAGE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun generate(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController? = null,
        httpClient: OkHttpClient = authHttpClient,
        imageDownloadClient: OkHttpClient = if (httpClient === authHttpClient) downloadHttpClient else httpClient,
    ): List<ImageGenOutputImage> {
        runController?.throwIfCancelled()
        if (config.apiKey.isBlank()) {
            throw ImageGenException(
                "IMAGE_GEN_MISSING_API_KEY",
                AppStrings.get(
                    R.string.imagegen_error_missing_api_key,
                    "未配置 AI 生图 API Key，请前往「设置 → AI 生图」填写或关联已有服务商",
                ),
            )
        }
        if (config.model.isBlank()) {
            throw ImageGenException(
                "IMAGE_GEN_MISSING_MODEL",
                AppStrings.get(
                    R.string.imagegen_error_missing_model,
                    "未配置生图模型 ID，请前往「设置 → AI 生图」检查模型设置",
                ),
            )
        }
        ImageGenSettingsRepository.validateEndpointUrl(config.baseUrl)?.let { err ->
            throw ImageGenException("IMAGE_GEN_INVALID_URL", err)
        }
        val normalizedPrompt = request.prompt.trim()
        if (normalizedPrompt.isEmpty()) {
            throw ImageGenException(
                "IMAGE_GEN_EMPTY_PROMPT",
                AppStrings.get(R.string.imagegen_error_empty_prompt, "生图提示词不能为空"),
            )
        }

        val capability = config.capability
        if (request.referenceImages.isNotEmpty() && !capability.supportsEdit) {
            throw ImageGenException(
                "IMAGE_GEN_EDIT_UNSUPPORTED",
                AppStrings.get(
                    R.string.imagegen_error_edit_unsupported,
                    "当前生图模型（%1\$s）不支持图生图编辑，请切换支持图像编辑的模型",
                    config.model,
                ),
            )
        }
        if (request.referenceImages.size > capability.maxReferenceImages) {
            throw ImageGenException(
                "TOO_MANY_REFERENCE_IMAGES",
                AppStrings.get(
                    R.string.imagegen_error_too_many_reference_images,
                    "当前模型（%1\$s）最多支持 %2\$d 张参考图，本次传入了 %3\$d 张",
                    config.model,
                    capability.maxReferenceImages,
                    request.referenceImages.size,
                ),
            )
        }

        val normalizedRequest = request.copy(
            prompt = normalizedPrompt,
            count = request.count.coerceIn(1, 4),
            aspectRatio = if (request.aspectRatio == ImageGenAspectRatio.AUTO) config.defaultAspectRatio else request.aspectRatio,
            resolution = capability.clampResolution(request.resolution),
        )
        return when (config.backend) {
            ImageGenBackend.GEMINI -> generateGemini(config, normalizedRequest, runController, httpClient)
            ImageGenBackend.OPENAI_COMPATIBLE -> generateOpenAiCompatible(config, normalizedRequest, runController, httpClient, imageDownloadClient)
            ImageGenBackend.XAI -> generateXai(config, normalizedRequest, runController, httpClient, imageDownloadClient)
            ImageGenBackend.DOUBAO -> generateDoubao(config, normalizedRequest, runController, httpClient, imageDownloadClient)
            ImageGenBackend.QWEN -> generateQwen(config, normalizedRequest, runController, httpClient, imageDownloadClient)
            ImageGenBackend.SILICONFLOW -> generateSiliconFlow(config, normalizedRequest, runController, httpClient, imageDownloadClient)
        }
    }

    // -------------------------------------------------------------------------
    // 1. Google Gemini (Nano Banana) — AI Studio Interactions / Vertex AI
    // -------------------------------------------------------------------------

    private fun generateGemini(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val useVertex = GeminiEndpointMode.normalize(config.geminiEndpointMode) == GeminiEndpointMode.VERTEX_AI
        val results = mutableListOf<ImageGenOutputImage>()
        var totalBytes = 0L
        repeat(request.count) {
            runController?.throwIfCancelled()
            val batch = if (useVertex) {
                callGeminiVertex(config, request, runController, httpClient)
            } else {
                callGeminiAiStudioInteractions(config, request, runController, httpClient)
            }
            totalBytes = accumulateBatchBudget(totalBytes, batch)
            results += batch
        }
        if (results.isEmpty()) {
            throw ImageGenException(
                "IMAGE_GEN_EMPTY_OUTPUT",
                AppStrings.get(
                    R.string.imagegen_error_gemini_empty_output,
                    "Gemini 未返回图像数据，请检查模型是否支持生图或调整提示词",
                ),
            )
        }
        return results
    }

    internal fun buildGeminiInteractionsBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val capability = config.capability
        val inputs = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", request.prompt))
            request.referenceImages.forEach { ref ->
                put(
                    JSONObject()
                        .put("type", "image")
                        .put("mime_type", ref.mimeType)
                        .put("data", ref.toBase64()),
                )
            }
        }
        val responseFormat = JSONObject().put("type", "image")
        if (capability.sendsResolutionParam) {
            responseFormat.put("image_size", capability.clampResolution(request.resolution).wireValue)
        }
        if (request.aspectRatio != ImageGenAspectRatio.AUTO) {
            responseFormat.put("aspect_ratio", request.aspectRatio.wireValue)
        }
        return JSONObject()
            .put("model", config.model)
            .put("input", inputs)
            .put("response_format", responseFormat)
            // 无状态单轮生图不依赖 previous_interaction_id，显式关闭服务端交互留存。
            .put("store", false)
    }

    internal fun buildGeminiVertexBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val capability = config.capability
        val parts = JSONArray().apply {
            put(JSONObject().put("text", request.prompt))
            request.referenceImages.forEach { ref ->
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", ref.mimeType)
                            .put("data", ref.toBase64()),
                    ),
                )
            }
        }
        val imageConfig = JSONObject()
        if (capability.sendsResolutionParam) {
            imageConfig.put("imageSize", capability.clampResolution(request.resolution).wireValue)
        }
        if (request.aspectRatio != ImageGenAspectRatio.AUTO) {
            imageConfig.put("aspectRatio", request.aspectRatio.wireValue)
        }
        val genConfig = JSONObject()
            .put("responseModalities", JSONArray().put("IMAGE").put("TEXT"))
        if (imageConfig.length() > 0) {
            genConfig.put("imageConfig", imageConfig)
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", genConfig)
    }

    /** Vertex Express 模式要求走官方全局主机且无 project/location 路径，构造时即规范化。 */
    internal fun resolveVertexImageUrl(config: ImageGenRuntimeConfig): String {
        val trimmedProject = config.vertexProject.trim()
        val effectiveBaseUrl = if (trimmedProject.isEmpty() && ProviderUrls.isOfficialVertexBaseUrl(config.baseUrl)) {
            GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL
        } else {
            config.baseUrl
        }
        return ProviderUrls.vertexGenerateContentUrl(
            baseUrl = effectiveBaseUrl,
            model = config.model,
            project = trimmedProject,
            location = config.vertexLocation,
        )
    }

    private fun callGeminiAiStudioInteractions(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val url = ProviderUrls.geminiInteractionsUrl(config.baseUrl)
        val bodyJson = buildGeminiInteractionsBody(config, request).toString()
        val httpReq = Request.Builder()
            .url(url)
            .applyCustomHeaders(config)
            .header("x-goog-api-key", config.apiKey)
            .header("Api-Revision", GEMINI_API_REVISION)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = executeForJsonBody(httpReq, "Gemini", runController, httpClient)
        return parseGeminiInteractionsResponse(responseText)
    }

    private fun callGeminiVertex(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val url = resolveVertexImageUrl(config)
        val bodyJson = buildGeminiVertexBody(config, request).toString()
        val httpReq = Request.Builder()
            .url(url)
            .applyCustomHeaders(config)
            .header("x-goog-api-key", config.apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = executeForJsonBody(httpReq, "Vertex AI", runController, httpClient)
        return parseGeminiGenerateContentResponse(responseText)
    }

    /**
     * 解析 Gemini Interactions REST 响应：
     * 1. 官方 REST 主结构：`steps[]` 中 `type == "model_output"` 的 `content[]`（排除 thought 思考步骤/图像）；
     * 2. 兼容旧版或代理直出的 `outputs[]`（排除 thought）；
     * 3. 兼容 SDK / 代理顶层 `output_image`；
     * 4. 兜底兼容 `generateContent` 的 `candidates[]`。
     */
    internal fun parseGeminiInteractionsResponse(rawJson: String): List<ImageGenOutputImage> {
        val root = JSONObject(rawJson)
        val outputs = mutableListOf<ImageGenOutputImage>()

        fun extractImageObj(obj: JSONObject?) {
            if (obj == null) return
            if (obj.optBoolean("thought", false)) return
            val data = obj.optString("data").trim()
            if (data.isEmpty()) return
            val mime = obj.optString("mime_type").ifBlank { obj.optString("mimeType", "image/png") }
            val bytes = decodeBase64Safely(data) ?: return
            outputs += ImageGenOutputImage(
                bytes = bytes,
                mimeType = GeneratedImageStore.normalizeMimeType(mime, bytes),
            )
        }

        fun extractFromSteps(steps: JSONArray?) {
            if (steps == null) return
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optBoolean("thought", false)) continue
                val stepType = step.optString("type").trim().lowercase()
                if (stepType == "thought" || stepType == "thinking") continue
                // 官方 REST 成品结构：step.type == "model_output" 且成品在 step.content[] 内。
                val content = step.optJSONArray("content")
                if (content != null) {
                    if (stepType.isEmpty() || stepType == "model_output" || stepType == "output") {
                        for (j in 0 until content.length()) {
                            val item = content.optJSONObject(j) ?: continue
                            if (item.optBoolean("thought", false)) continue
                            val itemType = item.optString("type").trim().lowercase()
                            if (itemType == "image") {
                                extractImageObj(item)
                            }
                        }
                    }
                } else if (stepType == "image") {
                    extractImageObj(step)
                }
            }
        }

        extractFromSteps(root.optJSONArray("steps"))
        if (outputs.isEmpty()) {
            extractFromSteps(root.optJSONObject("interaction")?.optJSONArray("steps"))
        }

        if (outputs.isEmpty()) {
            val outputArray = root.optJSONArray("outputs")
            if (outputArray != null) {
                for (i in 0 until outputArray.length()) {
                    val item = outputArray.optJSONObject(i) ?: continue
                    if (item.optBoolean("thought", false)) continue
                    if (item.optString("type").equals("image", ignoreCase = true)) {
                        extractImageObj(item)
                    }
                }
            }
        }

        if (outputs.isEmpty()) {
            extractImageObj(root.optJSONObject("output_image"))
        }
        if (outputs.isEmpty()) {
            extractImageObj(root.optJSONObject("interaction")?.optJSONObject("output_image"))
        }
        if (outputs.isEmpty() && root.has("candidates")) {
            return parseGeminiGenerateContentResponse(rawJson)
        }
        return outputs
    }

    internal fun parseGeminiGenerateContentResponse(rawJson: String): List<ImageGenOutputImage> {
        val root = JSONObject(rawJson)
        val outputs = mutableListOf<ImageGenOutputImage>()
        val candidates = root.optJSONArray("candidates") ?: return outputs
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optBoolean("thought", false)) continue
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data").trim()
                if (data.isEmpty()) continue
                val mime = inline.optString("mimeType").ifBlank { inline.optString("mime_type", "image/png") }
                val bytes = decodeBase64Safely(data) ?: continue
                outputs += ImageGenOutputImage(
                    bytes = bytes,
                    mimeType = GeneratedImageStore.normalizeMimeType(mime, bytes),
                )
            }
        }
        return outputs
    }

    // -------------------------------------------------------------------------
    // 2. OpenAI 及兼容生态
    // -------------------------------------------------------------------------

    private fun generateOpenAiCompatible(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
        imageDownloadClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val httpReq = if (request.referenceImages.isEmpty()) {
            val body = buildOpenAiGenerationsBody(config, request)
            Request.Builder()
                .url(ProviderUrls.openAiImagesGenerationsUrl(config.baseUrl))
                .applyCustomHeaders(config)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } else {
            val multipart = buildOpenAiEditsMultipart(config, request)
            Request.Builder()
                .url(ProviderUrls.openAiImagesEditsUrl(config.baseUrl))
                .applyCustomHeaders(config)
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(multipart)
                .build()
        }
        val responseText = executeForJsonBody(
            httpReq,
            AppStrings.get(R.string.imagegen_client_label_openai, "OpenAI 生图"),
            runController,
            httpClient,
        )
        return parseOpenAiImageResponse(responseText, runController, imageDownloadClient)
    }

    internal fun buildOpenAiGenerationsBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("prompt", request.prompt)
            .put("n", request.count)
            .put("size", ImageGenSizeMapper.openAiSize(request.aspectRatio, config.model))
        val isGptImage = config.model.trim().lowercase().startsWith("gpt-image")
        if (isGptImage) {
            ImageGenSizeMapper.openAiQuality(config.openAiQuality, request.resolution)?.let { q ->
                body.put("quality", q)
            }
        } else {
            body.put("response_format", "b64_json")
        }
        return body
    }

    internal fun buildOpenAiEditsMultipart(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("n", request.count.toString())
        val size = ImageGenSizeMapper.openAiSize(request.aspectRatio, config.model)
        if (size != "auto") {
            builder.addFormDataPart("size", size)
        }
        if (config.model.trim().lowercase().startsWith("gpt-image")) {
            ImageGenSizeMapper.openAiQuality(config.openAiQuality, request.resolution)?.let { q ->
                builder.addFormDataPart("quality", q)
            }
        }
        val fieldName = if (request.referenceImages.size > 1) "image[]" else "image"
        request.referenceImages.forEachIndexed { index, ref ->
            val ext = when (ref.mimeType.lowercase()) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            builder.addFormDataPart(
                fieldName,
                "ref_$index.$ext",
                ref.bytes.toRequestBody(ref.mimeType.toMediaType()),
            )
        }
        return builder.build()
    }

    // -------------------------------------------------------------------------
    // 3. xAI (Grok Imagine)
    // -------------------------------------------------------------------------

    private fun generateXai(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
        imageDownloadClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val (url, body) = if (request.referenceImages.isEmpty()) {
            ProviderUrls.openAiImagesGenerationsUrl(config.baseUrl) to buildXaiGenerationsBody(config, request)
        } else {
            ProviderUrls.openAiImagesEditsUrl(config.baseUrl) to buildXaiEditsBody(config, request)
        }
        val httpReq = Request.Builder()
            .url(url)
            .applyCustomHeaders(config)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = executeForJsonBody(httpReq, "xAI Grok Imagine", runController, httpClient)
        return parseOpenAiImageResponse(responseText, runController, imageDownloadClient)
    }

    internal fun buildXaiGenerationsBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("prompt", request.prompt)
            .put("n", request.count)
            .put("resolution", ImageGenSizeMapper.xaiResolution(request.resolution))
            .put("response_format", "b64_json")
        if (request.aspectRatio != ImageGenAspectRatio.AUTO) {
            body.put("aspect_ratio", request.aspectRatio.wireValue)
        }
        return body
    }

    internal fun buildXaiEditsBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("prompt", request.prompt)
            .put("n", request.count)
            .put("resolution", ImageGenSizeMapper.xaiResolution(request.resolution))
            .put("response_format", "b64_json")
        if (request.aspectRatio != ImageGenAspectRatio.AUTO) {
            body.put("aspect_ratio", request.aspectRatio.wireValue)
        }
        if (request.referenceImages.size == 1) {
            body.put(
                "image",
                JSONObject()
                    .put("url", request.referenceImages.first().toDataUrl())
                    .put("type", "image_url"),
            )
        } else {
            val arr = JSONArray()
            request.referenceImages.forEach { ref ->
                arr.put(JSONObject().put("url", ref.toDataUrl()).put("type", "image_url"))
            }
            body.put("images", arr)
        }
        return body
    }

    // -------------------------------------------------------------------------
    // 4. 硅基流动（SiliconFlow）
    // -------------------------------------------------------------------------

    private fun generateSiliconFlow(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
        imageDownloadClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val results = mutableListOf<ImageGenOutputImage>()
        var totalBytes = 0L
        // 硅基流动当前已移除 batch_size 参数，多张通过顺序多次请求实现。
        repeat(request.count) {
            runController?.throwIfCancelled()
            val body = buildSiliconFlowBody(config, request)
            val httpReq = Request.Builder()
                .url(ProviderUrls.openAiImagesGenerationsUrl(config.baseUrl))
                .applyCustomHeaders(config)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val responseText = executeForJsonBody(
                httpReq,
                AppStrings.get(R.string.imagegen_client_label_siliconflow, "硅基流动生图"),
                runController,
                httpClient,
            )
            val batch = parseOpenAiImageResponse(responseText, runController, imageDownloadClient)
            totalBytes = accumulateBatchBudget(totalBytes, batch)
            results += batch
        }
        return results
    }

    internal fun buildSiliconFlowBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("prompt", request.prompt)
            .put("image_size", ImageGenSizeMapper.siliconFlowImageSize(config.model, request.aspectRatio, request.resolution))
        if (request.referenceImages.isNotEmpty()) {
            body.put("image", request.referenceImages.first().toDataUrl())
        }
        return body
    }

    /** 统一解析 OpenAI / xAI / 豆包（`data[]`）与硅基流动（`images[]`）图像响应。 */
    internal fun parseOpenAiImageResponse(
        rawJson: String,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val root = JSONObject(rawJson)
        val array = root.optJSONArray("data") ?: root.optJSONArray("images")
            ?: throw ImageGenException(
                "IMAGE_GEN_EMPTY_OUTPUT",
                AppStrings.get(R.string.imagegen_error_missing_image_array, "接口未返回图像数组（data / images）"),
            )
        val results = mutableListOf<ImageGenOutputImage>()
        for (i in 0 until array.length()) {
            runController?.throwIfCancelled()
            val item = array.optJSONObject(i) ?: continue
            val revised = item.optString("revised_prompt").trim().takeIf { it.isNotEmpty() }
            val b64 = item.optString("b64_json").trim()
            if (b64.isNotEmpty()) {
                val bytes = decodeBase64Safely(b64) ?: continue
                results += ImageGenOutputImage(
                    bytes = bytes,
                    mimeType = GeneratedImageStore.normalizeMimeType("image/png", bytes),
                    revisedPrompt = revised,
                )
            } else {
                val url = item.optString("url").trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val (bytes, mime) = downloadImageBytes(url, runController, httpClient)
                    results += ImageGenOutputImage(bytes = bytes, mimeType = mime, revisedPrompt = revised)
                }
            }
        }
        if (results.isEmpty()) {
            throw ImageGenException(
                "IMAGE_GEN_EMPTY_OUTPUT",
                AppStrings.get(R.string.imagegen_error_empty_output, "生图接口未返回有效图片"),
            )
        }
        return results
    }

    // -------------------------------------------------------------------------
    // 5. 豆包（火山方舟 Seedream）
    // -------------------------------------------------------------------------

    private fun generateDoubao(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
        imageDownloadClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val results = mutableListOf<ImageGenOutputImage>()
        var totalBytes = 0L
        repeat(request.count) {
            runController?.throwIfCancelled()
            val body = buildDoubaoBody(config, request)
            val httpReq = Request.Builder()
                .url(ProviderUrls.openAiImagesGenerationsUrl(config.baseUrl))
                .applyCustomHeaders(config)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val responseText = executeForJsonBody(
                httpReq,
                AppStrings.get(R.string.imagegen_client_label_doubao, "豆包 Seedream"),
                runController,
                httpClient,
            )
            val batch = parseOpenAiImageResponse(responseText, runController, imageDownloadClient)
            totalBytes = accumulateBatchBudget(totalBytes, batch)
            results += batch
        }
        return results
    }

    internal fun buildDoubaoBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("prompt", request.prompt)
            .put("size", ImageGenSizeMapper.doubaoSize(request.aspectRatio, request.resolution, config.model))
            .put("response_format", "url")
            .put("watermark", config.watermark)
        if (request.referenceImages.size == 1) {
            body.put("image", request.referenceImages.first().toDataUrl())
        } else if (request.referenceImages.size > 1) {
            val arr = JSONArray()
            request.referenceImages.forEach { ref -> arr.put(ref.toDataUrl()) }
            body.put("image", arr)
        }
        return body
    }

    // -------------------------------------------------------------------------
    // 6. 千问（阿里云百炼 Qwen-Image 2.0 同步多模态接口）
    // -------------------------------------------------------------------------

    private fun generateQwen(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
        imageDownloadClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val body = buildQwenBody(config, request)
        val url = ProviderUrls.qwenMultimodalGenerationUrl(config.baseUrl)
        val httpReq = Request.Builder()
            .url(url)
            .applyCustomHeaders(config)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseText = executeForJsonBody(
            httpReq,
            AppStrings.get(R.string.imagegen_client_label_qwen, "千问 Qwen-Image"),
            runController,
            httpClient,
            isDashScope = true,
        )
        return parseQwenResponse(responseText, runController, imageDownloadClient)
    }

    internal fun buildQwenBody(
        config: ImageGenRuntimeConfig,
        request: ImageGenRequest,
    ): JSONObject {
        val content = JSONArray().apply {
            request.referenceImages.forEach { ref ->
                put(JSONObject().put("image", ref.toDataUrl()))
            }
            put(JSONObject().put("text", request.prompt))
        }
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content),
        )
        val parameters = JSONObject()
            .put("n", request.count.coerceIn(1, 6))
            .put("prompt_extend", config.promptExtend)
            .put("watermark", config.watermark)
        val size = ImageGenSizeMapper.qwenSize(
            aspectRatio = request.aspectRatio,
            resolution = request.resolution,
            isEdit = request.referenceImages.isNotEmpty(),
            // 多图编辑按百炼规范以最后一张参考图比例为准。
            referenceAspectRatio = request.referenceImages.lastOrNull()?.aspectRatio,
        )
        if (!size.isNullOrBlank()) {
            parameters.put("size", size)
        }
        return JSONObject()
            .put("model", config.model)
            .put("input", JSONObject().put("messages", messages))
            .put("parameters", parameters)
    }

    internal fun parseQwenResponse(
        rawJson: String,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
    ): List<ImageGenOutputImage> {
        val root = JSONObject(rawJson)
        val errCode = root.optString("code").trim()
        if (errCode.isNotEmpty()) {
            val safeCode = sanitizeToken(errCode) ?: "ERROR"
            val reqId = sanitizeToken(root.optString("request_id"))
            val detail = buildString {
                append(AppStrings.get(R.string.imagegen_error_qwen_failed, "千问生图失败（%1\$s）", safeCode))
                if (reqId != null) append(" [req=").append(reqId).append("]")
            }
            throw ImageGenException("QWEN_$safeCode", detail)
        }
        val choices = root.optJSONObject("output")?.optJSONArray("choices")
            ?: throw ImageGenException(
                "IMAGE_GEN_EMPTY_OUTPUT",
                AppStrings.get(R.string.imagegen_error_qwen_missing_choices, "千问接口未返回 output.choices"),
            )
        val results = mutableListOf<ImageGenOutputImage>()
        for (i in 0 until choices.length()) {
            val content = choices.optJSONObject(i)
                ?.optJSONObject("message")
                ?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                runController?.throwIfCancelled()
                val part = content.optJSONObject(j) ?: continue
                val url = part.optString("image").trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val (bytes, mime) = downloadImageBytes(url, runController, httpClient)
                    results += ImageGenOutputImage(bytes = bytes, mimeType = mime)
                }
            }
        }
        if (results.isEmpty()) {
            throw ImageGenException(
                "IMAGE_GEN_EMPTY_OUTPUT",
                AppStrings.get(R.string.imagegen_error_qwen_missing_image_url, "千问接口未返回有效图片 URL"),
            )
        }
        return results
    }

    // -------------------------------------------------------------------------
    // 通用 HTTP 执行、有界读取、安全错误提取与参考图严格校验
    // -------------------------------------------------------------------------

    private fun executeForJsonBody(
        request: Request,
        label: String,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
        isDashScope: Boolean = false,
    ): String {
        val call = httpClient.newCall(request)
        val binding = runController?.register { call.cancel() }
        try {
            call.execute().use { response ->
                if (response.isRedirect) {
                    throw ImageGenException(
                        code = "HTTP_${response.code}_REDIRECT_BLOCKED",
                        message = AppStrings.get(
                            R.string.imagegen_error_redirect_blocked,
                            "%1\$s 接口返回重定向（HTTP %2\$d），出于凭据安全已拦截，请检查 Base URL 是否准确",
                            label,
                            response.code,
                        ),
                        httpCode = response.code,
                    )
                }
                if (!response.isSuccessful) {
                    val errorBody = readBoundedText(response, MAX_ERROR_BODY_BYTES, runController)
                    val (code, message) = buildSafeHttpError(label, response.code, errorBody, isDashScope)
                    throw ImageGenException(code = code, message = message, httpCode = response.code)
                }
                val rawBody = readBoundedText(response, MAX_JSON_RESPONSE_BYTES, runController)
                if (rawBody.isBlank()) {
                    throw ImageGenException(
                        "IMAGE_GEN_EMPTY_BODY",
                        AppStrings.get(R.string.imagegen_error_empty_body, "%1\$s 返回了空响应", label),
                    )
                }
                return rawBody
            }
        } catch (e: IOException) {
            if (runController?.isCancelled == true) {
                throw AgentRunCancelledException()
            }
            throw ImageGenException(
                "IMAGE_GEN_NETWORK_ERROR",
                AppStrings.get(R.string.imagegen_error_network, "%1\$s 请求失败：网络连接异常或超时", label),
            )
        } finally {
            binding?.close()
        }
    }

    private fun downloadImageBytes(
        url: String,
        runController: AgentRunController?,
        httpClient: OkHttpClient,
    ): Pair<ByteArray, String> {
        val req = Request.Builder().url(url).get().build()
        val call = httpClient.newCall(req)
        val binding = runController?.register { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw ImageGenException(
                        "IMAGE_DOWNLOAD_FAILED",
                        AppStrings.get(
                            R.string.imagegen_error_download_http,
                            "下载生成图片失败（HTTP %1\$d）",
                            response.code,
                        ),
                        response.code,
                    )
                }
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    throw ImageGenException(
                        "IMAGE_TOO_LARGE",
                        AppStrings.get(R.string.imagegen_error_image_too_large, "生成的图片体积过大，超过本地缓存限制"),
                    )
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        runController?.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (out.size() + read > MAX_DOWNLOAD_BYTES) {
                            throw ImageGenException(
                                "IMAGE_TOO_LARGE",
                                AppStrings.get(R.string.imagegen_error_image_too_large, "生成的图片体积过大，超过本地缓存限制"),
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) {
                    throw ImageGenException(
                        "IMAGE_DOWNLOAD_EMPTY",
                        AppStrings.get(R.string.imagegen_error_download_empty, "下载的图片内容为空"),
                    )
                }
                val headerMime = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                return bytes to GeneratedImageStore.normalizeMimeType(headerMime, bytes)
            }
        } catch (e: IOException) {
            if (runController?.isCancelled == true) {
                throw AgentRunCancelledException()
            }
            throw ImageGenException(
                "IMAGE_DOWNLOAD_ERROR",
                AppStrings.get(R.string.imagegen_error_download_network, "下载生成图片时发生网络错误"),
            )
        } finally {
            binding?.close()
        }
    }

    /**
     * 严格校验并压缩参考图（至 2048px / 4MB 以内）。
     * 若输入无法被 BitmapFactory 解码为合法图像，直接抛出异常，绝不把未知文件字节外发给上游。
     */
    fun prepareReferenceImage(rawBytes: ByteArray, rawMimeType: String): ImageGenInputImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val detectedMime = bounds.outMimeType?.lowercase().orEmpty()
        if (w <= 0 || h <= 0 || !detectedMime.startsWith("image/")) {
            throw ImageGenException(
                "INVALID_REFERENCE_IMAGE",
                AppStrings.get(R.string.imagegen_error_invalid_reference_image, "参考文件不是有效的图片格式，已拒绝上传"),
            )
        }
        val normalizedMime = GeneratedImageStore.normalizeMimeType(detectedMime.ifEmpty { rawMimeType }, rawBytes)
        if (w <= MAX_REF_IMAGE_DIM && h <= MAX_REF_IMAGE_DIM && rawBytes.size <= MAX_REF_IMAGE_BYTES) {
            return ImageGenInputImage(rawBytes, normalizedMime, width = w, height = h)
        }
        var sampleSize = 1
        while ((w / sampleSize) > MAX_REF_IMAGE_DIM || (h / sampleSize) > MAX_REF_IMAGE_DIM) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            rawBytes,
            0,
            rawBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw ImageGenException(
            "INVALID_REFERENCE_IMAGE",
            AppStrings.get(R.string.imagegen_error_reference_decode_failed, "参考图片解码失败，无法用于图生图编辑"),
        )
        val scaled = try {
            val maxSide = maxOf(decoded.width, decoded.height)
            if (maxSide > MAX_REF_IMAGE_DIM) {
                val ratio = MAX_REF_IMAGE_DIM.toFloat() / maxSide.toFloat()
                val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
        } catch (_: Throwable) {
            decoded
        }
        return try {
            val out = ByteArrayOutputStream()
            val outW = scaled.width
            val outH = scaled.height
            val hasAlpha = normalizedMime == "image/png" && scaled.hasAlpha()
            if (hasAlpha) {
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (out.size() <= MAX_REF_IMAGE_BYTES) {
                    return ImageGenInputImage(out.toByteArray(), "image/png", width = outW, height = outH)
                }
                out.reset()
            }
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            ImageGenInputImage(out.toByteArray(), "image/jpeg", width = outW, height = outH)
        } finally {
            scaled.recycle()
        }
    }

    private fun Request.Builder.applyCustomHeaders(config: ImageGenRuntimeConfig): Request.Builder {
        CustomHeaderFilter.sanitize(config.customHeaders)
            .filterNot { it.name.trim().lowercase() in EXTRA_BLOCKED_HEADERS }
            .forEach { header ->
                header(header.name.trim(), header.value)
            }
        return this
    }

    private fun readBoundedText(
        response: Response,
        maxBytes: Int,
        runController: AgentRunController?,
    ): String {
        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            throw ImageGenException(
                "IMAGE_GEN_RESPONSE_TOO_LARGE",
                AppStrings.get(R.string.imagegen_error_response_too_large, "服务端返回数据过大，超出本地安全限制"),
            )
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        body.byteStream().use { input ->
            while (true) {
                runController?.throwIfCancelled()
                val read = input.read(buffer)
                if (read <= 0) break
                val remaining = maxBytes - out.size()
                if (remaining <= 0) {
                    if (maxBytes == MAX_ERROR_BODY_BYTES) break
                    throw ImageGenException(
                        "IMAGE_GEN_RESPONSE_TOO_LARGE",
                        AppStrings.get(R.string.imagegen_error_response_too_large, "服务端返回数据过大，超出本地安全限制"),
                    )
                }
                val toWrite = minOf(read, remaining)
                out.write(buffer, 0, toWrite)
                if (toWrite < read) {
                    if (maxBytes == MAX_ERROR_BODY_BYTES) break
                    throw ImageGenException(
                        "IMAGE_GEN_RESPONSE_TOO_LARGE",
                        AppStrings.get(R.string.imagegen_error_response_too_large, "服务端返回数据过大，超出本地安全限制"),
                    )
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun accumulateBatchBudget(currentBytes: Long, batch: List<ImageGenOutputImage>): Long {
        val next = currentBytes + batch.sumOf { it.bytes.size.toLong() }
        if (next > MAX_BATCH_OUTPUT_BYTES) {
            throw ImageGenException(
                "IMAGE_BATCH_TOO_LARGE",
                AppStrings.get(
                    R.string.imagegen_error_batch_too_large,
                    "本次生成的图片总体积超出安全内存预算，请减少单次生成张数或降低分辨率",
                ),
            )
        }
        return next
    }

    /**
     * 只输出受控状态码、经白名单校验的上游错误码与请求 ID，
     * 不把上游原始错误正文透传进持久聊天记录或工具结果，避免回显敏感内容。
     */
    internal fun buildSafeHttpError(
        label: String,
        httpCode: Int,
        rawBody: String,
        isDashScope: Boolean = false,
    ): Pair<String, String> {
        val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
        val errObj = parsed?.optJSONObject("error")
        val rawUpstreamCode = errObj?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
            ?: errObj?.optString("status")?.trim()?.takeIf { it.isNotEmpty() }
            ?: errObj?.optString("type")?.trim()?.takeIf { it.isNotEmpty() }
            ?: parsed?.optString("code")?.trim()?.takeIf { it.isNotEmpty() }
        val safeCode = sanitizeToken(rawUpstreamCode)
        val safeRequestId = sanitizeToken(
            parsed?.optString("request_id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: parsed?.optString("requestId")?.trim()?.takeIf { it.isNotEmpty() },
        )
        val stableCode = when {
            isDashScope && safeCode != null -> "QWEN_$safeCode"
            safeCode != null -> "HTTP_${httpCode}_$safeCode"
            else -> "HTTP_$httpCode"
        }
        val hint = when (httpCode) {
            400, 422 -> AppStrings.get(R.string.imagegen_http_error_bad_request, "请求参数、模型规格或提示词内容不受支持")
            401 -> AppStrings.get(R.string.imagegen_http_error_unauthorized, "API Key 无效或未授权")
            403 -> AppStrings.get(R.string.imagegen_http_error_forbidden, "无权访问该生图模型或账号权限不足")
            404 -> AppStrings.get(R.string.imagegen_http_error_not_found, "模型 ID 或接口路径不存在")
            429 -> AppStrings.get(R.string.imagegen_http_error_rate_limit, "请求过于频繁或额度已用尽，请稍后重试")
            in 500..599 -> AppStrings.get(R.string.imagegen_http_error_server, "上游生图服务暂时异常，请稍后重试")
            else -> AppStrings.get(R.string.imagegen_http_error_generic, "服务返回错误")
        }
        val summary = if (safeCode != null) {
            AppStrings.get(
                R.string.imagegen_http_error_summary_with_code,
                "%1\$s请求失败（HTTP %2\$d / %3\$s）：%4\$s",
                label,
                httpCode,
                safeCode,
                hint,
            )
        } else {
            AppStrings.get(
                R.string.imagegen_http_error_summary,
                "%1\$s请求失败（HTTP %2\$d）：%3\$s",
                label,
                httpCode,
                hint,
            )
        }
        val text = if (safeRequestId != null) "$summary [req=$safeRequestId]" else summary
        return stableCode to text
    }

    private fun sanitizeToken(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.takeIf { SAFE_TOKEN_REGEX.matches(it) }
    }

    private fun decodeBase64Safely(data: String): ByteArray? =
        runCatching {
            val cleaned = data.substringAfter("base64,", data).replace(Regex("\\s+"), "")
            Base64.getDecoder().decode(cleaned)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
}
