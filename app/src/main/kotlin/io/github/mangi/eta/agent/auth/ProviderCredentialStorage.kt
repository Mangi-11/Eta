package io.github.mangi.eta.agent.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface ProviderCredentialStorage {
    fun read(providerId: String): ProviderCredential?
    fun write(providerId: String, credential: ProviderCredential)
    fun delete(providerId: String)
}

internal class EncryptedProviderCredentialStorage(context: Context) : ProviderCredentialStorage {
    private val directory = File(context.applicationContext.noBackupFilesDir, "provider_credentials")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun read(providerId: String): ProviderCredential? {
        val file = file(providerId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return storageOperation {
            val payload = readBounded(file, MAX_BYTES)
            check(payload.size in (IV_SIZE + 1)..MAX_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, payload, 0, IV_SIZE))
            cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE)
            try {
                json.decodeFromString<ProviderCredential>(plaintext.toString(Charsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        }
    }

    @Synchronized
    override fun write(providerId: String, credential: ProviderCredential) = storageOperation {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
        val plaintext = json.encodeToString(credential).toByteArray(Charsets.UTF_8)
        try {
            val payload = cipher.iv + cipher.doFinal(plaintext)
            check(payload.size <= MAX_BYTES)
            writeAtomically(file(providerId), payload)
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    override fun delete(providerId: String) = storageOperation {
        val file = file(providerId)
        file.delete()
        check(listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"))
            .none(File::exists))
    }

    private fun file(providerId: String): AtomicFile {
        require(providerId.isNotBlank())
        val hash = MessageDigest.getInstance("SHA-256").digest(providerId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.bin"))
    }

    private fun writeAtomically(file: AtomicFile, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun readBounded(file: AtomicFile, maxBytes: Int): ByteArray = file.openRead().use { input ->
        val buffer = ByteArray(maxBytes + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        check(size <= maxBytes)
        buffer.copyOf(size)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw ProviderAuthException("credential_key_unavailable", "登录凭据的密钥不可用，请退出后重新登录")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private inline fun <T> storageOperation(block: () -> T): T = try {
        block()
    } catch (error: ProviderAuthException) {
        throw error
    } catch (_: Exception) {
        throw ProviderAuthException("credential_storage_failed", "无法读写登录凭据，请退出后重新登录")
    }

    private companion object {
        const val KEY_ALIAS = "eta_provider_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val MAX_BYTES = 262_144
    }
}
