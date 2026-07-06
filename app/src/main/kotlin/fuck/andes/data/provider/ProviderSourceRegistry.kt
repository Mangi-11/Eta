package fuck.andes.data.provider

import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.ProviderTypes
import fuck.andes.data.model.runtimeProviderType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProviderSourceRegistry {
    private val knownSourceTypes = setOf(
        ProviderSourceTypes.CUSTOM,
        ProviderSourceTypes.OPENAI,
        ProviderSourceTypes.ANTHROPIC,
        ProviderSourceTypes.BAILIAN,
        ProviderSourceTypes.DEEPSEEK,
        ProviderSourceTypes.MOONSHOT,
        ProviderSourceTypes.MIMO,
        ProviderSourceTypes.MINIMAX,
        ProviderSourceTypes.STEPFUN,
        ProviderSourceTypes.SILICONFLOW,
        ProviderSourceTypes.OPENROUTER,
    )

    fun normalize(sourceType: String?): String {
        val normalized = sourceType?.trim()?.lowercase().orEmpty()
        return normalized.takeIf { it in knownSourceTypes } ?: ProviderSourceTypes.CUSTOM
    }

    fun resolve(provider: ProviderSetting): String =
        resolve(
            providerId = provider.id,
            sourceType = provider.sourceType,
            baseUrl = provider.baseUrl,
            providerType = provider.runtimeProviderType,
        )

    fun resolve(
        providerId: String?,
        sourceType: String? = null,
        baseUrl: String?,
        providerType: String,
    ): String {
        val normalized = normalize(sourceType)
        if (normalized != ProviderSourceTypes.CUSTOM) {
            return normalized
        }
        sourceTypeFromProviderId(providerId)?.let { return it }
        sourceTypeFromBaseUrl(baseUrl)?.let { return it }
        if (providerType == ProviderTypes.ANTHROPIC) {
            return ProviderSourceTypes.ANTHROPIC
        }
        return ProviderSourceTypes.CUSTOM
    }

    private fun sourceTypeFromProviderId(providerId: String?): String? =
        when (providerId) {
            BuiltinProviders.OPENAI_ID -> ProviderSourceTypes.OPENAI
            BuiltinProviders.ANTHROPIC_ID -> ProviderSourceTypes.ANTHROPIC
            BuiltinProviders.BAILIAN_ID -> ProviderSourceTypes.BAILIAN
            BuiltinProviders.DEEPSEEK_ID -> ProviderSourceTypes.DEEPSEEK
            BuiltinProviders.KIMI_ID -> ProviderSourceTypes.MOONSHOT
            BuiltinProviders.MIMO_ID -> ProviderSourceTypes.MIMO
            BuiltinProviders.MINIMAX_ID -> ProviderSourceTypes.MINIMAX
            BuiltinProviders.STEPFUN_ID -> ProviderSourceTypes.STEPFUN
            BuiltinProviders.SILICONFLOW_ID -> ProviderSourceTypes.SILICONFLOW
            BuiltinProviders.OPENROUTER_ID -> ProviderSourceTypes.OPENROUTER
            else -> null
        }

    private fun sourceTypeFromBaseUrl(baseUrl: String?): String? {
        val httpUrl = baseUrl?.trim()?.toHttpUrlOrNull() ?: return null
        return when {
            httpUrl.host == "api.openai.com" -> ProviderSourceTypes.OPENAI
            httpUrl.host == "api.anthropic.com" -> ProviderSourceTypes.ANTHROPIC
            httpUrl.host == "api.deepseek.com" -> ProviderSourceTypes.DEEPSEEK
            httpUrl.host == "api.moonshot.cn" -> ProviderSourceTypes.MOONSHOT
            httpUrl.host == "api.moonshot.ai" -> ProviderSourceTypes.MOONSHOT
            httpUrl.host == "api.xiaomimimo.com" -> ProviderSourceTypes.MIMO
            httpUrl.host == "api.minimax.io" -> ProviderSourceTypes.MINIMAX
            httpUrl.host == "api.minimaxi.com" -> ProviderSourceTypes.MINIMAX
            httpUrl.host == "api.stepfun.com" -> ProviderSourceTypes.STEPFUN
            httpUrl.host == "dashscope.aliyuncs.com" -> ProviderSourceTypes.BAILIAN
            httpUrl.host.endsWith(".maas.aliyuncs.com") -> ProviderSourceTypes.BAILIAN
            httpUrl.host == "api.siliconflow.cn" -> ProviderSourceTypes.SILICONFLOW
            httpUrl.host == "openrouter.ai" -> ProviderSourceTypes.OPENROUTER
            else -> null
        }
    }
}
