package io.github.mangi.eta.agent.imagegen

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ImageGenAspectRatio
import io.github.mangi.eta.data.model.ImageGenBackend
import io.github.mangi.eta.data.model.ImageGenResolution
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 统一的模型生图能力规格：两套 UI、试画测试和工具执行共用同一事实源，
 * 避免 UI 允许选择某档位而实际 API 报 400 或静默降级。
 */
internal data class ImageGenModelCapability(
    val supportedResolutions: List<ImageGenResolution>,
    /** 是否向服务端显式发送 image_size / imageSize / resolution 参数。 */
    val sendsResolutionParam: Boolean = true,
    val maxReferenceImages: Int = 8,
    val supportsEdit: Boolean = true,
    val deprecationNotice: String? = null,
    /** OpenAI GPT-Image 系列中，该档位语义为画质（标准 / 高清）而非像素倍率。 */
    val resolutionIsQualityTier: Boolean = false,
) {
    fun clampResolution(requested: ImageGenResolution): ImageGenResolution =
        if (requested in supportedResolutions) requested else supportedResolutions.first()

    companion object {
        fun resolve(
            backend: ImageGenBackend,
            model: String,
            baseUrl: String = "",
        ): ImageGenModelCapability {
            val lower = model.trim().lowercase()
            return when (backend) {
                ImageGenBackend.GEMINI -> when {
                    lower.contains("3.1-flash-lite") || lower.contains("flash-lite-image") ->
                        ImageGenModelCapability(
                            supportedResolutions = listOf(ImageGenResolution.R_1K),
                            sendsResolutionParam = true,
                            maxReferenceImages = 1,
                            supportsEdit = true,
                        )
                    lower.contains("gemini-2.5") ->
                        ImageGenModelCapability(
                            supportedResolutions = listOf(ImageGenResolution.R_1K),
                            sendsResolutionParam = false,
                            maxReferenceImages = 3,
                            supportsEdit = true,
                        )
                    else ->
                        ImageGenModelCapability(
                            supportedResolutions = listOf(
                                ImageGenResolution.R_1K,
                                ImageGenResolution.R_2K,
                                ImageGenResolution.R_4K,
                            ),
                            sendsResolutionParam = true,
                            maxReferenceImages = 14,
                            supportsEdit = true,
                        )
                }

                ImageGenBackend.OPENAI_COMPATIBLE -> {
                    val isOfficialOpenAi = baseUrl.isBlank() || baseUrl.contains("api.openai.com", ignoreCase = true)
                    val notice = if (isOfficialOpenAi && lower == "gpt-image-1") {
                        AppStrings.get(
                            R.string.imagegen_deprecation_gpt_image_1,
                            "OpenAI 官方已下线 gpt-image-1，请改用 gpt-image-2.6-flare / gpt-image-2.5-flare 或 gpt-image-1.5",
                        )
                    } else {
                        null
                    }
                    ImageGenModelCapability(
                        supportedResolutions = listOf(ImageGenResolution.R_1K, ImageGenResolution.R_2K),
                        sendsResolutionParam = false,
                        maxReferenceImages = 8,
                        supportsEdit = true,
                        deprecationNotice = notice,
                        resolutionIsQualityTier = lower.startsWith("gpt-image"),
                    )
                }

                ImageGenBackend.XAI -> ImageGenModelCapability(
                    supportedResolutions = listOf(ImageGenResolution.R_1K, ImageGenResolution.R_2K),
                    sendsResolutionParam = true,
                    maxReferenceImages = 5,
                    supportsEdit = true,
                )

                ImageGenBackend.DOUBAO -> when {
                    lower.contains("5-5") || lower.contains("5.5") ||
                        lower.contains("5-0-pro") || lower.contains("5.0-pro") ||
                        lower.contains("5-0-flash") || lower.contains("5.0-flash") ->
                        ImageGenModelCapability(
                            supportedResolutions = listOf(ImageGenResolution.R_1K, ImageGenResolution.R_2K),
                            sendsResolutionParam = true,
                            maxReferenceImages = 10,
                            supportsEdit = true,
                        )
                    lower.contains("5-0-lite") || lower.contains("5.0-lite") ||
                        lower.contains("5-0-2601") ||
                        lower.contains("4-5") || lower.contains("4.5") ->
                        ImageGenModelCapability(
                            supportedResolutions = listOf(ImageGenResolution.R_2K, ImageGenResolution.R_4K),
                            sendsResolutionParam = true,
                            maxReferenceImages = 14,
                            supportsEdit = true,
                        )
                    else ->
                        ImageGenModelCapability(
                            supportedResolutions = listOf(
                                ImageGenResolution.R_1K,
                                ImageGenResolution.R_2K,
                                ImageGenResolution.R_4K,
                            ),
                            sendsResolutionParam = true,
                            maxReferenceImages = 10,
                            supportsEdit = true,
                        )
                }

                ImageGenBackend.QWEN -> ImageGenModelCapability(
                    supportedResolutions = listOf(ImageGenResolution.R_1K, ImageGenResolution.R_2K),
                    sendsResolutionParam = true,
                    maxReferenceImages = 3,
                    supportsEdit = true,
                )

                ImageGenBackend.SILICONFLOW -> {
                    val isSchnell = lower.contains("flux.1-schnell") || lower.contains("flux-1-schnell")
                    val isKolors = lower.contains("kolors")
                    val supportsEdit = lower.contains("edit") || lower.contains("kontext") || lower.contains("img2img")
                    ImageGenModelCapability(
                        // FLUX.1-schnell 与 Kolors 仅支持官方 1K 档位枚举尺寸；Qwen-Image 支持 1K 与推荐高分辨率档。
                        supportedResolutions = if (isSchnell || isKolors) {
                            listOf(ImageGenResolution.R_1K)
                        } else {
                            listOf(ImageGenResolution.R_1K, ImageGenResolution.R_2K)
                        },
                        sendsResolutionParam = true,
                        maxReferenceImages = if (supportsEdit) 1 else 0,
                        supportsEdit = supportsEdit,
                    )
                }
            }
        }
    }
}

