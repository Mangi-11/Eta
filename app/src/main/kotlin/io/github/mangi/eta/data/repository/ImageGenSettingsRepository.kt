package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.ImageGenRuntimeConfig
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.DoubaoImageConfig
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiImageConfig
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ImageGenBackend
import io.github.mangi.eta.data.model.ImageGenCredentials
import io.github.mangi.eta.data.model.ImageGenSettings
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiImageConfig
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.QwenImageConfig
import io.github.mangi.eta.data.model.SiliconFlowImageConfig
import io.github.mangi.eta.data.model.XaiImageConfig
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.net.InetAddress
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class ImageGenCredentialsUnavailable : IllegalStateException(
    AppStrings.get(
        R.string.imagegen_error_credentials_unavailable,
        "AI 生图凭据暂时不可用，请重新填写并保存",
    ),
)

internal data class LinkedImageProviderOption(
    val id: String,
    val name: String,
    val hasApiKey: Boolean,
    val baseUrl: String,
)

internal object ImageGenSettingsRepository {
    private const val PREFS_NAME = "eta_image_gen_secrets"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEYSTORE_ALIAS = "eta_image_gen_credentials_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<ImageGenSettings> = SettingsDataStore.imageGenSettingsFlow()

    suspend fun settings(): ImageGenSettings = SettingsDataStore.imageGenSettings()

    suspend fun save(settings: ImageGenSettings) = SettingsDataStore.setImageGenSettings(settings)

