package io.github.mangi.eta.data.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import kotlinx.serialization.Serializable

@Serializable
internal enum class ImageGenBackend(val wireValue: String) {
    GEMINI("gemini"),
    OPENAI_COMPATIBLE("openai_compatible"),
    XAI("xai"),
    DOUBAO("doubao"),
    QWEN("qwen"),
    SILICONFLOW("siliconflow");

    companion object {
        fun fromWireValue(value: String?): ImageGenBackend =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: GEMINI
    }
}

@Serializable
internal enum class ImageGenAspectRatio(
    val wireValue: String,
    @StringRes val displayLabelRes: Int,
    private val fallbackDisplayLabel: String,
) {
    AUTO("auto", R.string.imagegen_aspect_ratio_auto, "自动"),
    RATIO_1_1("1:1", R.string.imagegen_aspect_ratio_1_1, "1:1 正方形"),
    RATIO_16_9("16:9", R.string.imagegen_aspect_ratio_16_9, "16:9 横屏"),
    RATIO_9_16("9:16", R.string.imagegen_aspect_ratio_9_16, "9:16 竖屏"),
    RATIO_4_3("4:3", R.string.imagegen_aspect_ratio_4_3, "4:3 横图"),
    RATIO_3_4("3:4", R.string.imagegen_aspect_ratio_3_4, "3:4 竖图"),
    RATIO_3_2("3:2", R.string.imagegen_aspect_ratio_3_2, "3:2 相机横图"),
    RATIO_2_3("2:3", R.string.imagegen_aspect_ratio_2_3, "2:3 海报竖图");

    val displayLabel: String
        get() = AppStrings.get(displayLabelRes, fallbackDisplayLabel)

    companion object {
        fun fromWireValue(value: String?): ImageGenAspectRatio {
            val normalized = value?.trim()?.lowercase() ?: return AUTO
            return entries.firstOrNull { it.wireValue == normalized } ?: AUTO
        }
    }
}

@Serializable
internal enum class ImageGenResolution(
    val wireValue: String,
    @StringRes val displayLabelRes: Int,
    private val fallbackDisplayLabel: String,
) {
    R_1K("1K", R.string.imagegen_resolution_1k, "1K 标准"),
    R_2K("2K", R.string.imagegen_resolution_2k, "2K 高清"),
    R_4K("4K", R.string.imagegen_resolution_4k, "4K 超清");

    val displayLabel: String
        get() = AppStrings.get(displayLabelRes, fallbackDisplayLabel)

    companion object {
        fun fromWireValue(value: String?): ImageGenResolution {
            val normalized = value?.trim()?.uppercase() ?: return R_1K
            return when (normalized) {
                "1K", "STANDARD", "LOW", "MEDIUM" -> R_1K
                "2K", "HD", "HIGH" -> R_2K
                "4K", "ULTRA" -> R_4K
                else -> R_1K
            }
        }
    }
}

internal data class ImageGenModelPreset(
    val id: String,
    internal val rawLabel: String,
    @StringRes val noteRes: Int,
    private val fallbackNote: String,
    @StringRes val labelRes: Int? = null,
) {
    val label: String
        get() = labelRes?.let { AppStrings.get(it, rawLabel) } ?: rawLabel

    val note: String
        get() = AppStrings.get(noteRes, fallbackNote)
}

