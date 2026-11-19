package io.github.mangi.eta.ui.screens.imagegen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.imagegen.GeneratedImageRecord
import io.github.mangi.eta.agent.imagegen.GeneratedImageStore
import io.github.mangi.eta.agent.imagegen.ImageGenClient
import io.github.mangi.eta.agent.imagegen.ImageGenException
import io.github.mangi.eta.agent.imagegen.ImageGenRequest
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ImageGenAspectRatio
import io.github.mangi.eta.data.model.ImageGenBackend
import io.github.mangi.eta.data.model.ImageGenCredentials
import io.github.mangi.eta.data.model.ImageGenSettings
import io.github.mangi.eta.data.repository.ImageGenCredentialsUnavailable
import io.github.mangi.eta.data.repository.ImageGenSettingsRepository
import io.github.mangi.eta.data.repository.LinkedImageProviderOption
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ImageGenSettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(ImageGenSettings())
        private set
    var credentials by mutableStateOf(ImageGenCredentials())
        private set
    var linkedProviders by mutableStateOf<Map<ImageGenBackend, List<LinkedImageProviderOption>>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var testPreviewRecord by mutableStateOf<GeneratedImageRecord?>(null)
        private set
    var galleryCount by mutableIntStateOf(0)
        private set
    var galleryBytes by mutableLongStateOf(0L)
        private set

    private var activeTestController: AgentRunController? = null
    private var activeTestJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        scope.launch {
            loading = true
            errorMessage = null
            try {
                settings = ImageGenSettingsRepository.settings()
                credentials = try {
                    ImageGenSettingsRepository.credentials(context)
                } catch (_: ImageGenCredentialsUnavailable) {
                    errorMessage = "历史独立 API Key 解密失败，如需独立配置请重新输入并保存"
                    ImageGenCredentials()
                }
                linkedProviders = ImageGenBackend.entries.associateWith { backend ->
                    ImageGenSettingsRepository.availableLinkedProviders(backend)
                }
                refreshGalleryStats()
            } finally {
                loading = false
            }
        }
    }

    fun refreshGalleryStats() {
        scope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) {
                val list = GeneratedImageStore.listRecords(context)
                list.size to GeneratedImageStore.totalStorageBytes(context)
            }
            galleryCount = count
            galleryBytes = bytes
        }
    }

    fun edit(next: ImageGenSettings) {
        settings = next
        statusMessage = null
        errorMessage = null
    }

    fun editCredentials(next: ImageGenCredentials) {
        credentials = next
        statusMessage = null
        errorMessage = null
    }

    fun cancelActiveTest() {
        activeTestController?.cancel()
        activeTestController = null
        activeTestJob?.cancel()
        activeTestJob = null
        testing = false
    }

    fun resolvedLinkedProvider(backend: ImageGenBackend): LinkedImageProviderOption? {
        val options = linkedProviders[backend].orEmpty()
        val standaloneKey = currentApiKey(backend).trim()
        if (standaloneKey.isNotEmpty()) return null
        val selectedId = currentLinkId(backend).trim()
        if (selectedId == ImageGenSettings.STANDALONE_LINK_ID) return null
        if (selectedId.isNotEmpty()) {
            return options.firstOrNull { it.id == selectedId }
        }
        return options.firstOrNull { it.hasApiKey }
    }

    fun currentLinkId(backend: ImageGenBackend): String = when (backend) {
        ImageGenBackend.GEMINI -> settings.gemini.linkedProviderId
        ImageGenBackend.OPENAI_COMPATIBLE -> settings.openAi.linkedProviderId
        ImageGenBackend.XAI -> settings.xai.linkedProviderId
        ImageGenBackend.DOUBAO -> settings.doubao.linkedProviderId
        ImageGenBackend.QWEN -> settings.qwen.linkedProviderId
        ImageGenBackend.SILICONFLOW -> settings.siliconFlow.linkedProviderId
    }

    fun currentApiKey(backend: ImageGenBackend): String = when (backend) {
        ImageGenBackend.GEMINI -> credentials.geminiApiKey
        ImageGenBackend.OPENAI_COMPATIBLE -> credentials.openAiApiKey
        ImageGenBackend.XAI -> credentials.xaiApiKey
        ImageGenBackend.DOUBAO -> credentials.doubaoApiKey
        ImageGenBackend.QWEN -> credentials.qwenApiKey
        ImageGenBackend.SILICONFLOW -> credentials.siliconFlowApiKey
    }

    fun save() {
        if (saving) return
        val draftSettings = settings
        val draftCredentials = credentials
        scope.launch {
            saving = true
            statusMessage = null
            errorMessage = null
            try {
                val resolved = ImageGenSettingsRepository.resolveRuntimeConfig(
                    context = context,
                    settingsOverride = draftSettings,
                    credentialsOverride = draftCredentials,
                )
                val normalizedSettings = draftSettings.copy(
                    defaultResolution = resolved.effectiveDefaultResolution,
                )
                if (draftSettings.enabled) {
                    if (resolved.apiKey.isBlank()) {
                        errorMessage = "开启 AI 生图前，请填写 API Key 或关联已配置 Key 的模型服务商"
                        return@launch
                    }
                    if (resolved.model.isBlank()) {
                        errorMessage = "模型 ID 不能为空"
                        return@launch
                    }
                    ImageGenSettingsRepository.validateEndpointUrl(resolved.baseUrl)?.let { err ->
                        errorMessage = err
                        return@launch
                    }
                }
                ImageGenSettingsRepository.saveCredentials(context, draftCredentials)
                ImageGenSettingsRepository.save(normalizedSettings)
                settings = normalizedSettings
                statusMessage = if (normalizedSettings.enabled) "已保存并启用 AI 生图" else "配置已保存"
            } catch (throwable: Throwable) {
                errorMessage = throwable.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }

    fun testGenerate() {
        if (testing) return
        val draftSettings = settings.copy(enabled = true)
        val draftCredentials = credentials
        val controller = AgentRunController()
        activeTestController = controller
        activeTestJob = scope.launch {
            testing = true
            statusMessage = null
            errorMessage = null
            testPreviewRecord = null
            try {
                val resolved = ImageGenSettingsRepository.resolveRuntimeConfig(
                    context = context,
                    settingsOverride = draftSettings,
                    credentialsOverride = draftCredentials,
                )
                if (resolved.apiKey.isBlank()) {
                    errorMessage = "请先填写 API Key 或关联已配置 Key 的服务商后再试画"
                    return@launch
                }
                ImageGenSettingsRepository.validateEndpointUrl(resolved.baseUrl)?.let { err ->
                    errorMessage = err
                    return@launch
                }
                val record = withContext(Dispatchers.IO) {
                    val outputs = ImageGenClient.generate(
                        config = resolved,
                        request = ImageGenRequest(
                            prompt = "A cute minimal flat illustration of a happy orange cat wearing a blue scarf, clean white background",
                            aspectRatio = ImageGenAspectRatio.RATIO_1_1,
                            // 使用与聊天一致的有效默认分辨率，避免试画掩盖高分辨率配置不兼容问题。
                            resolution = resolved.effectiveDefaultResolution,
                            count = 1,
                        ),
                        runController = controller,
                    )
                    controller.throwIfCancelled()
                    val first = outputs.first()
                    GeneratedImageStore.saveGeneratedImage(
                        context = context,
                        bytes = first.bytes,
                        mimeType = first.mimeType,
                        prompt = "连通性测试 · 戴蓝色围巾的橘猫",
                        model = resolved.model,
                        backend = resolved.backend.wireValue,
                    )
                }
                testPreviewRecord = record
                statusMessage = "试画成功（${record.width}×${record.height}）"
                refreshGalleryStats()
            } catch (_: AgentRunCancelledException) {
                // 页面离开或主动取消时不报错。
            } catch (_: CancellationException) {
                controller.cancel()
            } catch (e: ImageGenException) {
                errorMessage = e.message
            } catch (throwable: Throwable) {
                errorMessage = "试画失败：${throwable.javaClass.simpleName}"
            } finally {
                if (activeTestController === controller) {
                    activeTestController = null
                }
                testing = false
            }
        }
    }
}

internal fun ImageGenBackend.displayName(): String = when (this) {
    ImageGenBackend.GEMINI -> "Google Gemini（Nano Banana）"
    ImageGenBackend.OPENAI_COMPATIBLE -> "OpenAI 及兼容接口"
    ImageGenBackend.XAI -> "xAI（Grok Imagine）"
    ImageGenBackend.DOUBAO -> "豆包（火山方舟 Seedream）"
    ImageGenBackend.QWEN -> "千问（阿里云百炼 Qwen-Image）"
    ImageGenBackend.SILICONFLOW -> "硅基流动（SiliconFlow）"
}

internal fun ImageGenBackend.shortName(): String = when (this) {
    ImageGenBackend.GEMINI -> "Gemini"
    ImageGenBackend.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ImageGenBackend.XAI -> "xAI Grok"
    ImageGenBackend.DOUBAO -> "豆包 Seedream"
    ImageGenBackend.QWEN -> "千问 Qwen-Image"
    ImageGenBackend.SILICONFLOW -> "硅基流动"
}

internal fun formatImageStorageSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
