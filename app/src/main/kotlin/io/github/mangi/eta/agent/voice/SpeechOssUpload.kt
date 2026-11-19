package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechOssConfig
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** 日志先于上传落盘；进程被杀或上传结果不明时仍能找回待删除对象。 */
internal class SpeechOssUpload(
    context: Context,
    private val send: suspend (okhttp3.Request) -> Unit = { request -> SpeechHttp.read(request) { Unit } },
) {
    private val preferences = context.applicationContext.getSharedPreferences("eta_speech_oss_cleanup", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Pending(val config: SpeechOssConfig, val key: String, val accessKeyId: String)

    suspend fun <T> withAudio(config: SpeechOssConfig, secrets: SpeechCredentials, wav: ByteArray,
        consume: suspend (String) -> T): T {
        val key = config.prefix.trim('/').let { if (it.isBlank()) "" else "$it/" } + UUID.randomUUID() + ".wav"
        val entry = json.encodeToString(Pending(config, key, secrets.ossAccessKeyId))
        remember(entry)
        var uploadFinished = false
        try {
            send(OssSpeechSigner.request(config, secrets, key, "PUT", wav.toRequestBody("audio/wav".toMediaType())))
            uploadFinished = true
            return consume(OssSpeechSigner.readUrl(config, secrets, key))
        } finally {
            withContext(NonCancellable) {
                try { cleanup(entry, secrets, removeOnSuccess = uploadFinished) }
                finally { synchronized(LOCK) { active.remove(entry) } }
            }
        }
    }

    suspend fun retryPending(context: Context) {
        val pending = synchronized(LOCK) { preferences.getStringSet("pending", emptySet())!!.toList() }
        if (pending.isEmpty()) return
        retryPending(SpeechSettingsRepository.credentials(context))
    }

    suspend fun retryPending(secrets: SpeechCredentials) {
        val pending = synchronized(LOCK) { preferences.getStringSet("pending", emptySet())!!.toList() }
        for (entry in pending.take(20)) {
            if (synchronized(LOCK) { entry !in active }) cleanup(entry, secrets, removeOnSuccess = true)
        }
    }

    private suspend fun cleanup(entry: String, secrets: SpeechCredentials, removeOnSuccess: Boolean) {
        try {
            val pending = json.decodeFromString<Pending>(entry)
            if (pending.accessKeyId != secrets.ossAccessKeyId || secrets.ossAccessKeySecret.isBlank()) return
            withTimeout(10_000) {
                send(OssSpeechSigner.request(pending.config, secrets, pending.key, "DELETE"))
            }
            if (removeOnSuccess) synchronized(LOCK) {
                val remaining = preferences.getStringSet("pending", emptySet())!!.toMutableSet().apply { remove(entry) }
                check(preferences.edit().putStringSet("pending", remaining).commit())
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            AndroidAgentLogger.warn("Eta speech OSS cleanup pending: type=${error.javaClass.simpleName}")
        }
    }

    private fun remember(entry: String) = synchronized(LOCK) {
        val pending = preferences.getStringSet("pending", emptySet())!!.toMutableSet()
        if (pending.size >= 100) {
            throw SpeechFailure(
                SpeechErrorCode.STORAGE,
                AppStrings.get(
                    R.string.voice_i18n_oss_pending_cleanup_limit,
                    "待清理录音较多，请检查 OSS 删除权限后重启 Eta",
                ),
            )
        }
        pending += entry
        if (!preferences.edit().putStringSet("pending", pending).commit()) {
            throw SpeechFailure(
                SpeechErrorCode.STORAGE,
                AppStrings.get(R.string.voice_i18n_oss_cleanup_record_failed, "无法记录录音清理信息"),
            )
        }
        active += entry
    }

    private companion object { val LOCK = Any(); val active = mutableSetOf<String>() }
}
