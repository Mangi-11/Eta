package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** MCP 凭据只以 Android Keystore 密文保存在 App 私有偏好中。 */
internal class McpSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun bearerToken(serverId: String): String? {
        val encoded = preferences.getString(tokenKey(serverId), null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun setBearerToken(serverId: String, token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            clear(serverId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
            .putString(tokenKey(serverId), Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        ) { AppStrings.get(R.string.repo_err_mcp_secret_save_failed, "MCP 凭据保存失败") }
    }

    @Synchronized
    fun clear(serverId: String) {
        check(preferences.edit().remove(tokenKey(serverId)).commit()) {
            AppStrings.get(R.string.repo_err_mcp_secret_delete_failed, "MCP 凭据删除失败")
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun tokenKey(serverId: String): String = "bearer_$serverId"

    private companion object {
        const val PREFERENCES_NAME = "eta_mcp_secrets"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_mcp_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
