package io.github.mangi.eta.ui.screens.imagegen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.ImageGenModelCapability
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.DoubaoImageConfig
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.ImageGenAspectRatio
import io.github.mangi.eta.data.model.ImageGenBackend
import io.github.mangi.eta.data.model.ImageGenModelPresets
import io.github.mangi.eta.data.model.ImageGenSettings
import io.github.mangi.eta.data.model.OpenAiImageConfig
import io.github.mangi.eta.data.model.SiliconFlowImageConfig
import io.github.mangi.eta.data.model.SpeechRegion
import io.github.mangi.eta.data.model.XaiImageConfig
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.LocalChatImagePreviewer
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.rememberChatImageBitmap
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ImageGenSettingsScreen(
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ImageGenSettingsStore(context, scope) }
    val previewer = LocalChatImagePreviewer.current
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store.refreshGalleryStats()
    }
    DisposableEffect(store) {
        onDispose { store.cancelActiveTest() }
    }

    val settings = store.settings
    val backend = settings.backend
    val backendList = ImageGenBackend.entries
    val ratioList = ImageGenAspectRatio.entries
    val presets = ImageGenModelPresets.forBackend(backend)
    val linkedOptions = store.linkedProviders[backend].orEmpty()
    val resolvedLink = store.resolvedLinkedProvider(backend)

    val currentLinkId = store.currentLinkId(backend)
    val currentApiKey = store.currentApiKey(backend)
    val usingLinkedProvider = resolvedLink != null && resolvedLink.hasApiKey && currentApiKey.isBlank()

    val linkChoiceIds = remember(linkedOptions, backend) {
        buildList {
            if (backend != ImageGenBackend.DOUBAO) add("")
            linkedOptions.forEach { add(it.id) }
            add(ImageGenSettings.STANDALONE_LINK_ID)
        }
    }
    val linkChoiceLabels = remember(linkedOptions, backend) {
        buildList {
            if (backend != ImageGenBackend.DOUBAO) add("自动匹配已有服务商")
            linkedOptions.forEach { opt ->
                val suffix = if (opt.hasApiKey) "" else "（未填 Key）"
                add("${opt.name}$suffix")
            }
            add("独立填写 API Key")
        }
    }
    val selectedLinkIndex = linkChoiceIds.indexOf(currentLinkId).let { if (it >= 0) it else 0 }

    val onSelectLink: (Int) -> Unit = { idx ->
        val id = linkChoiceIds.getOrElse(idx) { ImageGenSettings.STANDALONE_LINK_ID }
        // 切换回关联服务商时清掉独立 Key 避免覆盖关联状态；切到独立模式保留已填 Key。
        if (id != ImageGenSettings.STANDALONE_LINK_ID) {
            val c = store.credentials
            store.editCredentials(
                when (backend) {
                    ImageGenBackend.GEMINI -> c.copy(geminiApiKey = "")
                    ImageGenBackend.OPENAI_COMPATIBLE -> c.copy(openAiApiKey = "")
                    ImageGenBackend.XAI -> c.copy(xaiApiKey = "")
                    ImageGenBackend.DOUBAO -> c.copy(doubaoApiKey = "")
                    ImageGenBackend.QWEN -> c.copy(qwenApiKey = "")
                    ImageGenBackend.SILICONFLOW -> c.copy(siliconFlowApiKey = "")
                },
            )
        }
        when (backend) {
            ImageGenBackend.GEMINI -> store.edit(settings.copy(gemini = settings.gemini.copy(linkedProviderId = id)))
            ImageGenBackend.OPENAI_COMPATIBLE -> store.edit(settings.copy(openAi = settings.openAi.copy(linkedProviderId = id)))
            ImageGenBackend.XAI -> store.edit(settings.copy(xai = settings.xai.copy(linkedProviderId = id)))
            ImageGenBackend.DOUBAO -> store.edit(settings.copy(doubao = settings.doubao.copy(linkedProviderId = id)))
            ImageGenBackend.QWEN -> store.edit(settings.copy(qwen = settings.qwen.copy(linkedProviderId = id)))
            ImageGenBackend.SILICONFLOW -> store.edit(settings.copy(siliconFlow = settings.siliconFlow.copy(linkedProviderId = id)))
        }
    }

    val currentModel = when (backend) {
        ImageGenBackend.GEMINI -> settings.gemini.model
        ImageGenBackend.OPENAI_COMPATIBLE -> settings.openAi.model
        ImageGenBackend.XAI -> settings.xai.model
        ImageGenBackend.DOUBAO -> settings.doubao.model
        ImageGenBackend.QWEN -> settings.qwen.model
        ImageGenBackend.SILICONFLOW -> settings.siliconFlow.model
    }
    val effectiveBaseUrlForCapability = when {
        usingLinkedProvider -> resolvedLink.baseUrl
        backend == ImageGenBackend.OPENAI_COMPATIBLE -> settings.openAi.baseUrl
        else -> ""
    }
    val capability = remember(backend, currentModel, effectiveBaseUrlForCapability) {
        ImageGenModelCapability.resolve(backend, currentModel, effectiveBaseUrlForCapability)
    }
    val supportedResolutions = capability.supportedResolutions
    val effectiveResolution = capability.clampResolution(settings.defaultResolution)
    val resolutionOptionLabels = remember(supportedResolutions, capability.resolutionIsQualityTier) {
        if (capability.resolutionIsQualityTier) {
            supportedResolutions.map { res ->
                when (res) {
                    io.github.mangi.eta.data.model.ImageGenResolution.R_1K -> "标准画质（自动）"
                    else -> "高清画质（High）"
                }
            }
        } else {
            supportedResolutions.map { it.displayLabel }
        }
    }

    val onModelChange: (String) -> Unit = { value ->
        val nextCap = ImageGenModelCapability.resolve(backend, value, effectiveBaseUrlForCapability)
        val clampedRes = nextCap.clampResolution(settings.defaultResolution)
        val updated = when (backend) {
            ImageGenBackend.GEMINI -> settings.copy(gemini = settings.gemini.copy(model = value))
            ImageGenBackend.OPENAI_COMPATIBLE -> settings.copy(openAi = settings.openAi.copy(model = value))
            ImageGenBackend.XAI -> settings.copy(xai = settings.xai.copy(model = value))
            ImageGenBackend.DOUBAO -> settings.copy(doubao = settings.doubao.copy(model = value))
            ImageGenBackend.QWEN -> settings.copy(qwen = settings.qwen.copy(model = value))
            ImageGenBackend.SILICONFLOW -> settings.copy(siliconFlow = settings.siliconFlow.copy(model = value))
        }.copy(defaultResolution = clampedRes)
        store.edit(updated)
    }

    val onApiKeyChange: (String) -> Unit = { value ->
        val c = store.credentials
        store.editCredentials(
            when (backend) {
                ImageGenBackend.GEMINI -> c.copy(geminiApiKey = value)
                ImageGenBackend.OPENAI_COMPATIBLE -> c.copy(openAiApiKey = value)
                ImageGenBackend.XAI -> c.copy(xaiApiKey = value)
                ImageGenBackend.DOUBAO -> c.copy(doubaoApiKey = value)
                ImageGenBackend.QWEN -> c.copy(qwenApiKey = value)
                ImageGenBackend.SILICONFLOW -> c.copy(siliconFlowApiKey = value)
            },
        )
    }

    val presetLabels = remember(presets) { presets.map { "${it.label} (${it.id})" } + "自定义模型 ID" }
    val selectedPresetIndex = remember(presets, currentModel) {
        presets.indexOfFirst { it.id == currentModel.trim() }.let { if (it >= 0) it else presets.size }
    }
    val onSelectPreset: (Int) -> Unit = { idx ->
        presets.getOrNull(idx)?.let { onModelChange(it.id) }
    }

    val gallerySummary = if (store.galleryCount > 0) {
        "${store.galleryCount} 张 · ${formatImageStorageSize(store.galleryBytes)}"
    } else {
        "暂无本地图片"
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val pageColors = CouiPageDefaults.themeColors(dark)
            CouiScaffoldPage(
                title = stringResource(R.string.image_gen_settings_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (store.loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator()
                    }
                    return@CouiScaffoldPage
                }

                Group {
                    CouiSwitchPreference(
                        title = "启用 AI 生图",
                        summary = "开启后任何对话模型都能自动调用生图与改图工具，生成的图片直接显示在聊天流中",
                        checked = settings.enabled,
                        onCheckedChange = { store.edit(settings.copy(enabled = it)) },
                    )
                }

                Group(title = "生图服务") {
                    CouiDropdownPreference(
                        title = "服务商渠道",
                        items = backendList.map { it.displayName() },
                        selectedIndex = backendList.indexOf(backend).coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            val nextBackend = backendList[idx]
                            val nextModel = when (nextBackend) {
                                ImageGenBackend.GEMINI -> settings.gemini.model
                                ImageGenBackend.OPENAI_COMPATIBLE -> settings.openAi.model
                                ImageGenBackend.XAI -> settings.xai.model
                                ImageGenBackend.DOUBAO -> settings.doubao.model
                                ImageGenBackend.QWEN -> settings.qwen.model
                                ImageGenBackend.SILICONFLOW -> settings.siliconFlow.model
                            }
                            val nextCap = ImageGenModelCapability.resolve(nextBackend, nextModel)
                            store.edit(
                                settings.copy(
                                    backend = nextBackend,
                                    defaultResolution = nextCap.clampResolution(settings.defaultResolution),
                                ),
                            )
                        },
                    )
                    Divider()
                    CouiDropdownPreference(
                        title = "凭据来源",
                        items = linkChoiceLabels,
                        selectedIndex = selectedLinkIndex,
                        onSelectedIndexChange = onSelectLink,
                    )
                    Divider()
                    CouiDropdownPreference(
                        title = "常用模型预设",
                        items = presetLabels,
                        selectedIndex = selectedPresetIndex,
                        onSelectedIndexChange = onSelectPreset,
                    )
                }

                if (usingLinkedProvider) {
                    Footer(
                        text = "当前完整复用「${resolvedLink.name}」的 API Key 与连接地址（${resolvedLink.baseUrl.ifBlank { "官方默认" }}）。如需指定其他地址或地域，请将「凭据来源」切换为「独立填写 API Key」。",
                    )
                }

                capability.deprecationNotice?.let { notice ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusWarning.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(text = notice, fontSize = 12.sp, color = StatusWarning)
                    }
                }

                Group(title = "连接与模型参数") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CouiTextField(
                            value = currentModel,
                            onValueChange = onModelChange,
                            label = if (backend == ImageGenBackend.DOUBAO) "模型 ID 或 Endpoint ID" else "模型 ID",
                            placeholder = presets.firstOrNull()?.id.orEmpty(),
                            singleLine = true,
                        )

                        // 仅独立配置模式展示独立地址与平台参数，避免将关联服务商的 Key 发往不同主机。
                        if (!usingLinkedProvider) {
                            when (backend) {
                                ImageGenBackend.GEMINI -> {
                                    val endpointModes = listOf(GeminiEndpointMode.AI_STUDIO, GeminiEndpointMode.VERTEX_AI)
                                    val endpointLabels = listOf("Google AI Studio（Interactions API）", "Google Cloud Vertex AI")
                                    CouiDropdownPreference(
                                        title = "接入平台",
                                        items = endpointLabels,
                                        selectedIndex = endpointModes.indexOf(
                                            GeminiEndpointMode.normalize(settings.gemini.endpointMode),
                                        ).coerceAtLeast(0),
                                        onSelectedIndexChange = { idx ->
                                            store.edit(settings.copy(gemini = settings.gemini.copy(endpointMode = endpointModes[idx])))
                                        },
                                    )
                                    CouiTextField(
                                        value = settings.gemini.baseUrl,
                                        onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(baseUrl = it))) },
                                        label = "接口地址（留空使用官方默认）",
                                        placeholder = if (GeminiEndpointMode.normalize(settings.gemini.endpointMode) == GeminiEndpointMode.VERTEX_AI) {
                                            GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL
                                        } else {
                                            GeminiProviderSetting.DEFAULT_BASE_URL
                                        },
                                        singleLine = true,
                                    )
                                    if (GeminiEndpointMode.normalize(settings.gemini.endpointMode) == GeminiEndpointMode.VERTEX_AI) {
                                        CouiTextField(
                                            value = settings.gemini.vertexProject,
                                            onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexProject = it))) },
                                            label = "Vertex Project（Express 模式留空）",
                                            placeholder = "留空走官方 global Express 端点",
                                            singleLine = true,
                                        )
                                        CouiTextField(
                                            value = settings.gemini.vertexLocation,
                                            onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexLocation = it))) },
                                            label = "Vertex Location",
                                            placeholder = "global",
                                            singleLine = true,
                                        )
                                    }
                                }

                                ImageGenBackend.OPENAI_COMPATIBLE -> {
                                    CouiTextField(
                                        value = settings.openAi.baseUrl,
                                        onValueChange = { store.edit(settings.copy(openAi = settings.openAi.copy(baseUrl = it))) },
                                        label = "接口 Base URL（OpenAI 或兼容中转）",
                                        placeholder = OpenAiImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.XAI -> {
                                    CouiTextField(
                                        value = settings.xai.baseUrl,
                                        onValueChange = { store.edit(settings.copy(xai = settings.xai.copy(baseUrl = it))) },
                                        label = "xAI Base URL",
                                        placeholder = XaiImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.DOUBAO -> {
                                    CouiTextField(
                                        value = settings.doubao.baseUrl,
                                        onValueChange = { store.edit(settings.copy(doubao = settings.doubao.copy(baseUrl = it))) },
                                        label = "火山方舟 Base URL",
                                        placeholder = DoubaoImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.QWEN -> {
                                    val regions = listOf(SpeechRegion.BEIJING, SpeechRegion.SINGAPORE)
                                    val regionLabels = listOf("华北2（北京）", "新加坡（国际站）")
                                    CouiDropdownPreference(
                                        title = "百炼地域",
                                        items = regionLabels,
                                        selectedIndex = regions.indexOf(settings.qwen.region).coerceAtLeast(0),
                                        onSelectedIndexChange = { idx ->
                                            store.edit(settings.copy(qwen = settings.qwen.copy(region = regions[idx])))
                                        },
                                    )
                                    CouiTextField(
                                        value = settings.qwen.baseUrl,
                                        onValueChange = { store.edit(settings.copy(qwen = settings.qwen.copy(baseUrl = it))) },
                                        label = "专属业务空间域名（可选，留空走所选地域官方域名）",
                                        placeholder = settings.qwen.defaultEndpoint(),
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.SILICONFLOW -> {
                                    CouiTextField(
                                        value = settings.siliconFlow.baseUrl,
                                        onValueChange = { store.edit(settings.copy(siliconFlow = settings.siliconFlow.copy(baseUrl = it))) },
                                        label = "硅基流动 Base URL",
                                        placeholder = SiliconFlowImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }
                            }

                            CouiTextField(
                                value = currentApiKey,
                                onValueChange = onApiKeyChange,
                                label = "API Key",
                                placeholder = "sk-... / AIza...",
                                singleLine = true,
                                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clickable { apiKeyVisible = !apiKeyVisible },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CouiIcon(
                                            imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = "切换 API Key 可见性",
                                            tint = pageColors.summary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                Group(title = "生成默认偏好") {
                    CouiDropdownPreference(
                        title = "默认画面比例",
                        items = ratioList.map { it.displayLabel },
                        selectedIndex = ratioList.indexOf(settings.defaultAspectRatio).coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            store.edit(settings.copy(defaultAspectRatio = ratioList[idx]))
                        },
                    )
                    if (supportedResolutions.size > 1) {
                        Divider()
                        CouiDropdownPreference(
                            title = if (capability.resolutionIsQualityTier) "默认画质档位" else "默认清晰度",
                            items = resolutionOptionLabels,
                            selectedIndex = supportedResolutions.indexOf(effectiveResolution).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                store.edit(settings.copy(defaultResolution = supportedResolutions[idx]))
                            },
                        )
                    } else {
                        Divider()
                        CouiPreference(
                            title = "默认清晰度",
                            value = "${resolutionOptionLabels.first()}（当前模型固定）",
                            showChevron = false,
                        )
                    }
                    if (backend == ImageGenBackend.DOUBAO) {
                        Divider()
                        CouiSwitchPreference(
                            title = "添加 AI 生成水印",
                            summary = "默认关闭，开启后在图片右下角显示火山方舟水印",
                            checked = settings.doubao.watermark,
                            onCheckedChange = {
                                store.edit(settings.copy(doubao = settings.doubao.copy(watermark = it)))
                            },
                        )
                    }
                    if (backend == ImageGenBackend.QWEN) {
                        Divider()
                        CouiSwitchPreference(
                            title = "提示词智能润色",
                            summary = "开启后由千问模型自动补充画面细节（推荐开启）",
                            checked = settings.qwen.promptExtend,
                            onCheckedChange = {
                                store.edit(settings.copy(qwen = settings.qwen.copy(promptExtend = it)))
                            },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = "添加 Qwen-Image 水印",
                            summary = "默认关闭",
                            checked = settings.qwen.watermark,
                            onCheckedChange = {
                                store.edit(settings.copy(qwen = settings.qwen.copy(watermark = it)))
                            },
                        )
                    }
                }

                ImageGenFeedbackBlock(
                    store = store,
                    isCoui = true,
                    onPreviewTestImage = { path -> previewer?.open(listOf(path)) },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CouiButton(
                        text = if (store.testing) "试画中…" else "试画测试",
                        onClick = store::testGenerate,
                        enabled = !store.testing && !store.saving,
                        role = CouiButtonRole.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    CouiButton(
                        text = if (store.saving) "保存中…" else "保存配置",
                        onClick = store::save,
                        enabled = !store.saving && !store.testing,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }

                Group(title = "本地图片") {
                    CouiPreference(
                        title = "已生成图片画廊",
                        value = gallerySummary,
                        icon = { CouiSettingsIcon(Icons.Rounded.PhotoLibrary, pageColors.accent) },
                        onClick = onOpenGallery,
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.image_gen_settings_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (store.loading) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator()
                        }
                    }
                    return@MiuixScaffoldPage
                }

                item(key = "enable_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        SwitchPreference(
                            title = "启用 AI 生图",
                            summary = "开启后任何对话模型都能自动调用生图与改图工具，成图直接显示在聊天流中",
                            checked = settings.enabled,
                            onCheckedChange = { store.edit(settings.copy(enabled = it)) },
                        )
                    }
                }

                item(key = "backend_section") {
                    SmallTitle(text = "生图服务")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        GlassDropdownPreference(
                            title = "服务商渠道",
                            items = backendList.map { it.displayName() },
                            selectedIndex = backendList.indexOf(backend).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                val nextBackend = backendList[idx]
                                val nextModel = when (nextBackend) {
                                    ImageGenBackend.GEMINI -> settings.gemini.model
                                    ImageGenBackend.OPENAI_COMPATIBLE -> settings.openAi.model
                                    ImageGenBackend.XAI -> settings.xai.model
                                    ImageGenBackend.DOUBAO -> settings.doubao.model
                                    ImageGenBackend.QWEN -> settings.qwen.model
                                    ImageGenBackend.SILICONFLOW -> settings.siliconFlow.model
                                }
                                val nextCap = ImageGenModelCapability.resolve(nextBackend, nextModel)
                                store.edit(
                                    settings.copy(
                                        backend = nextBackend,
                                        defaultResolution = nextCap.clampResolution(settings.defaultResolution),
                                    ),
                                )
                            },
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                        GlassDropdownPreference(
                            title = "凭据来源",
                            items = linkChoiceLabels,
                            selectedIndex = selectedLinkIndex,
                            onSelectedIndexChange = onSelectLink,
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                        GlassDropdownPreference(
                            title = "常用模型预设",
                            items = presetLabels,
                            selectedIndex = selectedPresetIndex,
                            onSelectedIndexChange = onSelectPreset,
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                    }
                    if (usingLinkedProvider) {
                        Text(
                            text = "当前完整复用「${resolvedLink.name}」的 API Key 与连接地址。如需指定其他地址或地域，请将「凭据来源」切为「独立填写 API Key」。",
                            fontSize = 12.sp,
                            color = StatusSuccess,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    capability.deprecationNotice?.let { notice ->
                        Text(
                            text = notice,
                            fontSize = 12.sp,
                            color = StatusWarning,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }

                item(key = "connection_section") {
                    SmallTitle(text = "连接与模型参数")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextField(
                                value = currentModel,
                                onValueChange = onModelChange,
                                label = if (backend == ImageGenBackend.DOUBAO) "模型 ID 或 Endpoint ID" else "模型 ID",
                                singleLine = true,
                            )

                            if (!usingLinkedProvider) {
                                when (backend) {
                                    ImageGenBackend.GEMINI -> {
                                        val endpointModes = listOf(GeminiEndpointMode.AI_STUDIO, GeminiEndpointMode.VERTEX_AI)
                                        val endpointLabels = listOf("Google AI Studio（Interactions API）", "Google Cloud Vertex AI")
                                        GlassDropdownPreference(
                                            title = "接入平台",
                                            items = endpointLabels,
                                            selectedIndex = endpointModes.indexOf(
                                                GeminiEndpointMode.normalize(settings.gemini.endpointMode),
                                            ).coerceAtLeast(0),
                                            onSelectedIndexChange = { idx ->
                                                store.edit(settings.copy(gemini = settings.gemini.copy(endpointMode = endpointModes[idx])))
                                            },
                                            backdrop = currentGlassOverlayBackdrop(),
                                        )
                                        TextField(
                                            value = settings.gemini.baseUrl,
                                            onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(baseUrl = it))) },
                                            label = "接口地址（留空使用官方默认）",
                                            singleLine = true,
                                        )
                                        if (GeminiEndpointMode.normalize(settings.gemini.endpointMode) == GeminiEndpointMode.VERTEX_AI) {
                                            TextField(
                                                value = settings.gemini.vertexProject,
                                                onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexProject = it))) },
                                                label = "Vertex Project（Express 留空）",
                                                singleLine = true,
                                            )
                                            TextField(
                                                value = settings.gemini.vertexLocation,
                                                onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexLocation = it))) },
                                                label = "Vertex Location（默认 global）",
                                                singleLine = true,
                                            )
                                        }
                                    }

                                    ImageGenBackend.OPENAI_COMPATIBLE -> {
                                        TextField(
                                            value = settings.openAi.baseUrl,
                                            onValueChange = { store.edit(settings.copy(openAi = settings.openAi.copy(baseUrl = it))) },
                                            label = "接口 Base URL（OpenAI 或兼容中转）",
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.XAI -> {
                                        TextField(
                                            value = settings.xai.baseUrl,
                                            onValueChange = { store.edit(settings.copy(xai = settings.xai.copy(baseUrl = it))) },
                                            label = "xAI Base URL",
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.DOUBAO -> {
                                        TextField(
                                            value = settings.doubao.baseUrl,
                                            onValueChange = { store.edit(settings.copy(doubao = settings.doubao.copy(baseUrl = it))) },
                                            label = "火山方舟 Base URL",
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.QWEN -> {
                                        val regions = listOf(SpeechRegion.BEIJING, SpeechRegion.SINGAPORE)
                                        val regionLabels = listOf("华北2（北京）", "新加坡（国际站）")
                                        GlassDropdownPreference(
                                            title = "百炼地域",
                                            items = regionLabels,
                                            selectedIndex = regions.indexOf(settings.qwen.region).coerceAtLeast(0),
                                            onSelectedIndexChange = { idx ->
                                                store.edit(settings.copy(qwen = settings.qwen.copy(region = regions[idx])))
                                            },
                                            backdrop = currentGlassOverlayBackdrop(),
                                        )
                                        TextField(
                                            value = settings.qwen.baseUrl,
                                            onValueChange = { store.edit(settings.copy(qwen = settings.qwen.copy(baseUrl = it))) },
                                            label = "专属业务空间域名（可选，留空走所选地域官方域名）",
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.SILICONFLOW -> {
                                        TextField(
                                            value = settings.siliconFlow.baseUrl,
                                            onValueChange = { store.edit(settings.copy(siliconFlow = settings.siliconFlow.copy(baseUrl = it))) },
                                            label = "硅基流动 Base URL",
                                            singleLine = true,
                                        )
                                    }
                                }

                                TextField(
                                    value = currentApiKey,
                                    onValueChange = onApiKeyChange,
                                    label = "API Key",
                                    singleLine = true,
                                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                            Icon(
                                                imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = "切换 API Key 可见性",
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "defaults_section") {
                    SmallTitle(text = "生成默认偏好")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        GlassDropdownPreference(
                            title = "默认画面比例",
                            items = ratioList.map { it.displayLabel },
                            selectedIndex = ratioList.indexOf(settings.defaultAspectRatio).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                store.edit(settings.copy(defaultAspectRatio = ratioList[idx]))
                            },
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                        if (supportedResolutions.size > 1) {
                            GlassDropdownPreference(
                                title = if (capability.resolutionIsQualityTier) "默认画质档位" else "默认清晰度",
                                items = resolutionOptionLabels,
                                selectedIndex = supportedResolutions.indexOf(effectiveResolution).coerceAtLeast(0),
                                onSelectedIndexChange = { idx ->
                                    store.edit(settings.copy(defaultResolution = supportedResolutions[idx]))
                                },
                                backdrop = currentGlassOverlayBackdrop(),
                            )
                        }
                        if (backend == ImageGenBackend.DOUBAO) {
                            SwitchPreference(
                                title = "添加 AI 生成水印",
                                summary = "默认关闭，开启后在图片右下角显示水印",
                                checked = settings.doubao.watermark,
                                onCheckedChange = {
                                    store.edit(settings.copy(doubao = settings.doubao.copy(watermark = it)))
                                },
                            )
                        }
                        if (backend == ImageGenBackend.QWEN) {
                            SwitchPreference(
                                title = "提示词智能润色",
                                summary = "开启后由千问模型自动补充画面细节",
                                checked = settings.qwen.promptExtend,
                                onCheckedChange = {
                                    store.edit(settings.copy(qwen = settings.qwen.copy(promptExtend = it)))
                                },
                            )
                            SwitchPreference(
                                title = "添加 Qwen-Image 水印",
                                summary = "默认关闭",
                                checked = settings.qwen.watermark,
                                onCheckedChange = {
                                    store.edit(settings.copy(qwen = settings.qwen.copy(watermark = it)))
                                },
                            )
                        }
                    }
                }

                item(key = "feedback_and_actions") {
                    ImageGenFeedbackBlock(
                        store = store,
                        isCoui = false,
                        onPreviewTestImage = { path -> previewer?.open(listOf(path)) },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(
                            text = if (store.testing) "试画中…" else "试画测试",
                            onClick = store::testGenerate,
                            enabled = !store.testing && !store.saving,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = if (store.saving) "保存中…" else "保存配置",
                            onClick = store::save,
                            enabled = !store.saving && !store.testing,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item(key = "gallery_section") {
                    SmallTitle(text = "本地图片")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        ArrowPreference(
                            title = "已生成图片画廊",
                            summary = gallerySummary,
                            onClick = onOpenGallery,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGenFeedbackBlock(
    store: ImageGenSettingsStore,
    isCoui: Boolean,
    onPreviewTestImage: (String) -> Unit,
) {
    val error = store.errorMessage
    val status = store.statusMessage
    val previewRecord = store.testPreviewRecord
    if (error == null && status == null && previewRecord == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isCoui) 16.dp else 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!error.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(StatusError.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(text = error, fontSize = 13.sp, color = StatusError)
            }
        }
        if (!status.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(StatusSuccess.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(text = status, fontSize = 13.sp, color = StatusSuccess)
            }
        }
        if (previewRecord != null) {
            val bitmap = rememberChatImageBitmap(previewRecord.filePath, maxDimensionPx = 800)
            val previewCardBg = if (isCoui) {
                CouiPageDefaults.themeColors(CouiTheme.isDark).card
            } else {
                MiuixTheme.colorScheme.surfaceContainerHigh
            }
            val previewTitleColor = if (isCoui) {
                CouiPageDefaults.themeColors(CouiTheme.isDark).title
            } else {
                MiuixTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(previewCardBg)
                    .clickable { onPreviewTestImage(previewRecord.filePath) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "试画预览",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "试画成功，点击查看大图",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = previewTitleColor,
                    )
                    Text(
                        text = "${previewRecord.model} · ${previewRecord.width}×${previewRecord.height}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
