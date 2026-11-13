package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.DeepSeekAsrConfig
import io.github.mangi.eta.data.model.DeepSeekTtsConfig
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
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

internal object SpeechSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<SpeechSettings> = SettingsDataStore.speechSettingsFlow()
    suspend fun settings(): SpeechSettings = SettingsDataStore.speechSettings()
    suspend fun save(settings: SpeechSettings) = SettingsDataStore.setSpeechSettings(settings)

    suspend fun deepSeekProviderCredentials(): Pair<String, String>? =
        WebToolsSettingsRepository.deepSeekProviderCredentials()

    /** 若 DeepSeek 语音未单独填写凭据，自动复用模型提供商中已配置的 DeepSeek Key 与地址。 */
    suspend fun resolveDeepSeekFallback(
        settings: SpeechSettings,
        credentials: SpeechCredentials,
    ): Pair<SpeechSettings, SpeechCredentials> {
        val needAsrKey = settings.asr == AsrProvider.DEEPSEEK && credentials.deepseekAsr.isBlank()
        val needTtsKey = settings.tts == TtsProvider.DEEPSEEK && credentials.deepseekTts.isBlank()
        if (!needAsrKey && !needTtsKey) return settings to credentials
        val provider = deepSeekProviderCredentials() ?: return settings to credentials
        val defaultUrl = DeepSeekAsrConfig().baseUrl
        val resolvedSettings = settings.copy(
            deepseekAsr = if (needAsrKey &&
                settings.deepseekAsr.baseUrl.trim().trimEnd('/') == defaultUrl &&
                provider.second.isNotBlank()
            ) {
                settings.deepseekAsr.copy(baseUrl = provider.second)
            } else {
                settings.deepseekAsr
            },
            deepseekTts = if (needTtsKey &&
                settings.deepseekTts.baseUrl.trim().trimEnd('/') == defaultUrl &&
                provider.second.isNotBlank()
            ) {
                settings.deepseekTts.copy(baseUrl = provider.second)
            } else {
                settings.deepseekTts
            },
        )
        val resolvedCredentials = SpeechCredentials(
            qwenAsr = credentials.qwenAsr,
            qwenTts = credentials.qwenTts,
            doubaoAsr = credentials.doubaoAsr,
            doubaoTts = credentials.doubaoTts,
            ossAccessKeyId = credentials.ossAccessKeyId,
            ossAccessKeySecret = credentials.ossAccessKeySecret,
            geminiTts = credentials.geminiTts,
            minimaxTts = credentials.minimaxTts,
            mimoAsr = credentials.mimoAsr,
            mimoTts = credentials.mimoTts,
            deepseekAsr = if (needAsrKey) provider.first else credentials.deepseekAsr,
            deepseekTts = if (needTtsKey) provider.first else credentials.deepseekTts,
        )
        return resolvedSettings to resolvedCredentials
    }

    suspend fun credentials(context: Context): SpeechCredentials = withContext(Dispatchers.IO) {
        val encoded = context.getSharedPreferences("eta_speech_secrets", Context.MODE_PRIVATE)
            .getString("credentials", null) ?: return@withContext SpeechCredentials()
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            json.decodeFromString<SpeechCredentials>(
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw SpeechCredentialsUnavailable()
        }
    }

    suspend fun saveCredentials(context: Context, credentials: SpeechCredentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
        check(context.getSharedPreferences("eta_speech_secrets", Context.MODE_PRIVATE).edit()
            .putString("credentials", Base64.getEncoder().encodeToString(encrypted)).commit()) {
            "语音凭据保存失败"
        }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("eta_speech_credentials_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("eta_speech_credentials_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}

internal class SpeechCredentialsUnavailable : Exception("语音凭据无法解密，请重新填写并保存")
