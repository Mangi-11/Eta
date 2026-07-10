package fuck.andes.agent.runtime

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.data.model.CustomBody
import fuck.andes.data.model.CustomHeader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AgentRuntime 跨进程通信协议。
 *
 * 入口进程通过 bind + Messenger 与模块自身进程的 [AgentRuntimeService] 通信：
 * 发送一次运行请求，接收事件流和最终结果。
 *
 * 不引入 AIDL：消息体只用 [Bundle]（Int/Boolean/String/ArrayList），跨进程传递无序列化门槛。
 */
internal object AgentRuntimeWire {
    /** bind 获取服务端 Messenger 的 Intent action。 */
    const val ACTION_BIND = "fuck.andes.agent.runtime.BIND"

    // Messenger.what
    /** client -> service：开始一次 Agent 运行，[Message.replyTo] 携带 client Messenger。 */
    const val MSG_START_RUN = 1

    /** service -> client：推送一个 [AgentEvent]。 */
    const val MSG_EVENT = 2

    /** service -> client：最终结果。 */
    const val MSG_RESULT = 3

    /** client -> service：取消当前运行。 */
    const val MSG_CANCEL = 4

    /** client -> service：确认一个最终结果已经被入口层成功展示。 */
    const val MSG_ACK_RESULT = 5

    /** client -> service：拉取尚未被入口层确认展示的最终结果。 */
    const val MSG_DRAIN_RESULTS = 6

    /** service -> client：返回一组尚未确认展示的最终结果。 */
    const val MSG_DRAIN_RESULTS_RESPONSE = 7

    private const val MODULE_PACKAGE = "fuck.andes"
    private const val SERVICE_CLASS = "fuck.andes.agent.runtime.AgentRuntimeService"

    private const val KEY_TYPE = "type"
    private const val KEY_RUN_ID = "run_id"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_PROVIDER_ID = "provider_id"
    private const val KEY_PROVIDER_NAME = "provider_name"
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_PROVIDER_SOURCE_TYPE = "provider_source_type"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_MODEL_DISPLAY_NAME = "model_display_name"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_ANTHROPIC_VERSION = "anthropic_version"
    private const val KEY_OPENAI_ENDPOINT_MODE = "openai_endpoint_mode"
    private const val KEY_TERMINAL_TOOLS = "terminal_tools"
    private const val KEY_BROWSER_TOOLS = "browser_tools"
    private const val KEY_THINKING_ENABLED = "thinking_enabled"
    private const val KEY_EXTRA_BODY_JSON = "extra_body_json"
    private const val KEY_CUSTOM_HEADERS_JSON = "custom_headers_json"
    private const val KEY_CUSTOM_BODY_JSON = "custom_body_json"
    private const val KEY_IMAGES = "images"
    private const val KEY_HISTORY = "history"
    private const val KEY_CONTENT_JSON = "content_json"
    private const val KEY_TOOL_CALL_ID = "tool_call_id"
    private const val KEY_TOOL_CALLS_JSON = "tool_calls_json"
    private const val KEY_ROLE = "role"
    private const val KEY_DATA_URL = "data_url"
    private const val KEY_MIME_TYPE = "mime_type"
    private const val KEY_BYTES = "bytes"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SOURCE = "source"
    private const val KEY_OK = "ok"
    private const val KEY_CONTENT = "content"
    private const val KEY_REASONING_CONTENT = "reasoning_content"
    private const val KEY_ERROR = "error"
    private const val KEY_RESULT = "result"
    private const val KEY_TRANSCRIPT_JSON = "transcript_json"
    private const val KEY_HANDOFF = "handoff"
    private const val KEY_HANDOFF_ID = "handoff_id"
    private const val KEY_HANDOFF_SOURCE = "handoff_source"
    private const val KEY_HANDOFF_PAYLOAD = "handoff_payload"
    private const val KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION =
        "handoff_dismiss_entry_surface_on_foreground_operation"
    private const val LEGACY_BREENO_HANDOFF_SOURCE = "breeno"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_RESULTS = "results"

    data class RunRequest(
        val runId: String,
        val prompt: String,
        val config: AgentModelClient.ModelConfig,
        val images: List<AgentModelClient.ModelImage>,
        val history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val handoff: EntryHandoff? = null
    )

    data class RunResult(
        val runId: String,
        val ok: Boolean,
        val content: String,
        val error: String? = null,
        val reasoningContent: String = "",
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    )

