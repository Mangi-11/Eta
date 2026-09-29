package io.github.mangi.eta.ui.components

import androidx.annotation.DrawableRes
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    providerBrandLogoRes(ProviderSourceRegistry.resolve(provider))

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    when (ProviderSourceRegistry.normalize(sourceType)) {
        ProviderSourceTypes.OPENAI -> R.drawable.provider_logo_openai
        ProviderSourceTypes.OPENAI_CODEX -> R.drawable.provider_logo_codex
        ProviderSourceTypes.ANTHROPIC -> R.drawable.provider_logo_anthropic
        ProviderSourceTypes.BAILIAN -> R.drawable.provider_logo_bailian
        ProviderSourceTypes.DEEPSEEK -> R.drawable.provider_logo_deepseek
        ProviderSourceTypes.MOONSHOT -> R.drawable.provider_logo_kimi
        ProviderSourceTypes.MIMO -> R.drawable.provider_logo_mimo
        ProviderSourceTypes.MINIMAX -> R.drawable.provider_logo_minimax
        ProviderSourceTypes.STEPFUN -> R.drawable.provider_logo_stepfun
        ProviderSourceTypes.SILICONFLOW -> R.drawable.provider_logo_siliconflow
        ProviderSourceTypes.OPENROUTER -> R.drawable.provider_logo_openrouter
        else -> null
    }

@DrawableRes
internal fun modelBrandLogoRes(modelId: String): Int? {
    val normalized = modelId.trim().lowercase()
    if (normalized.isEmpty()) return null
    return MODEL_BRAND_RULES.firstOrNull { it.pattern.containsMatchIn(normalized) }?.logo
}

@DrawableRes
internal fun modelOrProviderBrandLogoRes(modelId: String?, sourceType: String?): Int? =
    modelId?.let(::modelBrandLogoRes)
        ?: sourceType?.let(::providerBrandLogoRes)

private data class ModelBrandRule(
    @param:DrawableRes val logo: Int,
    val pattern: Regex,
)

private val MODEL_BRAND_RULES = listOf(
    ModelBrandRule(
        logo = R.drawable.provider_logo_openai,
        pattern = modelFamilyPattern("gpt", "o1", "o3", "o4", "codex"),
    ),
    ModelBrandRule(R.drawable.model_logo_claude, modelFamilyPattern("claude")),
    ModelBrandRule(R.drawable.provider_logo_kimi, modelFamilyPattern("kimi", "moonshot")),
    ModelBrandRule(R.drawable.model_logo_qwen, modelFamilyPattern("qwen", "qwq", "qvq")),
    ModelBrandRule(R.drawable.provider_logo_deepseek, modelFamilyPattern("deepseek")),
    ModelBrandRule(R.drawable.provider_logo_mimo, modelFamilyPattern("mimo")),
    ModelBrandRule(R.drawable.provider_logo_minimax, modelFamilyPattern("minimax", "abab")),
    ModelBrandRule(
        logo = R.drawable.provider_logo_stepfun,
        pattern = Regex("(?:^|[/_:.-])(?:stepfun(?:$|[/_:.-]|\\d)|step[-_]?\\d)"),
    ),
    ModelBrandRule(
        logo = R.drawable.model_logo_zai,
        pattern = Regex("(?:^|[/_:.-])glm[-_]?[45](?:$|[/_:.-]|\\d)"),
    ),
    ModelBrandRule(R.drawable.model_logo_chatglm, modelFamilyPattern("glm", "chatglm")),
    ModelBrandRule(R.drawable.model_logo_gemini, modelFamilyPattern("gemini")),
    ModelBrandRule(R.drawable.model_logo_gemma, modelFamilyPattern("gemma")),
    ModelBrandRule(R.drawable.model_logo_grok, modelFamilyPattern("grok")),
    ModelBrandRule(R.drawable.model_logo_meta, modelFamilyPattern("llama")),
    ModelBrandRule(
        logo = R.drawable.model_logo_mistral,
        pattern = modelFamilyPattern("mistral", "mixtral", "codestral"),
    ),
    ModelBrandRule(R.drawable.model_logo_doubao, modelFamilyPattern("doubao")),
    ModelBrandRule(R.drawable.model_logo_hunyuan, modelFamilyPattern("hunyuan")),
    ModelBrandRule(R.drawable.model_logo_yi, modelFamilyPattern("yi")),
)

private fun modelFamilyPattern(vararg names: String): Regex {
    val alternatives = names.joinToString("|") { Regex.escape(it) }
    return Regex("(?:^|[/_:.-])(?:$alternatives)(?:$|[/_:.-]|\\d)")
}
