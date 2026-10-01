package io.github.mangi.eta.agent.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.security.KeyStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Tokens are stored as one AES-GCM ciphertext in an Android Keystore-backed key. */
internal class CodexCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    // Use a file rather than SharedPreferences so a second app process never
    // rereads a stale in-memory preference cache while holding the file lock.
    private val credentialFile = File(appContext.filesDir, CREDENTIAL_FILE_NAME)
    private val lockFile = File(appContext.filesDir, LOCK_FILE_NAME)

    fun read(): CodexCredentials? = readDetailed().credentials

    /** A non-secret diagnostic for recovery/UI decisions; never includes file contents. */
    internal fun readDetailed(): CodexCredentialReadResult = withCrossProcessLock {
        readLockedDetailed()
    }

    /**
     * Read while the caller already owns [withExclusiveLock].  Keeping this
     * separate is important for refresh-token rotation: the network request
     * must happen under the same cross-process lock as the final write.
     */
    internal fun readLocked(): CodexCredentials? {
        return readLockedDetailed().credentials
    }

    internal fun readLockedDetailed(): CodexCredentialReadResult {
        if (!credentialFile.isFile) {
            return CodexCredentialReadResult(CodexCredentialReadStatus.ABSENT)
        }
        val encoded = try {
            credentialFile.readText(Charsets.US_ASCII)
        } catch (failure: IOException) {
            return CodexCredentialReadResult(
                status = CodexCredentialReadStatus.UNREADABLE,
                diagnostic = failure.javaClass.simpleName,
            )
        }
        if (encoded.isBlank()) {
            return CodexCredentialReadResult(
                status = CodexCredentialReadStatus.CORRUPT,
                diagnostic = "blank_payload",
            )
        }
        return try {
            CodexCredentialReadResult(
                status = CodexCredentialReadStatus.VALID,
                credentials = decrypt(encoded),
            )
        } catch (failure: Throwable) {
            // The exception text can be provider/crypto implementation detail;
            // expose only its type, never ciphertext or token material.
            CodexCredentialReadResult(
                status = CodexCredentialReadStatus.CORRUPT,
                diagnostic = failure.javaClass.simpleName,
            )
        }
    }

    fun write(credentials: CodexCredentials) {
        withCrossProcessLock {
            writeLocked(credentials)
        }
    }

    /** Write while the caller already owns [withExclusiveLock]. */
    internal fun writeLocked(credentials: CodexCredentials) {
        if (credentials.accessToken.isBlank()) {
            throw CodexCredentialStoreException("missing_tokens")
        }
        try {
            credentialFile.parentFile?.mkdirs()
            val temporaryFile = File(credentialFile.parentFile, "${credentialFile.name}.tmp")
            try {
                FileOutputStream(temporaryFile).use { output ->
                    output.write(encrypt(credentials).toByteArray(Charsets.US_ASCII))
                    output.fd.sync()
                }
                CodexCredentialAtomicFile.replace(temporaryFile, credentialFile)
            } finally {
                temporaryFile.delete()
            }
        } catch (failure: CodexCredentialStoreException) {
            throw failure
        } catch (failure: Exception) {
            // Keystore/I/O messages can contain implementation details.  Keep
            // the original cause for internal debugging, but expose only a
            // stable stage code to callers and UI.
            throw CodexCredentialStoreException("write_failed", failure)
        }
    }

    /** Persist and verify both token fields before a login is reported successful. */
    fun writeAndReadBack(credentials: CodexCredentials): CodexCredentials = withCrossProcessLock {
        writeAndReadBackLocked(credentials)
    }

    internal fun writeAndReadBackLocked(credentials: CodexCredentials): CodexCredentials {
        if (credentials.accessToken.isBlank()) {
            throw CodexCredentialStoreException("missing_tokens")
        }
        writeLocked(credentials)
        val persisted = readLocked()
            ?: throw CodexCredentialStoreException("readback_failed")
        if (
            persisted.accessToken != credentials.accessToken ||
            persisted.refreshToken != credentials.refreshToken
        ) {
            throw CodexCredentialStoreException("readback_mismatch")
        }
        return persisted
    }

    fun clear() {
        withCrossProcessLock {
            clearLocked()
        }
    }

    /** Clear while the caller already owns [withExclusiveLock]. */
    internal fun clearLocked() {
        credentialFile.delete()
        File(credentialFile.parentFile, "${credentialFile.name}.tmp").delete()
    }

    private fun encrypt(credentials: CodexCredentials): String {
        val plaintext = CodexCredentialPayload.toJson(credentials)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): CodexCredentials {
        val payload = Base64.decode(encoded, Base64.DEFAULT)
        require(payload.size > GCM_IV_BYTES) { "Invalid Codex credential payload" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return CodexCredentialPayload.fromJson(
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8)),
        )
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Run a complete credential transaction under both the process and file
     * lock.  In particular, callers that refresh must keep this lock while
     * performing the token endpoint request so another Eta process cannot
     * consume a rotated refresh token concurrently.
     */
    internal fun <T> withExclusiveLock(block: () -> T): T = withCrossProcessLock(block)

    private fun <T> withCrossProcessLock(block: () -> T): T {
        PROCESS_LOCK.lock()
        try {
            lockFile.parentFile?.mkdirs()
            return RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.lock().use { block() }
            }
        } finally {
            PROCESS_LOCK.unlock()
        }
    }

    companion object {
        private const val CREDENTIAL_FILE_NAME = "eta_codex_oauth_v1.bin"
        private const val LOCK_FILE_NAME = "eta_codex_oauth.lock"
        private const val KEY_ALIAS = "eta_codex_oauth_aes_gcm_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val PROCESS_LOCK = ReentrantLock()
    }
}