internal object ImageGenModelPresets {
    val GEMINI: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "gemini-nano-banana-2.1",
            rawLabel = "Nano Banana 2.1",
            noteRes = R.string.imagegen_preset_note_gemini_nano_banana_2_1,
            fallbackNote = "主力推荐 · 1K/2K/4K 与多图一致",
        ),
        ImageGenModelPreset(
            id = "gemini-3.1-flash-lite-image",
            rawLabel = "Nano Banana 2 Lite",
            noteRes = R.string.imagegen_preset_note_gemini_3_1_flash_lite,
            fallbackNote = "极速低价 · 仅 1K 分辨率",
        ),
        ImageGenModelPreset(
            id = "gemini-3.1-flash-image",
            rawLabel = "Nano Banana 2",
            noteRes = R.string.imagegen_preset_note_gemini_3_1_flash,
            fallbackNote = "支持 1K~4K 与多参考图",
        ),
        ImageGenModelPreset(
            id = "gemini-3-pro-image",
            rawLabel = "Nano Banana Pro",
            noteRes = R.string.imagegen_preset_note_gemini_3_pro,
            fallbackNote = "专业排版与复杂指令推理",
        ),
        ImageGenModelPreset(
            id = "gemini-2.5-flash-image",
            rawLabel = "Nano Banana 1",
            noteRes = R.string.imagegen_preset_note_gemini_2_5_flash,
            fallbackNote = "固定分辨率经典模型",
        ),
    )

    val OPENAI_COMPATIBLE: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "gpt-image-2.6-flare",
            rawLabel = "GPT-Image 2.6 Flare",
            noteRes = R.string.imagegen_preset_note_gpt_2_6_flare,
            fallbackNote = "OpenAI 快速高质量出图",
        ),
        ImageGenModelPreset(
            id = "gpt-image-2.6-sunburst",
            rawLabel = "GPT-Image 2.6 Sunburst",
            noteRes = R.string.imagegen_preset_note_gpt_2_6_sunburst,
            fallbackNote = "OpenAI 精细编辑与高保真",
        ),
        ImageGenModelPreset(
            id = "gpt-image-2.5-flare",
            rawLabel = "GPT-Image 2.5 Flare",
            noteRes = R.string.imagegen_preset_note_gpt_2_5_flare,
            fallbackNote = "OpenAI 2.5 快速主力",
        ),
        ImageGenModelPreset(
            id = "gpt-image-2.5-sunburst",
            rawLabel = "GPT-Image 2.5 Sunburst",
            noteRes = R.string.imagegen_preset_note_gpt_2_5_sunburst,
            fallbackNote = "OpenAI 2.5 精细编辑",
        ),
        ImageGenModelPreset(
            id = "gpt-image-1.5",
            rawLabel = "GPT-Image 1.5",
            noteRes = R.string.imagegen_preset_note_gpt_1_5,
            fallbackNote = "广泛兼容版本",
        ),
    )

    val XAI: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "grok-imagine-image-2.0",
            rawLabel = "Grok Imagine 2.0",
            noteRes = R.string.imagegen_preset_note_xai_grok_2_0,
            fallbackNote = "xAI 生图与最多 5 张参考图编辑",
        ),
    )

    val DOUBAO: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "doubao-seedream-5-5-261028",
            rawLabel = "Seedream 5.5",
            noteRes = R.string.imagegen_preset_note_doubao_5_5,
            fallbackNote = "最新主力 · 1K/2K 写实与原生透明底",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-5-0-pro-260628",
            rawLabel = "Seedream 5.0 Pro",
            noteRes = R.string.imagegen_preset_note_doubao_5_0_pro,
            fallbackNote = "1K/2K 精准编辑与高细节",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-5-0-flash-260915",
            rawLabel = "Seedream 5.0 Flash",
            noteRes = R.string.imagegen_preset_note_doubao_5_0_flash,
            fallbackNote = "1K/2K 响应迅速",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-5-0-lite-260128",
            rawLabel = "Seedream 5.0 Lite",
            noteRes = R.string.imagegen_preset_note_doubao_5_0_lite,
            fallbackNote = "支持 2K/4K 与高分辨率出图",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-4-5-251128",
            rawLabel = "Seedream 4.5",
            noteRes = R.string.imagegen_preset_note_doubao_4_5,
            fallbackNote = "经典 2K/4K 高质",
        ),
    )

    val QWEN: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "qwen-image-2.0-pro",
            rawLabel = "Qwen-Image 2.0 Pro",
            noteRes = R.string.imagegen_preset_note_qwen_2_0_pro,
            fallbackNote = "主力推荐 · 中文排版与写实极强",
        ),
        ImageGenModelPreset(
            id = "qwen-image-2.0",
            rawLabel = "Qwen-Image 2.0",
            noteRes = R.string.imagegen_preset_note_qwen_2_0,
            fallbackNote = "加速版 · 速度与效果均衡",
        ),
    )

    val SILICONFLOW: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "Qwen/Qwen-Image",
            rawLabel = "Qwen-Image",
            noteRes = R.string.imagegen_preset_note_sf_qwen_image,
            fallbackNote = "硅基流动 · 综合与中文表现优秀",
        ),
        ImageGenModelPreset(
            id = "Qwen/Qwen-Image-Edit-2509",
            rawLabel = "Qwen-Image-Edit",
            noteRes = R.string.imagegen_preset_note_sf_qwen_edit,
            fallbackNote = "硅基流动 · 支持图生图编辑",
        ),
        ImageGenModelPreset(
            id = "Kwai-Kolors/Kolors",
            rawLabel = "可图 Kolors",
            noteRes = R.string.imagegen_preset_note_sf_kolors,
            fallbackNote = "硅基流动 · 高性价比文生图",
            labelRes = R.string.imagegen_preset_label_kolors,
        ),
        ImageGenModelPreset(
            id = "black-forest-labs/FLUX.1-schnell",
            rawLabel = "FLUX.1 Schnell",
            noteRes = R.string.imagegen_preset_note_sf_flux_schnell,
            fallbackNote = "硅基流动 · 极速文生图",
        ),
    )

    fun forBackend(backend: ImageGenBackend): List<ImageGenModelPreset> = when (backend) {
        ImageGenBackend.GEMINI -> GEMINI
        ImageGenBackend.OPENAI_COMPATIBLE -> OPENAI_COMPATIBLE
        ImageGenBackend.XAI -> XAI
        ImageGenBackend.DOUBAO -> DOUBAO
        ImageGenBackend.QWEN -> QWEN
        ImageGenBackend.SILICONFLOW -> SILICONFLOW
    }
}