/**
 * 解析并合并凭据后的只读生图运行时配置。
 *
 * 所有 Provider 协议差异收敛在 agent/imagegen/ 层，UI 与工具层不自行拼装认证或端点。
 */
internal data class ImageGenRuntimeConfig(
    val enabled: Boolean,
    val backend: ImageGenBackend,
    val model: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultAspectRatio: ImageGenAspectRatio = ImageGenAspectRatio.AUTO,
    val defaultResolution: ImageGenResolution = ImageGenResolution.R_1K,
    val customHeaders: List<CustomHeader> = emptyList(),
    val geminiEndpointMode: String = GeminiEndpointMode.AI_STUDIO,
    val vertexProject: String = "",
    val vertexLocation: String = GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
    val openAiQuality: String = "auto",
    val watermark: Boolean = false,
    val promptExtend: Boolean = true,
) {
    val capability: ImageGenModelCapability
        get() = ImageGenModelCapability.resolve(backend, model, baseUrl)

    val effectiveDefaultResolution: ImageGenResolution
        get() = capability.clampResolution(defaultResolution)

    val isReady: Boolean
        get() = enabled && apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    override fun toString(): String =
        "ImageGenRuntimeConfig(enabled=$enabled, backend=${backend.wireValue}, model=$model, ready=$isReady)"
}

/**
 * 各生图渠道的尺寸与比例映射规则（依据 2026 官方接口规范）。
 */