    data class EntryHandoff(
        val id: String,
        val source: String,
        val payload: String,
        val dismissEntrySurfaceOnForegroundOperation: Boolean = false
    )

    data class CompletedRun(
        val handoff: EntryHandoff,
        val result: RunResult,
        val createdAt: Long
    )

    fun serviceIntent(): Intent =
        Intent(ACTION_BIND).setComponent(ComponentName(MODULE_PACKAGE, SERVICE_CLASS))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun toBundle(request: RunRequest): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, request.runId)
        putString(KEY_PROMPT, request.prompt)
        putString(KEY_PROVIDER_ID, request.config.providerId)
        putString(KEY_PROVIDER_NAME, request.config.providerName)
        putString(KEY_PROVIDER_TYPE, request.config.providerType)
        putString(KEY_PROVIDER_SOURCE_TYPE, request.config.providerSourceType)
        putString(KEY_BASE_URL, request.config.baseUrl)
        putString(KEY_API_KEY, request.config.apiKey)
        putString(KEY_MODEL, request.config.model)
        putString(KEY_MODEL_DISPLAY_NAME, request.config.modelDisplayName)
        putString(KEY_SYSTEM_PROMPT, request.config.systemPrompt)
        putString(KEY_ANTHROPIC_VERSION, request.config.anthropicVersion)
        putString(KEY_OPENAI_ENDPOINT_MODE, request.config.openAiEndpointMode)
        putBoolean(KEY_TERMINAL_TOOLS, request.config.terminalTools)
        putBoolean(KEY_BROWSER_TOOLS, request.config.browserTools)
        putBoolean(KEY_THINKING_ENABLED, request.config.thinkingEnabled)
        putString(KEY_EXTRA_BODY_JSON, request.config.extraBodyJson)
        putString(KEY_CUSTOM_HEADERS_JSON, json.encodeToString(request.config.customHeaders))
        putString(KEY_CUSTOM_BODY_JSON, json.encodeToString(request.config.customBody))
        request.handoff?.let { putBundle(KEY_HANDOFF, toBundle(it)) }
        putParcelableArrayList(
            KEY_HISTORY,
            ArrayList(request.history.map { message ->
                Bundle().apply {
                    putString(KEY_ROLE, message.role)
                    putString(KEY_CONTENT, message.content)
                    putString(KEY_CONTENT_JSON, message.contentJson)
                    putString(KEY_TOOL_CALL_ID, message.toolCallId)
                    putString(KEY_REASONING_CONTENT, message.reasoningContent)
                    putString(KEY_TOOL_CALLS_JSON, message.toolCallsJson)
                }
            })
        )
        putParcelableArrayList(
            KEY_IMAGES,
            ArrayList(request.images.map { image ->
                Bundle().apply {
                    putString(KEY_DATA_URL, image.dataUrl)
                    putString(KEY_MIME_TYPE, image.mimeType)
                    putInt(KEY_BYTES, image.bytes)
                    image.width?.let { putInt(KEY_WIDTH, it) }
                    image.height?.let { putInt(KEY_HEIGHT, it) }
                    putString(KEY_SOURCE, image.source)
                }
            })
        )
    }

    fun runRequestFromBundle(bundle: Bundle): RunRequest =
        RunRequest(
            runId = bundle.getString(KEY_RUN_ID).orEmpty(),
            prompt = bundle.getString(KEY_PROMPT).orEmpty(),
            config = AgentModelClient.ModelConfig(
                providerId = bundle.getString(KEY_PROVIDER_ID).orEmpty(),
                providerName = bundle.getString(KEY_PROVIDER_NAME).orEmpty(),
                providerType = bundle.getString(KEY_PROVIDER_TYPE).orEmpty()
                    .ifBlank { fuck.andes.data.model.ProviderTypes.OPENAI_COMPATIBLE },
                providerSourceType = bundle.getString(KEY_PROVIDER_SOURCE_TYPE).orEmpty(),
                baseUrl = bundle.getString(KEY_BASE_URL).orEmpty(),
                apiKey = bundle.getString(KEY_API_KEY).orEmpty(),
                model = bundle.getString(KEY_MODEL).orEmpty(),
                modelDisplayName = bundle.getString(KEY_MODEL_DISPLAY_NAME).orEmpty(),
                systemPrompt = bundle.getString(KEY_SYSTEM_PROMPT).orEmpty(),
                anthropicVersion = bundle.getString(KEY_ANTHROPIC_VERSION).orEmpty()
                    .ifBlank { fuck.andes.data.model.AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
                openAiEndpointMode = bundle.getString(KEY_OPENAI_ENDPOINT_MODE).orEmpty()
                    .ifBlank { fuck.andes.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS },
                terminalTools = bundle.getBoolean(KEY_TERMINAL_TOOLS),
                browserTools = if (bundle.containsKey(KEY_BROWSER_TOOLS)) {
                    bundle.getBoolean(KEY_BROWSER_TOOLS)
                } else {
                    true
                },
                thinkingEnabled = bundle.getBoolean(KEY_THINKING_ENABLED),
                extraBodyJson = bundle.getString(KEY_EXTRA_BODY_JSON).orEmpty(),
                customHeaders = decodeCustomHeaders(bundle.getString(KEY_CUSTOM_HEADERS_JSON)),
                customBody = decodeCustomBody(bundle.getString(KEY_CUSTOM_BODY_JSON))
            ),
            history = bundle.getParcelableArrayList(KEY_HISTORY, Bundle::class.java).orEmpty().map { message ->
                AgentModelClient.ConversationMessage(
                    role = message.getString(KEY_ROLE).orEmpty(),
                    content = message.getString(KEY_CONTENT).orEmpty(),
                    contentJson = message.getString(KEY_CONTENT_JSON).orEmpty(),
                    toolCallId = message.getString(KEY_TOOL_CALL_ID).orEmpty(),
                    reasoningContent = message.getString(KEY_REASONING_CONTENT).orEmpty(),
                    toolCallsJson = message.getString(KEY_TOOL_CALLS_JSON).orEmpty(),
                )
            },
            images = bundle.getParcelableArrayList(KEY_IMAGES, Bundle::class.java).orEmpty().map { image ->
                AgentModelClient.ModelImage(
                    dataUrl = image.getString(KEY_DATA_URL).orEmpty(),
                    mimeType = image.getString(KEY_MIME_TYPE).orEmpty(),
                    bytes = image.getInt(KEY_BYTES),
                    width = image.optionalInt(KEY_WIDTH),
                    height = image.optionalInt(KEY_HEIGHT),
                    source = image.getString(KEY_SOURCE).orEmpty()
                )
            },
            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle)
        )

    fun toBundle(handoff: EntryHandoff): Bundle = Bundle().apply {
        putString(KEY_HANDOFF_ID, handoff.id)
        putString(KEY_HANDOFF_SOURCE, handoff.source)
        putString(KEY_HANDOFF_PAYLOAD, handoff.payload)
        putBoolean(
            KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION,
            handoff.dismissEntrySurfaceOnForegroundOperation
        )
    }

    fun entryHandoffFromBundle(bundle: Bundle): EntryHandoff {
        val source = bundle.getString(KEY_HANDOFF_SOURCE).orEmpty()
        return EntryHandoff(
            id = bundle.getString(KEY_HANDOFF_ID).orEmpty(),
            source = source,
            payload = bundle.getString(KEY_HANDOFF_PAYLOAD).orEmpty(),
            dismissEntrySurfaceOnForegroundOperation = if (
                bundle.containsKey(KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION)
            ) {
                bundle.getBoolean(KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION)
            } else {
                source == LEGACY_BREENO_HANDOFF_SOURCE
            }
        )
    }

    fun toBundle(result: RunResult): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, result.runId)
        putBoolean(KEY_OK, result.ok)
        putString(KEY_CONTENT, result.content)
        putString(KEY_REASONING_CONTENT, result.reasoningContent)
        putString(KEY_ERROR, result.error)
        putString(KEY_TRANSCRIPT_JSON, json.encodeToString(result.transcript))
    }

    fun runResultFromBundle(bundle: Bundle): RunResult =
        RunResult(
            runId = bundle.getString(KEY_RUN_ID).orEmpty(),
            ok = bundle.getBoolean(KEY_OK),
            content = bundle.getString(KEY_CONTENT).orEmpty(),
            error = bundle.getString(KEY_ERROR),
            reasoningContent = bundle.getString(KEY_REASONING_CONTENT).orEmpty(),
            transcript = decodeConversationHistory(bundle.getString(KEY_TRANSCRIPT_JSON)),
        )

    fun toBundle(completedRun: CompletedRun): Bundle = Bundle().apply {
        putBundle(KEY_HANDOFF, toBundle(completedRun.handoff))
        putBundle(KEY_RESULT, toBundle(completedRun.result))
        putLong(KEY_CREATED_AT, completedRun.createdAt)
    }

    fun completedRunFromBundle(bundle: Bundle): CompletedRun =
        CompletedRun(
            handoff = entryHandoffFromBundle(bundle.getBundle(KEY_HANDOFF) ?: Bundle()),
            result = runResultFromBundle(bundle.getBundle(KEY_RESULT) ?: Bundle()),
            createdAt = bundle.getLong(KEY_CREATED_AT)
        )

    fun completedRunsToBundle(results: List<CompletedRun>): Bundle = Bundle().apply {
        putParcelableArrayList(KEY_RESULTS, ArrayList(results.map(::toBundle)))
    }

    fun completedRunsFromBundle(bundle: Bundle): List<CompletedRun> =
        bundle.getParcelableArrayList(KEY_RESULTS, Bundle::class.java)
            .orEmpty()
            .map(::completedRunFromBundle)

    fun ackBundle(runId: String): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
    }

    fun runIdFromBundle(bundle: Bundle): String =
        bundle.getString(KEY_RUN_ID).orEmpty()

    /** 将 [AgentEvent] 打包为可跨进程传递的 [Bundle]。 */
    fun eventToBundle(event: AgentEvent): Bundle = Bundle().apply {
        when (event) {
            is AgentEvent.RunStarted -> {
                putString(KEY_TYPE, "run_started")
                putInt("initial_images", event.initialImages)
                putInt("initial_image_bytes", event.initialImageBytes)
                putInt("tool_count", event.toolCount)
                putBoolean("terminal_tools", event.terminalTools)
            }

            is AgentEvent.RoundStarted -> {
                putString(KEY_TYPE, "round_started")
                putInt("round", event.round)
                putInt("message_count", event.messageCount)
            }

            is AgentEvent.ProviderRequestStarted -> {
                putString(KEY_TYPE, "provider_request_started")
                putInt("round", event.round)
            }

            is AgentEvent.ProviderResponseStarted -> {
                putString(KEY_TYPE, "provider_response_started")
                putInt("round", event.round)
                putInt("http_code", event.httpCode)
            }

            is AgentEvent.AssistantBlockStart -> {
                putString(KEY_TYPE, "assistant_block_start")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putString("block_id", event.blockId)
                putString("name", event.name)
            }

            is AgentEvent.AssistantBlockDelta -> {
                putString(KEY_TYPE, "assistant_block_delta")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putInt("delta_chars", event.deltaChars)
                putString("delta", event.delta)
            }

            is AgentEvent.AssistantBlockEnd -> {
                putString(KEY_TYPE, "assistant_block_end")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putString("block_id", event.blockId)
                putString("name", event.name)
                putInt("content_chars", event.contentChars)
            }

            is AgentEvent.AssistantReceived -> {
                putString(KEY_TYPE, "assistant_received")
                putInt("round", event.round)
                putInt("content_chars", event.contentChars)
                putString("reasoning_content", event.reasoningContent)
                putStringArrayList("tool_names", ArrayList(event.toolNames))
            }

            is AgentEvent.UsageReceived -> {
                putString(KEY_TYPE, "usage_received")
                putInt("round", event.round)
                putTokenUsage(event.usage)
            }

            is AgentEvent.UserSupplementReceived -> {
                putString(KEY_TYPE, "user_supplement_received")
                putInt("index", event.index)
                putString("text", event.text)
            }

            is AgentEvent.ToolStarted -> {
                putString(KEY_TYPE, "tool_started")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("args_preview", event.argsPreview)
            }

            is AgentEvent.ToolFinished -> {
                putString(KEY_TYPE, "tool_finished")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("result_summary", event.resultSummary)
                putInt("image_count", event.imageCount)
                putInt("image_bytes", event.imageBytes)
            }

            is AgentEvent.ToolImagesAttached -> {
                putString(KEY_TYPE, "tool_images_attached")
                putInt("round", event.round)
                putString("tool_name", event.toolName)
                putInt("image_count", event.imageCount)
                putInt("image_bytes", event.imageBytes)
            }

            is AgentEvent.RunFinished -> {
                putString(KEY_TYPE, "run_finished")
                putInt("round", event.round)
                putInt("content_chars", event.contentChars)
            }

            is AgentEvent.RunFailed -> {
                putString(KEY_TYPE, "run_failed")
                putString("reason", event.reason)
            }
        }
    }

    /** 将 [Bundle] 还原为 [AgentEvent]，无法识别时返回 null。 */
    fun eventFromBundle(bundle: Bundle): AgentEvent? = when (bundle.getString(KEY_TYPE)) {
        "run_started" -> AgentEvent.RunStarted(
            initialImages = bundle.getInt("initial_images"),
            initialImageBytes = bundle.getInt("initial_image_bytes"),
            toolCount = bundle.getInt("tool_count"),
            terminalTools = bundle.getBoolean("terminal_tools"),
        )

        "round_started" -> AgentEvent.RoundStarted(
            round = bundle.getInt("round"),
            messageCount = bundle.getInt("message_count"),
        )

        "provider_request_started" -> AgentEvent.ProviderRequestStarted(
            round = bundle.getInt("round"),
        )

        "provider_response_started" -> AgentEvent.ProviderResponseStarted(
            round = bundle.getInt("round"),
            httpCode = bundle.getInt("http_code"),
        )

        "assistant_block_start" -> AgentEvent.AssistantBlockStart(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            blockId = bundle.getString("block_id"),
            name = bundle.getString("name"),
        )

        "assistant_block_delta" -> AgentEvent.AssistantBlockDelta(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            deltaChars = bundle.getInt("delta_chars"),
            delta = bundle.getString("delta").orEmpty(),
        )

        "assistant_block_end" -> AgentEvent.AssistantBlockEnd(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            blockId = bundle.getString("block_id"),
            name = bundle.getString("name"),
            contentChars = bundle.getInt("content_chars"),
        )

        "assistant_received" -> AgentEvent.AssistantReceived(
            round = bundle.getInt("round"),
            contentChars = bundle.getInt("content_chars"),
            reasoningContent = bundle.getString("reasoning_content").orEmpty(),
            toolNames = bundle.getStringArrayList("tool_names").orEmpty(),
        )

        "usage_received" -> AgentEvent.UsageReceived(
            round = bundle.getInt("round"),
            usage = bundle.getTokenUsage(),
        )

        "user_supplement_received" -> AgentEvent.UserSupplementReceived(
            index = bundle.getInt("index"),
            text = bundle.getString("text").orEmpty(),
        )

        "tool_started" -> AgentEvent.ToolStarted(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            argsPreview = bundle.getString("args_preview").orEmpty(),
        )

        "tool_finished" -> AgentEvent.ToolFinished(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            resultSummary = bundle.getString("result_summary").orEmpty(),
            imageCount = bundle.getInt("image_count"),
            imageBytes = bundle.getInt("image_bytes"),
        )

        "tool_images_attached" -> AgentEvent.ToolImagesAttached(
            round = bundle.getInt("round"),
            toolName = bundle.getString("tool_name").orEmpty(),
            imageCount = bundle.getInt("image_count"),
            imageBytes = bundle.getInt("image_bytes"),
        )

        "run_finished" -> AgentEvent.RunFinished(
            round = bundle.getInt("round"),
            contentChars = bundle.getInt("content_chars"),
        )

        "run_failed" -> AgentEvent.RunFailed(
            reason = bundle.getString("reason").orEmpty(),
        )

        else -> null
    }

    private fun Bundle.optionalInt(key: String): Int? =
        if (containsKey(key)) getInt(key) else null

    private fun Bundle.putTokenUsage(usage: AgentTokenUsage) {
        usage.contextTokens?.let { putInt("usage_context", it) }
        usage.inputTokens?.let { putInt("usage_input", it) }
        usage.outputTokens?.let { putInt("usage_output", it) }
        usage.reasoningTokens?.let { putInt("usage_reasoning", it) }
        usage.cachedTokens?.let { putInt("usage_cache", it) }
    }

    private fun Bundle.getTokenUsage(): AgentTokenUsage =
        AgentTokenUsage(
            contextTokens = optionalInt("usage_context"),
            inputTokens = optionalInt("usage_input"),
            outputTokens = optionalInt("usage_output"),
            reasoningTokens = optionalInt("usage_reasoning"),
            cachedTokens = optionalInt("usage_cache"),
        )

    private fun decodeCustomHeaders(raw: String?): List<CustomHeader> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<CustomHeader>>(raw) }.getOrDefault(emptyList())

    private fun decodeCustomBody(raw: String?): List<CustomBody> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<CustomBody>>(raw) }.getOrDefault(emptyList())

    private fun decodeConversationHistory(raw: String?): List<AgentModelClient.ConversationMessage> =
        if (raw.isNullOrBlank()) emptyList()
        else {
            runCatching { json.decodeFromString<List<AgentModelClient.ConversationMessage>>(raw) }
                .getOrDefault(emptyList())
        }
}