@Serializable
internal data class GeminiImageConfig(
    /** 为空时自动匹配已配置的内置 Gemini 服务商；显式设为 [ImageGenSettings.STANDALONE_LINK_ID] 表示独立配置。 */
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = "",
    val endpointMode: String = GeminiEndpointMode.AI_STUDIO,
    val vertexProject: String = "",
    val vertexLocation: String = GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-nano-banana-2.1"
    }
}

@Serializable
internal data class OpenAiImageConfig(
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val quality: String = "auto",
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-image-2.6-flare"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}

@Serializable
internal data class XaiImageConfig(
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_MODEL = "grok-imagine-image-2.0"
        const val DEFAULT_BASE_URL = "https://api.x.ai/v1"
    }
}

@Serializable
internal data class DoubaoImageConfig(
    val linkedProviderId: String = ImageGenSettings.STANDALONE_LINK_ID,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val watermark: Boolean = false,
) {
    companion object {
        const val DEFAULT_MODEL = "doubao-seedream-5-5-261028"
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

@Serializable
internal data class QwenImageConfig(
    val linkedProviderId: String = "",
    val region: SpeechRegion = SpeechRegion.BEIJING,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = "",
    val promptExtend: Boolean = true,
    val watermark: Boolean = false,
) {
    fun defaultEndpoint(): String =
        if (region == SpeechRegion.BEIJING) "https://dashscope.aliyuncs.com"
        else "https://dashscope-intl.aliyuncs.com"

    companion object {
        const val DEFAULT_MODEL = "qwen-image-2.0-pro"
    }
}

@Serializable
internal data class SiliconFlowImageConfig(
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_MODEL = "Qwen/Qwen-Image"
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
    }
}

@Serializable
internal data class ImageGenSettings(
    val enabled: Boolean = false,
    val backend: ImageGenBackend = ImageGenBackend.GEMINI,
    val defaultAspectRatio: ImageGenAspectRatio = ImageGenAspectRatio.AUTO,
    val defaultResolution: ImageGenResolution = ImageGenResolution.R_1K,
    val gemini: GeminiImageConfig = GeminiImageConfig(),
    val openAi: OpenAiImageConfig = OpenAiImageConfig(),
    val xai: XaiImageConfig = XaiImageConfig(),
    val doubao: DoubaoImageConfig = DoubaoImageConfig(),
    val qwen: QwenImageConfig = QwenImageConfig(),
    val siliconFlow: SiliconFlowImageConfig = SiliconFlowImageConfig(),
) {
    companion object {
        const val STANDALONE_LINK_ID = "__standalone__"
    }
}

/** 独立凭据不随普通设置写入明文 DataStore，统一使用 AndroidKeyStore AES-GCM 加密。 */
@Serializable
internal data class ImageGenCredentials(
    val geminiApiKey: String = "",
    val openAiApiKey: String = "",
    val xaiApiKey: String = "",
    val doubaoApiKey: String = "",
    val qwenApiKey: String = "",
    val siliconFlowApiKey: String = "",
) {
    override fun toString(): String = "ImageGenCredentials(redacted)"
}
