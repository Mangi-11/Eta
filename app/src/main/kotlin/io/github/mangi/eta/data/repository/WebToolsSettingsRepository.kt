package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mangi.eta.agent.web.WebToolsRuntimeConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.DeepSeekWebConfig
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
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

internal object WebToolsSettingsRepository {
    private const val PREFS_NAME = "eta_web_tools_secrets"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEYSTORE_ALIAS = "eta_web_tools_credentials_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<WebToolsSettings> = SettingsDataStore.webToolsSettingsFlow()

    suspend fun settings(): WebToolsSettings = SettingsDataStore.webToolsSettings()

    suspend fun save(settings: WebToolsSettings) = SettingsDataStore.setWebToolsSettings(settings)

    suspend fun credentials(context: Context): WebToolsCredentials = withContext(Dispatchers.IO) {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIALS, null) ?: return@withContext WebToolsCredentials()
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            json.decodeFromString<WebToolsCredentials>(
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw WebToolsCredentialsUnavailable()
        }
    }

    suspend fun saveCredentials(context: Context, credentials: WebToolsCredentials) = withContext(Dispatchers.IO) {
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
            "网页工具凭据保存失败"
        }
    }

    /** 查找模型提供商中已配置的 DeepSeek API Key 与 Base URL，供网页工具零配置复用。 */
    suspend fun deepSeekProviderCredentials(): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val builtin = ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID)
                ?.takeIf { it.apiKey.isNotBlank() }
            val provider = builtin ?: ProviderRepository.allProviders().firstOrNull {
                ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.DEEPSEEK && it.apiKey.isNotBlank()
            }
            provider?.let { it.apiKey.trim() to it.baseUrl.trim() }
        }.getOrNull()
    }

    suspend fun resolveRuntimeConfig(context: Context): WebToolsRuntimeConfig = withContext(Dispatchers.IO) {
        val settings = runCatching { settings() }.getOrDefault(WebToolsSettings())
        val credentials = runCatching { credentials(context) }.getOrDefault(WebToolsCredentials())
        val providerDeepSeek = deepSeekProviderCredentials()
        val deepSeekApiKey = credentials.deepSeekApiKey.trim().ifEmpty {
            providerDeepSeek?.first.orEmpty()
        }
        val configuredDeepSeekUrl = settings.deepSeek.baseUrl.trim()
        val deepSeekBaseUrl = when {
            configuredDeepSeekUrl.isNotEmpty() && configuredDeepSeekUrl != DeepSeekWebConfig.DEFAULT_BASE_URL ->
                configuredDeepSeekUrl
            providerDeepSeek != null && providerDeepSeek.second.isNotEmpty() ->
                providerDeepSeek.second
            configuredDeepSeekUrl.isNotEmpty() -> configuredDeepSeekUrl
            else -> DeepSeekWebConfig.DEFAULT_BASE_URL
        }
        WebToolsRuntimeConfig(
            searchBackend = settings.searchBackend,
            fetchBackend = settings.fetchBackend,
            deepSeekBaseUrl = deepSeekBaseUrl,
            deepSeekApiKey = deepSeekApiKey,
            customSearch = settings.customSearch,
            customSearchApiKey = credentials.customSearchApiKey.trim(),
            customFetch = settings.customFetch,
            customFetchApiKey = credentials.customFetchApiKey.trim(),
        )
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

internal class WebToolsCredentialsUnavailable : Exception("网页工具凭据无法解密，请重新填写并保存")
