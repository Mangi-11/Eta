package io.github.mangi.eta.agent.tool.chat

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 读取聊天应用的图片缓存；系统个人数据由原生 Provider 通道负责。 */
internal class ChatImageSearchTools(private val root: BoundedRootCommandExecutor) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? =
        when (name) {
            "search_qq_chat_images" -> searchQqChatImages(args)
            "search_wechat_chat_images" -> searchWechatChatImages(args)
            else -> null
        }

    private fun searchQqChatImages(args: JSONObject): AgentModelClient.ToolResult =
        searchPrivateChatImages(
            tool = "search_qq_chat_images",
            directory = QQ_CHAT_IMAGES_DIRECTORY,
            pathFilter =
                "\\( -path '*/chatimg/*' -o -path '*/chatraw/*' -o -path '*/chatthumb/*' \\)",
            unavailableCode = "QQ_CHAT_IMAGES_UNAVAILABLE",
            unavailableMessage = "QQ 聊天图片缓存暂时不可访问",
            args = args,
            kind = ::qqImageKind,
        )

    private fun searchWechatChatImages(args: JSONObject): AgentModelClient.ToolResult =
        searchPrivateChatImages(
            tool = "search_wechat_chat_images",
            directory = WECHAT_CHAT_IMAGES_DIRECTORY,
            pathFilter = "-path '*/image/*'",
            unavailableCode = "WECHAT_CHAT_IMAGES_UNAVAILABLE",
            unavailableMessage = "微信聊天图片缓存暂时不可访问",
            args = args,
            kind = { "image" },
        )

    private fun searchPrivateChatImages(
        tool: String,
        directory: String,
        pathFilter: String,
        unavailableCode: String,
        unavailableMessage: String,
        args: JSONObject,
        kind: (String) -> String,
    ): AgentModelClient.ToolResult {
        val result =
            root.execute(
                "test -d $directory && " +
                    "find $directory -type f $pathFilter -size -${CHAT_IMAGE_MAX_FILE_BYTES}c " +
                    "-printf '%T@|%s|%p\\n' 2>/dev/null | sort -rn | head -n $CHAT_IMAGE_CANDIDATE_LIMIT",
                timeoutMillis = QUERY_TIMEOUT_MS,
                maxOutputBytes = MAX_OUTPUT_BYTES,
            )
        if (!result.ok) return sensitive(error(unavailableCode, unavailableMessage))

        val keyword = args.optString("query").trim().lowercase(Locale.ROOT)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val candidates =
            result.stdout
                .lineSequence()
                .mapNotNull { privateChatImageRow(it, directory, kind) }
                .toList()
        val items =
            candidates
                .asSequence()
                .filter {
                    keyword.isBlank() ||
                        it.optString("path").lowercase(Locale.ROOT).contains(keyword)
                }
                .take(limit)
                .toList()
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("items", JSONArray(items))
                .put("count", items.size)
                .put(
                    "truncated",
                    result.truncated ||
                        candidates.size == CHAT_IMAGE_CANDIDATE_LIMIT ||
                        items.size == limit,
                )
                .toString()
        )
    }

    private fun privateChatImageRow(
        line: String,
        directory: String,
        kind: (String) -> String,
    ): JSONObject? {
        val fields = line.split('|', limit = 3)
        if (fields.size != 3) return null
        val modifiedAt = fields[0].toDoubleOrNull()?.toLong() ?: return null
        val size = fields[1].toLongOrNull() ?: return null
        val path = fields[2]
        if (!path.startsWith("$directory/")) return null
        return JSONObject()
            .put("path", path)
            .put("name", path.substringAfterLast('/'))
            .put("kind", kind(path))
            .put("modified_at_epoch_seconds", modifiedAt)
            .put("size_bytes", size)
    }

    private fun qqImageKind(path: String): String =
        when {
            "/chatraw/" in path -> "original"
            "/chatimg/" in path -> "image"
            "/chatthumb/" in path -> "thumbnail"
            else -> "other"
        }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String) =
        AgentModelClient.ToolResult(content = content, sensitive = true)

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val QUERY_TIMEOUT_MS = 15_000L
        const val MAX_OUTPUT_BYTES = 512 * 1024
        const val QQ_CHAT_IMAGES_DIRECTORY =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
        const val WECHAT_CHAT_IMAGES_DIRECTORY =
            "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg"
        const val CHAT_IMAGE_CANDIDATE_LIMIT = 120
        const val CHAT_IMAGE_MAX_FILE_BYTES = 128L * 1024 * 1024
    }
}