internal object ImageGenSizeMapper {
    fun openAiSize(aspectRatio: ImageGenAspectRatio, model: String): String {
        val lower = model.trim().lowercase()
        val isGptImage = lower.startsWith("gpt-image")
        if (aspectRatio == ImageGenAspectRatio.AUTO) {
            return if (isGptImage) "auto" else "1024x1024"
        }
        return if (isGptImage) {
            when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "1024x1024"
                ImageGenAspectRatio.RATIO_16_9,
                ImageGenAspectRatio.RATIO_4_3,
                ImageGenAspectRatio.RATIO_3_2,
                -> "1536x1024"
                ImageGenAspectRatio.RATIO_9_16,
                ImageGenAspectRatio.RATIO_3_4,
                ImageGenAspectRatio.RATIO_2_3,
                -> "1024x1536"
                ImageGenAspectRatio.AUTO -> "auto"
            }
        } else {
            when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "1024x1024"
                ImageGenAspectRatio.RATIO_16_9 -> "1792x1024"
                ImageGenAspectRatio.RATIO_9_16 -> "1024x1792"
                ImageGenAspectRatio.RATIO_4_3 -> "1280x960"
                ImageGenAspectRatio.RATIO_3_4 -> "960x1280"
                ImageGenAspectRatio.RATIO_3_2 -> "1536x1024"
                ImageGenAspectRatio.RATIO_2_3 -> "1024x1536"
                ImageGenAspectRatio.AUTO -> "1024x1024"
            }
        }
    }

    fun openAiQuality(configuredQuality: String, resolution: ImageGenResolution): String? {
        val trimmed = configuredQuality.trim().lowercase()
        if (trimmed in setOf("low", "medium", "high")) return trimmed
        return when (resolution) {
            ImageGenResolution.R_1K -> null
            ImageGenResolution.R_2K, ImageGenResolution.R_4K -> "high"
        }
    }

    fun xaiResolution(resolution: ImageGenResolution): String =
        if (resolution == ImageGenResolution.R_1K) "1k" else "2k"

    /**
     * 硅基流动按模型官方枚举尺寸映射：
     * - FLUX.1-schnell 严格枚举：1024x1024, 960x1280, 768x1024, 720x1440, 720x1280, 1024x576 等；
     * - Kolors 严格枚举：1024x1024, 960x1280, 768x1024, 720x1440, 720x1280；
     * - Qwen/Qwen-Image 系列：1328x1328, 1664x928, 928x1664, 1472x1140, 1140x1472, 1584x1056, 1056x1584。
     */
    fun siliconFlowImageSize(
        model: String,
        aspectRatio: ImageGenAspectRatio,
        resolution: ImageGenResolution,
    ): String {
        val lower = model.trim().lowercase()
        if (lower.contains("flux.1-schnell") || lower.contains("flux-1-schnell")) {
            return when (aspectRatio) {
                ImageGenAspectRatio.AUTO, ImageGenAspectRatio.RATIO_1_1 -> "1024x1024"
                ImageGenAspectRatio.RATIO_16_9 -> "1024x576"
                ImageGenAspectRatio.RATIO_9_16 -> "720x1280"
                ImageGenAspectRatio.RATIO_4_3 -> "1024x768"
                ImageGenAspectRatio.RATIO_3_4 -> "768x1024"
                ImageGenAspectRatio.RATIO_3_2 -> "1024x768"
                ImageGenAspectRatio.RATIO_2_3 -> "960x1280"
            }
        }
        if (lower.contains("kolors")) {
            return when (aspectRatio) {
                ImageGenAspectRatio.AUTO, ImageGenAspectRatio.RATIO_1_1 -> "1024x1024"
                ImageGenAspectRatio.RATIO_16_9 -> "1024x1024"
                ImageGenAspectRatio.RATIO_9_16 -> "720x1280"
                ImageGenAspectRatio.RATIO_4_3 -> "1024x1024"
                ImageGenAspectRatio.RATIO_3_4 -> "768x1024"
                ImageGenAspectRatio.RATIO_3_2 -> "1024x1024"
                ImageGenAspectRatio.RATIO_2_3 -> "960x1280"
            }
        }
        val high = resolution != ImageGenResolution.R_1K
        return if (high) {
            when (aspectRatio) {
                ImageGenAspectRatio.AUTO, ImageGenAspectRatio.RATIO_1_1 -> "1328x1328"
                ImageGenAspectRatio.RATIO_16_9 -> "1664x928"
                ImageGenAspectRatio.RATIO_9_16 -> "928x1664"
                ImageGenAspectRatio.RATIO_4_3 -> "1472x1140"
                ImageGenAspectRatio.RATIO_3_4 -> "1140x1472"
                ImageGenAspectRatio.RATIO_3_2 -> "1584x1056"
                ImageGenAspectRatio.RATIO_2_3 -> "1056x1584"
            }
        } else {
            when (aspectRatio) {
                ImageGenAspectRatio.AUTO, ImageGenAspectRatio.RATIO_1_1 -> "1024x1024"
                ImageGenAspectRatio.RATIO_16_9 -> "1280x720"
                ImageGenAspectRatio.RATIO_9_16 -> "720x1280"
                ImageGenAspectRatio.RATIO_4_3 -> "1152x864"
                ImageGenAspectRatio.RATIO_3_4 -> "864x1152"
                ImageGenAspectRatio.RATIO_3_2 -> "1152x768"
                ImageGenAspectRatio.RATIO_2_3 -> "768x1152"
            }
        }
    }

    fun doubaoSize(
        aspectRatio: ImageGenAspectRatio,
        resolution: ImageGenResolution,
        model: String,
    ): String {
        val effectiveRes = ImageGenModelCapability.resolve(ImageGenBackend.DOUBAO, model).clampResolution(resolution)
        if (aspectRatio == ImageGenAspectRatio.AUTO) {
            return effectiveRes.wireValue
        }
        return when (effectiveRes) {
            ImageGenResolution.R_1K -> when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "1024x1024"
                ImageGenAspectRatio.RATIO_16_9 -> "1424x800"
                ImageGenAspectRatio.RATIO_9_16 -> "800x1424"
                ImageGenAspectRatio.RATIO_4_3 -> "1152x864"
                ImageGenAspectRatio.RATIO_3_4 -> "864x1152"
                ImageGenAspectRatio.RATIO_3_2 -> "1248x832"
                ImageGenAspectRatio.RATIO_2_3 -> "832x1248"
                ImageGenAspectRatio.AUTO -> "1K"
            }
            ImageGenResolution.R_2K -> when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "2048x2048"
                ImageGenAspectRatio.RATIO_16_9 -> "2816x1584"
                ImageGenAspectRatio.RATIO_9_16 -> "1584x2816"
                ImageGenAspectRatio.RATIO_4_3 -> "2368x1776"
                ImageGenAspectRatio.RATIO_3_4 -> "1776x2368"
                ImageGenAspectRatio.RATIO_3_2 -> "2496x1664"
                ImageGenAspectRatio.RATIO_2_3 -> "1664x2496"
                ImageGenAspectRatio.AUTO -> "2K"
            }
            ImageGenResolution.R_4K -> when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "4096x4096"
                ImageGenAspectRatio.RATIO_16_9 -> "5504x3040"
                ImageGenAspectRatio.RATIO_9_16 -> "3040x5504"
                ImageGenAspectRatio.RATIO_4_3 -> "4704x3520"
                ImageGenAspectRatio.RATIO_3_4 -> "3520x4704"
                ImageGenAspectRatio.RATIO_3_2 -> "4992x3328"
                ImageGenAspectRatio.RATIO_2_3 -> "3328x4992"
                ImageGenAspectRatio.AUTO -> "4K"
            }
        }
    }

    /**
     * 阿里云百炼 Qwen-Image 2.0：总像素范围 512*512 至 2048*2048，宽高对齐 16 的倍数。
     * 编辑模式在 AUTO 比例下：
     * - 1K 省略 size 由服务端按参考图比例输出；
     * - 2K 按最后一张参考图真实宽高比计算总像素贴近 2048*2048 的宽高（避免把纵图压成方图）。
     */
    fun qwenSize(
        aspectRatio: ImageGenAspectRatio,
        resolution: ImageGenResolution,
        isEdit: Boolean,
        referenceAspectRatio: Float? = null,
    ): String? {
        if (aspectRatio == ImageGenAspectRatio.AUTO) {
            if (isEdit) {
                if (resolution == ImageGenResolution.R_1K) return null
                val refRatio = referenceAspectRatio?.takeIf { it.isFinite() && it > 0.1f }
                    ?: return null
                val clampedRatio = refRatio.coerceIn(0.35f, 2.85f)
                // 目标总像素约 2048*2048，稍留余量确保四舍五入到 16 的倍数后不超上限。
                val targetPixels = 2000.0 * 2000.0
                val rawH = sqrt(targetPixels / clampedRatio.toDouble())
                val rawW = rawH * clampedRatio.toDouble()
                val w = (((rawW / 16.0).roundToInt()).coerceAtLeast(32) * 16).coerceIn(512, 3072)
                val h = (((rawH / 16.0).roundToInt()).coerceAtLeast(32) * 16).coerceIn(512, 3072)
                if (w.toLong() * h.toLong() in (512L * 512L)..(2048L * 2048L)) {
                    return "$w*$h"
                }
                return null
            }
            return if (resolution == ImageGenResolution.R_1K) "1024*1024" else "2048*2048"
        }
        val high = resolution != ImageGenResolution.R_1K
        return if (high) {
            when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "2048*2048"
                ImageGenAspectRatio.RATIO_16_9 -> "2688*1536"
                ImageGenAspectRatio.RATIO_9_16 -> "1536*2688"
                ImageGenAspectRatio.RATIO_4_3 -> "2368*1728"
                ImageGenAspectRatio.RATIO_3_4 -> "1728*2368"
                ImageGenAspectRatio.RATIO_3_2 -> "1536*1024"
                ImageGenAspectRatio.RATIO_2_3 -> "1024*1536"
                ImageGenAspectRatio.AUTO -> null
            }
        } else {
            when (aspectRatio) {
                ImageGenAspectRatio.RATIO_1_1 -> "1024*1024"
                ImageGenAspectRatio.RATIO_16_9 -> "1280*720"
                ImageGenAspectRatio.RATIO_9_16 -> "720*1280"
                ImageGenAspectRatio.RATIO_4_3 -> "1280*960"
                ImageGenAspectRatio.RATIO_3_4 -> "960*1280"
                ImageGenAspectRatio.RATIO_3_2 -> "1152*768"
                ImageGenAspectRatio.RATIO_2_3 -> "768*1152"
                ImageGenAspectRatio.AUTO -> null
            }
        }
    }
}