internal enum class CodexCredentialReadStatus {
    ABSENT,
    VALID,
    CORRUPT,
    UNREADABLE,
}

internal data class CodexCredentialReadResult(
    val status: CodexCredentialReadStatus,
    val credentials: CodexCredentials? = null,
    val diagnostic: String? = null,
)

/** Stable, non-secret failure from the credential-store stage. */
internal class CodexCredentialStoreException(
    val errorCode: String,
    cause: Throwable? = null,
) : IllegalStateException("OpenAI Codex 本地凭据保存失败", cause)

/** Same-directory replacement keeps the encrypted file whole across a crash. */
internal object CodexCredentialAtomicFile {
    fun replace(temporaryFile: File, targetFile: File) {
        try {
            Files.move(
                temporaryFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: UnsupportedOperationException) {
            Files.move(
                temporaryFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

internal data class CodexCredentials(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMillis: Long?,
    val accountId: String? = null,
    val residency: String? = null,
) {
    fun isUsable(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() &&
            if (expiresAtEpochMillis == null) {
                // An access-only exchange may be opaque and have no exp.  It
                // is still usable until the backend rejects it; there is no
                // refresh operation to perform in the meantime.
                refreshToken.isNullOrBlank()
            } else {
                !CodexCompatibilityProfile.shouldRefresh(expiresAtEpochMillis, nowEpochMillis)
            }
}

/** Pure JSON representation shared by the encrypted store and JVM tests. */
internal object CodexCredentialPayload {
    fun toJson(credentials: CodexCredentials): JSONObject = JSONObject()
        .put("access_token", credentials.accessToken)
        .putOpt("refresh_token", credentials.refreshToken)
        .put("expires_at_epoch_ms", credentials.expiresAtEpochMillis)
        .putOpt("account_id", credentials.accountId)
        .putOpt("residency", credentials.residency)

    fun fromJson(json: JSONObject): CodexCredentials = CodexCredentials(
        accessToken = json.optString("access_token"),
        refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
        expiresAtEpochMillis = json.optLong("expires_at_epoch_ms").takeIf { it > 0L },
        accountId = json.optString("account_id").takeIf { it.isNotBlank() },
        residency = json.optString("residency").takeIf { it.isNotBlank() },
    )
}