    suspend fun credentials(context: Context): ImageGenCredentials = withContext(Dispatchers.IO) {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIALS, null) ?: return@withContext ImageGenCredentials()
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            json.decodeFromString<ImageGenCredentials>(
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw ImageGenCredentialsUnavailable()
        }
    }

    suspend fun saveCredentials(context: Context, credentials: ImageGenCredentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
        check(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CREDENTIALS, Base64.getEncoder().encodeToString(encrypted))
                .commit(),
        ) {
            AppStrings.get(R.string.imagegen_error_credentials_save_failed, "AI 生图凭据保存失败")
        }
    }

    /** 返回可用于指定生图渠道一键复用的模型服务商列表（过滤掉不支持 API Key 的 OAuth 账号）。 */
    suspend fun availableLinkedProviders(backend: ImageGenBackend): List<LinkedImageProviderOption> =
        withContext(Dispatchers.IO) {
            runCatching {
                ProviderRepository.allProviders()
                    .filter { provider ->
                        if (ProviderAuthModes.isOAuth(provider.authMode)) return@filter false
                        when (backend) {
                            ImageGenBackend.GEMINI -> provider is GeminiProviderSetting
                            ImageGenBackend.OPENAI_COMPATIBLE ->
                                provider is OpenAiCompatibleProviderSetting || provider is CustomProviderSetting
                            ImageGenBackend.XAI ->
                                ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.XAI ||
                                    provider.baseUrl.contains("api.x.ai", ignoreCase = true)
                            ImageGenBackend.QWEN ->
                                ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.BAILIAN ||
                                    provider.baseUrl.contains("dashscope", ignoreCase = true) ||
                                    provider.baseUrl.contains("maas.aliyuncs.com", ignoreCase = true)
                            ImageGenBackend.DOUBAO ->
                                (provider is OpenAiCompatibleProviderSetting || provider is CustomProviderSetting) &&
                                    (provider.baseUrl.contains("volces.com", ignoreCase = true) ||
                                        provider.name.contains("火山", ignoreCase = true) ||
                                        provider.name.contains("豆包", ignoreCase = true))
                            ImageGenBackend.SILICONFLOW ->
                                ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.SILICONFLOW ||
                                    provider.baseUrl.contains("siliconflow", ignoreCase = true)
                        }
                    }
                    .map { provider ->
                        LinkedImageProviderOption(
                            id = provider.id,
                            name = provider.name,
                            hasApiKey = provider.apiKey.isNotBlank(),
                            baseUrl = provider.baseUrl.trim(),
                        )
                    }
            }.getOrDefault(emptyList())
        }

    suspend fun resolveRuntimeConfig(
        context: Context,
        settingsOverride: ImageGenSettings? = null,
        credentialsOverride: ImageGenCredentials? = null,
    ): ImageGenRuntimeConfig = withContext(Dispatchers.IO) {
        val settings = settingsOverride ?: runCatching { settings() }.getOrDefault(ImageGenSettings())
        val credentials = credentialsOverride ?: runCatching { credentials(context) }.getOrDefault(ImageGenCredentials())
        val allProviders = runCatching { ProviderRepository.allProviders() }.getOrDefault(emptyList())
        resolveRuntimeConfigPure(settings, credentials, allProviders)
    }

    /**
     * 凭据与目标地址必须绑定同一连接配置，避免 A 服务商的 Key/Header 误发给 B 主机：
     * - 独立模式（选了「独立填写」或填入了非空独立 API Key）完全使用独立配置的地址与地域，不继承任何关联服务商的地址和 Header；
     * - 关联模式完全使用所选关联服务商的 API Key、Base URL、CustomHeaders 及平台字段。
     */
    internal fun resolveRuntimeConfigPure(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig = when (settings.backend) {
        ImageGenBackend.GEMINI -> resolveGemini(settings, credentials, allProviders)
        ImageGenBackend.OPENAI_COMPATIBLE -> resolveOpenAi(settings, credentials, allProviders)
        ImageGenBackend.XAI -> resolveXai(settings, credentials, allProviders)
        ImageGenBackend.DOUBAO -> resolveDoubao(settings, credentials, allProviders)
        ImageGenBackend.QWEN -> resolveQwen(settings, credentials, allProviders)
        ImageGenBackend.SILICONFLOW -> resolveSiliconFlow(settings, credentials, allProviders)
    }

    private fun resolveGemini(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.gemini
        val standaloneKey = credentials.geminiApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.filterIsInstance<GeminiProviderSetting>().firstOrNull {
                    it.id == BuiltinProviders.GEMINI_ID && it.apiKey.isNotBlank()
                } ?: providers.filterIsInstance<GeminiProviderSetting>().firstOrNull { it.apiKey.isNotBlank() }
            } as? GeminiProviderSetting
        }

        val endpointMode = if (linked != null) {
            GeminiEndpointMode.normalize(linked.endpointMode)
        } else {
            GeminiEndpointMode.normalize(cfg.endpointMode)
        }
        val defaultUrl = if (endpointMode == GeminiEndpointMode.VERTEX_AI) {
            GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL
        } else {
            GeminiProviderSetting.DEFAULT_BASE_URL
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { defaultUrl }
        } else {
            cfg.baseUrl.trim().ifEmpty { defaultUrl }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.GEMINI,
            model = cfg.model.trim().ifEmpty { GeminiImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            geminiEndpointMode = endpointMode,
            vertexProject = if (linked != null) linked.vertexProject.trim() else cfg.vertexProject.trim(),
            vertexLocation = if (linked != null) {
                linked.vertexLocation.trim().ifEmpty { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION }
            } else {
                cfg.vertexLocation.trim().ifEmpty { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION }
            },
        )
    }

    private fun resolveOpenAi(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.openAi
        val standaloneKey = credentials.openAiApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.OPENAI_ID &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.OPENAI &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                }
            }
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { OpenAiImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { OpenAiImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.OPENAI_COMPATIBLE,
            model = cfg.model.trim().ifEmpty { OpenAiImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            openAiQuality = cfg.quality.trim().ifEmpty { "auto" },
        )
    }

    private fun resolveXai(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.xai
        val standaloneKey = credentials.xaiApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.XAI_ID &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.XAI &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                }
            }
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { XaiImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { XaiImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.XAI,
            model = cfg.model.trim().ifEmpty { XaiImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
        )
    }

    private fun resolveDoubao(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.doubao
        val standaloneKey = credentials.doubaoApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else findLinkedProvider(cfg.linkedProviderId, allProviders) { null }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { DoubaoImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { DoubaoImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.DOUBAO,
            model = cfg.model.trim().ifEmpty { DoubaoImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            watermark = cfg.watermark,
        )
    }

    private fun resolveQwen(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.qwen
        val standaloneKey = credentials.qwenApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.BAILIAN_ID && it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.BAILIAN && it.apiKey.isNotBlank()
                }
            }
        }
        // 独立模式按用户所选地域与自定义域名决定地址，绝不继承关联北京服务商地址。
        val baseUrl = if (linked != null) {
            normalizeDashScopeEndpoint(linked.baseUrl, cfg.defaultEndpoint())
        } else {
            normalizeDashScopeEndpoint(cfg.baseUrl, cfg.defaultEndpoint())
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.QWEN,
            model = cfg.model.trim().ifEmpty { QwenImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            watermark = cfg.watermark,
            promptExtend = cfg.promptExtend,
        )
    }

    private fun resolveSiliconFlow(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.siliconFlow
        val standaloneKey = credentials.siliconFlowApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.SILICONFLOW_ID && it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.SILICONFLOW && it.apiKey.isNotBlank()
                }
            }
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { SiliconFlowImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { SiliconFlowImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.SILICONFLOW,
            model = cfg.model.trim().ifEmpty { SiliconFlowImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
        )
    }

    private fun findLinkedProvider(
        linkedProviderId: String,
        allProviders: List<ProviderSetting>,
        autoMatch: (List<ProviderSetting>) -> ProviderSetting?,
    ): ProviderSetting? {
        val trimmed = linkedProviderId.trim()
        if (trimmed == ImageGenSettings.STANDALONE_LINK_ID) return null
        if (trimmed.isNotEmpty()) {
            return allProviders.firstOrNull {
                it.id == trimmed && !ProviderAuthModes.isOAuth(it.authMode)
            }
        }
        return autoMatch(allProviders)
    }

    /** 校验带凭据的接口地址：公网必须使用 HTTPS，仅本机与局域网私网允许 HTTP。 */
    internal fun validateEndpointUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) {
            return AppStrings.get(R.string.imagegen_error_url_empty, "接口地址不能为空")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return AppStrings.get(R.string.imagegen_error_url_invalid, "接口地址格式无效")
        val scheme = uri.scheme?.lowercase()
            ?: return AppStrings.get(R.string.imagegen_error_url_missing_scheme, "接口地址缺少协议头（https://）")
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return AppStrings.get(R.string.imagegen_error_url_missing_host, "接口地址缺少有效主机名")
        if (scheme == "https") return null
        if (scheme == "http" && isLocalOrPrivateHost(host)) return null
        return AppStrings.get(
            R.string.imagegen_error_url_https_required,
            "携带 API Key 的公网接口必须使用 HTTPS 协议",
        )
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) return true
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }

    /** 将阿里百炼 OpenAI 兼容端点规范化为 DashScope 原生接口根域名，保留用户专属业务空间域名。 */
    internal fun normalizeDashScopeEndpoint(raw: String, fallback: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return fallback.trimEnd('/')
        val withoutCompat = trimmed
            .removeSuffix("/compatible-mode/v1")
            .removeSuffix("/compatible-mode")
            .removeSuffix("/api/v1/services/aigc/multimodal-generation/generation")
            .removeSuffix("/api/v1")
            .trimEnd('/')
        return withoutCompat.ifEmpty { fallback.trimEnd('/') }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
