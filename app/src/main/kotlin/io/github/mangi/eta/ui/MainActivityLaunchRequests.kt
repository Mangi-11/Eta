package io.github.mangi.eta.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.app.ArchivedConversationRequest
import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS

data class IncomingShareRequest(
    val uris: List<String> = emptyList(),
    val mimeType: String? = null,
    val text: String? = null,
)

internal data class MainActivityLaunchRequests(
    val assistantConversationKey: String? = null,
    val conversationId: String? = null,
    val archivedConversation: ArchivedConversationRequest? = null,
    val sharedContent: IncomingShareRequest? = null,
    val speechSettings: Boolean = false,
    val terminal: Boolean = false,
) {
    fun accept(intent: Intent?): MainActivityLaunchRequests = when (intent?.action) {
        MainActivity.ACTION_OPEN_TERMINAL -> copy(terminal = true)
        ACTION_SPEECH_SETTINGS -> copy(speechSettings = true)
        MainActivity.ACTION_OPEN_CONVERSATION_ID -> copy(
            conversationId = intent.nonBlankExtra(MainActivity.EXTRA_CONVERSATION_ID),
        )
        MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION -> {
            val source = intent.nonBlankExtra(MainActivity.EXTRA_HANDOFF_SOURCE)
            val key = intent.nonBlankExtra(MainActivity.EXTRA_CONVERSATION_KEY)
            copy(
                archivedConversation = if (source != null && key != null) {
                    ArchivedConversationRequest(source, key)
                } else null,
            )
        }
        EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION -> copy(
            assistantConversationKey = intent.nonBlankExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY),
        )
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        -> intent.toIncomingShareRequest()?.let { request ->
            copy(sharedContent = request)
        } ?: this
        else -> this
    }

    fun saveTo(outState: Bundle) {
        outState.putBundle(STATE_KEY, Bundle().apply {
            putString("assistant_conversation_key", assistantConversationKey)
            putString("conversation_id", conversationId)
            putString("archive_source", archivedConversation?.source)
            putString("archive_conversation_key", archivedConversation?.conversationKey)
            sharedContent?.let { share ->
                putStringArrayList("share_uris", ArrayList(share.uris))
                putString("share_mime_type", share.mimeType)
                putString("share_text", share.text)
            }
            putBoolean("speech_settings", speechSettings)
            putBoolean("terminal", terminal)
        })
    }

    companion object {
        private const val STATE_KEY = "eta_pending_launch_requests"

        fun restore(savedInstanceState: Bundle?, intent: Intent?): MainActivityLaunchRequests {
            // 系统可能保留最初的启动 Intent；空的待办状态也必须保存，防止重建后重放已消费的跳转。
            val saved = savedInstanceState?.getBundle(STATE_KEY)
                ?: return MainActivityLaunchRequests().accept(intent)
            val source = saved.getString("archive_source")
            val key = saved.getString("archive_conversation_key")
            val shareUris = saved.getStringArrayList("share_uris")
                ?.mapNotNull { it?.takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()
            val shareText = saved.getString("share_text")?.takeIf(String::isNotBlank)
            val shareMimeType = saved.getString("share_mime_type")?.takeIf(String::isNotBlank)
            return MainActivityLaunchRequests(
                assistantConversationKey = saved.getString("assistant_conversation_key"),
                conversationId = saved.getString("conversation_id"),
                archivedConversation = if (source != null && key != null) {
                    ArchivedConversationRequest(source, key)
                } else null,
                sharedContent = if (shareUris.isNotEmpty() || shareText != null) {
                    IncomingShareRequest(
                        uris = shareUris,
                        mimeType = shareMimeType,
                        text = shareText,
                    )
                } else null,
                speechSettings = saved.getBoolean("speech_settings"),
                terminal = saved.getBoolean("terminal"),
            )
        }
    }
}

private fun Intent.toIncomingShareRequest(): IncomingShareRequest? {
    val streamUris = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java),
        )
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
            this,
            Intent.EXTRA_STREAM,
            Uri::class.java,
        ).orEmpty()
        else -> emptyList()
    }
    val clipUris = buildList {
        val data = clipData ?: return@buildList
        for (index in 0 until data.itemCount) {
            data.getItemAt(index)?.uri?.let(::add)
        }
    }
    val uris = (streamUris + clipUris)
        .mapNotNull { uri -> uri.toString().takeIf(String::isNotBlank) }
        .distinct()
    val rawText = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        ?.takeIf(String::isNotBlank)
        ?: if (uris.isEmpty()) {
            clipData?.let { data ->
                buildList {
                    for (index in 0 until data.itemCount) {
                        data.getItemAt(index)?.text?.toString()?.trim()
                            ?.takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }.joinToString("\n").takeIf(String::isNotBlank)
            }
        } else {
            null
        }
    val text = rawText?.takeUnless { it in uris }
    if (uris.isEmpty() && text == null) return null
    return IncomingShareRequest(
        uris = uris,
        mimeType = type?.takeIf(String::isNotBlank),
        text = text,
    )
}

private fun Intent.nonBlankExtra(key: String): String? = getStringExtra(key)?.takeIf(String::isNotBlank)
