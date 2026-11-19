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
import androidx.compose.ui.res.pluralStringResource
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
    val linkChoiceLabels = buildList {
        if (backend != ImageGenBackend.DOUBAO) add(stringResource(R.string.imagegen_link_auto_match))
        linkedOptions.forEach { opt ->
            add(
                if (opt.hasApiKey) {
                    opt.name
                } else {
                    stringResource(R.string.imagegen_link_provider_no_key, opt.name)
                },
            )
        }
        add(stringResource(R.string.imagegen_link_standalone))
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
    val resolutionOptionLabels = if (capability.resolutionIsQualityTier) {
        supportedResolutions.map { res ->
            when (res) {
                io.github.mangi.eta.data.model.ImageGenResolution.R_1K ->
                    stringResource(R.string.imagegen_quality_standard_auto)
                else ->
                    stringResource(R.string.imagegen_quality_high)
            }
        }
    } else {
        supportedResolutions.map { stringResource(it.displayLabelRes) }
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

    val presetLabels = presets.map { preset ->
        val label = preset.labelRes?.let { stringResource(it) } ?: preset.rawLabel
        "$label (${preset.id})"
    } + stringResource(R.string.imagegen_preset_custom_model)
    val selectedPresetIndex = remember(presets, currentModel) {
        presets.indexOfFirst { it.id == currentModel.trim() }.let { if (it >= 0) it else presets.size }
    }
    val onSelectPreset: (Int) -> Unit = { idx ->
        presets.getOrNull(idx)?.let { onModelChange(it.id) }
    }

    val gallerySummary = if (store.galleryCount > 0) {
        pluralStringResource(
            R.plurals.imagegen_gallery_summary_count,
            store.galleryCount,
            store.galleryCount,
            formatImageStorageSize(store.galleryBytes),
        )
    } else {
        stringResource(R.string.imagegen_gallery_summary_empty)
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
                        title = stringResource(R.string.imagegen_enable_title),
                        summary = stringResource(R.string.imagegen_enable_summary),
                        checked = settings.enabled,
                        onCheckedChange = { store.edit(settings.copy(enabled = it)) },
                    )
                }

                Group(title = stringResource(R.string.imagegen_section_service)) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.imagegen_provider_channel),
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
                        title = stringResource(R.string.imagegen_credential_source),
                        items = linkChoiceLabels,
                        selectedIndex = selectedLinkIndex,
                        onSelectedIndexChange = onSelectLink,
                    )
                    Divider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.imagegen_model_presets),
                        items = presetLabels,
                        selectedIndex = selectedPresetIndex,
                        onSelectedIndexChange = onSelectPreset,
                    )
                }

                if (usingLinkedProvider) {
                    Footer(
                        text = stringResource(
                            R.string.imagegen_linked_provider_footer_with_url,
                            resolvedLink.name,
                            resolvedLink.baseUrl.ifBlank { stringResource(R.string.imagegen_official_default) },
                        ),
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

                Group(title = stringResource(R.string.imagegen_section_connection)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CouiTextField(
                            value = currentModel,
                            onValueChange = onModelChange,
                            label = stringResource(
                                if (backend == ImageGenBackend.DOUBAO) {
                                    R.string.imagegen_field_model_or_endpoint_id
                                } else {
                                    R.string.imagegen_field_model_id
                                },
                            ),
                            placeholder = presets.firstOrNull()?.id.orEmpty(),
                            singleLine = true,
                        )

                        // 仅独立配置模式展示独立地址与平台参数，避免将关联服务商的 Key 发往不同主机。
                        if (!usingLinkedProvider) {
                            when (backend) {
                                ImageGenBackend.GEMINI -> {
                                    val endpointModes = listOf(GeminiEndpointMode.AI_STUDIO, GeminiEndpointMode.VERTEX_AI)
                                    val endpointLabels = listOf(
                                        stringResource(R.string.imagegen_gemini_endpoint_ai_studio),
                                        stringResource(R.string.imagegen_gemini_endpoint_vertex),
                                    )
                                    CouiDropdownPreference(
                                        title = stringResource(R.string.imagegen_gemini_platform),
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
                                        label = stringResource(R.string.imagegen_field_base_url_optional),
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
                                            label = stringResource(R.string.imagegen_field_vertex_project),
                                            placeholder = stringResource(R.string.imagegen_field_vertex_project_placeholder),
                                            singleLine = true,
                                        )
                                        CouiTextField(
                                            value = settings.gemini.vertexLocation,
                                            onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexLocation = it))) },
                                            label = stringResource(R.string.imagegen_field_vertex_location),
                                            placeholder = "global",
                                            singleLine = true,
                                        )
                                    }
                                }

                                ImageGenBackend.OPENAI_COMPATIBLE -> {
                                    CouiTextField(
                                        value = settings.openAi.baseUrl,
                                        onValueChange = { store.edit(settings.copy(openAi = settings.openAi.copy(baseUrl = it))) },
                                        label = stringResource(R.string.imagegen_field_openai_base_url),
                                        placeholder = OpenAiImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.XAI -> {
                                    CouiTextField(
                                        value = settings.xai.baseUrl,
                                        onValueChange = { store.edit(settings.copy(xai = settings.xai.copy(baseUrl = it))) },
                                        label = stringResource(R.string.imagegen_field_xai_base_url),
                                        placeholder = XaiImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.DOUBAO -> {
                                    CouiTextField(
                                        value = settings.doubao.baseUrl,
                                        onValueChange = { store.edit(settings.copy(doubao = settings.doubao.copy(baseUrl = it))) },
                                        label = stringResource(R.string.imagegen_field_doubao_base_url),
                                        placeholder = DoubaoImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.QWEN -> {
                                    val regions = listOf(SpeechRegion.BEIJING, SpeechRegion.SINGAPORE)
                                    val regionLabels = listOf(
                                        stringResource(R.string.imagegen_qwen_region_beijing),
                                        stringResource(R.string.imagegen_qwen_region_singapore),
                                    )
                                    CouiDropdownPreference(
                                        title = stringResource(R.string.imagegen_qwen_region),
                                        items = regionLabels,
                                        selectedIndex = regions.indexOf(settings.qwen.region).coerceAtLeast(0),
                                        onSelectedIndexChange = { idx ->
                                            store.edit(settings.copy(qwen = settings.qwen.copy(region = regions[idx])))
                                        },
                                    )
                                    CouiTextField(
                                        value = settings.qwen.baseUrl,
                                        onValueChange = { store.edit(settings.copy(qwen = settings.qwen.copy(baseUrl = it))) },
                                        label = stringResource(R.string.imagegen_field_qwen_base_url),
                                        placeholder = settings.qwen.defaultEndpoint(),
                                        singleLine = true,
                                    )
                                }

                                ImageGenBackend.SILICONFLOW -> {
                                    CouiTextField(
                                        value = settings.siliconFlow.baseUrl,
                                        onValueChange = { store.edit(settings.copy(siliconFlow = settings.siliconFlow.copy(baseUrl = it))) },
                                        label = stringResource(R.string.imagegen_field_siliconflow_base_url),
                                        placeholder = SiliconFlowImageConfig.DEFAULT_BASE_URL,
                                        singleLine = true,
                                    )
                                }
                            }

                            CouiTextField(
                                value = currentApiKey,
                                onValueChange = onApiKeyChange,
                                label = stringResource(R.string.imagegen_field_api_key),
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
                                            contentDescription = stringResource(R.string.imagegen_toggle_api_key_visibility),
                                            tint = pageColors.summary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                Group(title = stringResource(R.string.imagegen_section_defaults)) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.imagegen_default_aspect_ratio),
                        items = ratioList.map { stringResource(it.displayLabelRes) },
                        selectedIndex = ratioList.indexOf(settings.defaultAspectRatio).coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            store.edit(settings.copy(defaultAspectRatio = ratioList[idx]))
                        },
                    )
                    if (supportedResolutions.size > 1) {
                        Divider()
                        CouiDropdownPreference(
                            title = stringResource(
                                if (capability.resolutionIsQualityTier) {
                                    R.string.imagegen_default_quality_tier
                                } else {
                                    R.string.imagegen_default_resolution
                                },
                            ),
                            items = resolutionOptionLabels,
                            selectedIndex = supportedResolutions.indexOf(effectiveResolution).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                store.edit(settings.copy(defaultResolution = supportedResolutions[idx]))
                            },
                        )
                    } else {
                        Divider()
                        CouiPreference(
                            title = stringResource(R.string.imagegen_default_resolution),
                            value = stringResource(
                                R.string.imagegen_resolution_fixed_by_model,
                                resolutionOptionLabels.first(),
                            ),
                            showChevron = false,
                        )
                    }
                    if (backend == ImageGenBackend.DOUBAO) {
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.imagegen_doubao_watermark_title),
                            summary = stringResource(R.string.imagegen_doubao_watermark_summary),
                            checked = settings.doubao.watermark,
                            onCheckedChange = {
                                store.edit(settings.copy(doubao = settings.doubao.copy(watermark = it)))
                            },
                        )
                    }
                    if (backend == ImageGenBackend.QWEN) {
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.imagegen_qwen_prompt_extend_title),
                            summary = stringResource(R.string.imagegen_qwen_prompt_extend_summary),
                            checked = settings.qwen.promptExtend,
                            onCheckedChange = {
                                store.edit(settings.copy(qwen = settings.qwen.copy(promptExtend = it)))
                            },
                        )
                        Divider()
                        CouiSwitchPreference(
                            title = stringResource(R.string.imagegen_qwen_watermark_title),
                            summary = stringResource(R.string.imagegen_default_off),
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
                        text = stringResource(
                            if (store.testing) R.string.imagegen_action_testing else R.string.imagegen_action_test,
                        ),
                        onClick = store::testGenerate,
                        enabled = !store.testing && !store.saving,
                        role = CouiButtonRole.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    CouiButton(
                        text = stringResource(
                            if (store.saving) R.string.imagegen_action_saving else R.string.imagegen_action_save_config,
                        ),
                        onClick = store::save,
                        enabled = !store.saving && !store.testing,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }

                Group(title = stringResource(R.string.imagegen_section_local_images)) {
                    CouiPreference(
                        title = stringResource(R.string.imagegen_open_gallery),
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
                            title = stringResource(R.string.imagegen_enable_title),
                            summary = stringResource(R.string.imagegen_enable_summary),
                            checked = settings.enabled,
                            onCheckedChange = { store.edit(settings.copy(enabled = it)) },
                        )
                    }
                }

                item(key = "backend_section") {
                    SmallTitle(text = stringResource(R.string.imagegen_section_service))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        GlassDropdownPreference(
                            title = stringResource(R.string.imagegen_provider_channel),
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
                            title = stringResource(R.string.imagegen_credential_source),
                            items = linkChoiceLabels,
                            selectedIndex = selectedLinkIndex,
                            onSelectedIndexChange = onSelectLink,
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                        GlassDropdownPreference(
                            title = stringResource(R.string.imagegen_model_presets),
                            items = presetLabels,
                            selectedIndex = selectedPresetIndex,
                            onSelectedIndexChange = onSelectPreset,
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                    }
                    if (usingLinkedProvider) {
                        Text(
                            text = stringResource(R.string.imagegen_linked_provider_footer, resolvedLink.name),
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
                    SmallTitle(text = stringResource(R.string.imagegen_section_connection))
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
                                label = stringResource(
                                    if (backend == ImageGenBackend.DOUBAO) {
                                        R.string.imagegen_field_model_or_endpoint_id
                                    } else {
                                        R.string.imagegen_field_model_id
                                    },
                                ),
                                singleLine = true,
                            )

                            if (!usingLinkedProvider) {
                                when (backend) {
                                    ImageGenBackend.GEMINI -> {
                                        val endpointModes = listOf(GeminiEndpointMode.AI_STUDIO, GeminiEndpointMode.VERTEX_AI)
                                        val endpointLabels = listOf(
                                            stringResource(R.string.imagegen_gemini_endpoint_ai_studio),
                                            stringResource(R.string.imagegen_gemini_endpoint_vertex),
                                        )
                                        GlassDropdownPreference(
                                            title = stringResource(R.string.imagegen_gemini_platform),
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
                                            label = stringResource(R.string.imagegen_field_base_url_optional),
                                            singleLine = true,
                                        )
                                        if (GeminiEndpointMode.normalize(settings.gemini.endpointMode) == GeminiEndpointMode.VERTEX_AI) {
                                            TextField(
                                                value = settings.gemini.vertexProject,
                                                onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexProject = it))) },
                                                label = stringResource(R.string.imagegen_field_vertex_project),
                                                singleLine = true,
                                            )
                                            TextField(
                                                value = settings.gemini.vertexLocation,
                                                onValueChange = { store.edit(settings.copy(gemini = settings.gemini.copy(vertexLocation = it))) },
                                                label = stringResource(R.string.imagegen_field_vertex_location_default),
                                                singleLine = true,
                                            )
                                        }
                                    }

                                    ImageGenBackend.OPENAI_COMPATIBLE -> {
                                        TextField(
                                            value = settings.openAi.baseUrl,
                                            onValueChange = { store.edit(settings.copy(openAi = settings.openAi.copy(baseUrl = it))) },
                                            label = stringResource(R.string.imagegen_field_openai_base_url),
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.XAI -> {
                                        TextField(
                                            value = settings.xai.baseUrl,
                                            onValueChange = { store.edit(settings.copy(xai = settings.xai.copy(baseUrl = it))) },
                                            label = stringResource(R.string.imagegen_field_xai_base_url),
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.DOUBAO -> {
                                        TextField(
                                            value = settings.doubao.baseUrl,
                                            onValueChange = { store.edit(settings.copy(doubao = settings.doubao.copy(baseUrl = it))) },
                                            label = stringResource(R.string.imagegen_field_doubao_base_url),
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.QWEN -> {
                                        val regions = listOf(SpeechRegion.BEIJING, SpeechRegion.SINGAPORE)
                                        val regionLabels = listOf(
                                            stringResource(R.string.imagegen_qwen_region_beijing),
                                            stringResource(R.string.imagegen_qwen_region_singapore),
                                        )
                                        GlassDropdownPreference(
                                            title = stringResource(R.string.imagegen_qwen_region),
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
                                            label = stringResource(R.string.imagegen_field_qwen_base_url),
                                            singleLine = true,
                                        )
                                    }

                                    ImageGenBackend.SILICONFLOW -> {
                                        TextField(
                                            value = settings.siliconFlow.baseUrl,
                                            onValueChange = { store.edit(settings.copy(siliconFlow = settings.siliconFlow.copy(baseUrl = it))) },
                                            label = stringResource(R.string.imagegen_field_siliconflow_base_url),
                                            singleLine = true,
                                        )
                                    }
                                }

                                TextField(
                                    value = currentApiKey,
                                    onValueChange = onApiKeyChange,
                                    label = stringResource(R.string.imagegen_field_api_key),
                                    singleLine = true,
                                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                            Icon(
                                                imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = stringResource(R.string.imagegen_toggle_api_key_visibility),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "defaults_section") {
                    SmallTitle(text = stringResource(R.string.imagegen_section_defaults))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        GlassDropdownPreference(
                            title = stringResource(R.string.imagegen_default_aspect_ratio),
                            items = ratioList.map { stringResource(it.displayLabelRes) },
                            selectedIndex = ratioList.indexOf(settings.defaultAspectRatio).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                store.edit(settings.copy(defaultAspectRatio = ratioList[idx]))
                            },
                            backdrop = currentGlassOverlayBackdrop(),
                        )
                        if (supportedResolutions.size > 1) {
                            GlassDropdownPreference(
                                title = stringResource(
                                    if (capability.resolutionIsQualityTier) {
                                        R.string.imagegen_default_quality_tier
                                    } else {
                                        R.string.imagegen_default_resolution
                                    },
                                ),
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
                                title = stringResource(R.string.imagegen_doubao_watermark_title),
                                summary = stringResource(R.string.imagegen_doubao_watermark_summary),
                                checked = settings.doubao.watermark,
                                onCheckedChange = {
                                    store.edit(settings.copy(doubao = settings.doubao.copy(watermark = it)))
                                },
                            )
                        }
                        if (backend == ImageGenBackend.QWEN) {
                            SwitchPreference(
                                title = stringResource(R.string.imagegen_qwen_prompt_extend_title),
                                summary = stringResource(R.string.imagegen_qwen_prompt_extend_summary),
                                checked = settings.qwen.promptExtend,
                                onCheckedChange = {
                                    store.edit(settings.copy(qwen = settings.qwen.copy(promptExtend = it)))
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.imagegen_qwen_watermark_title),
                                summary = stringResource(R.string.imagegen_default_off),
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
                            text = stringResource(
                                if (store.testing) R.string.imagegen_action_testing else R.string.imagegen_action_test,
                            ),
                            onClick = store::testGenerate,
                            enabled = !store.testing && !store.saving,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(
                                if (store.saving) R.string.imagegen_action_saving else R.string.imagegen_action_save_config,
                            ),
                            onClick = store::save,
                            enabled = !store.saving && !store.testing,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item(key = "gallery_section") {
                    SmallTitle(text = stringResource(R.string.imagegen_section_local_images))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.imagegen_open_gallery),
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
                            contentDescription = stringResource(R.string.imagegen_test_preview_desc),
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
                        text = stringResource(R.string.imagegen_test_preview_title),
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
