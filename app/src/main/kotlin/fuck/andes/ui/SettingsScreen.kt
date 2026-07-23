package fuck.andes.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.config.Prefs
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.systemizer.GoogleAppSystemizerInstaller
import fuck.andes.ui.components.MiuixBackButton
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.systemizer.RootManager
import fuck.andes.systemizer.SystemizerInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── ColorOS / COUI 主色（ColorOS 16.1 Settings.apk: coui_color_*） ────────────────
// 约定：设置页圆形图标/按钮底色只使用 ColorOS 设置主色。
// 不要用 coui_color_*_variant、截图平均取样色或 Material/iOS 近似色替代，否则实心圆底会发灰或偏色。
private val ColorOSOrangeRed = Color(0xFFFF7700)
private val ColorOSRoyalBlue = Color(0xFF0066FF)
private val ColorOSVividGreen = Color(0xFF00BD13)
private val ColorOSAmberYellow = Color(0xFFFFB200)
private val ColorOSLightBlue = Color(0xFF0066FF)
private val ColorOSRed = Color(0xFFEB3B2F)
private val ColorOSPurple = Color(0xFF0066FF)
private val ColorOSSlateGray = Color(0xFF0066FF)
private val ColorOSOrange = Color(0xFFFF7700)

/**
 * 模块配置界面。
 *
 * 开关默认开启（与历史硬编码行为一致）。切换时同步提交（RemotePreferences.commit
 * 会同步等待 binder 提交到 LSPosed 数据库，失败返回 false）；XposedService 未就绪时
 * 不允许写入，避免保存到 hook 进程不可见的本地配置。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: "未选择模型"}"
    } ?: "未配置"

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)) }
    DisposableEffect(Unit) {
        val listener = object : FuckAndesApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        FuckAndesApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { FuckAndesApp.removeServiceStateListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置",
                largeTitle = "设置",
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = innerPadding,
        ) {
            // ── LSPosed 未连接提示 ──────────────────────────────────────
            if (prefs == null) {
                item(key = "service_warning") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        BasicComponent(
                            title = "LSPosed 服务未连接",
                        )
                    }
                }
            }

            // ── Agent ──────────────────────────────────────────────────
            item(key = "section_agent") {
                SmallTitle("Agent")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "默认启用深度思考",
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = LucideR.drawable.lucide_ic_brain,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "启用网页浏览工具",
                        summary = "在离屏浏览器中读取和操作网页，不会自动切换前台",
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = LucideR.drawable.lucide_ic_globe,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "启用终端/文件工具",
                        summary = "允许 Agent 使用 Android user/root shell，并读取或写入手机文件",
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = LucideR.drawable.lucide_ic_square_terminal,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "Linux 工具环境",
                        summary = "安装 Python、Git、jq、zip 等通用命令，当前约 120 MB",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_square_terminal,
                                tint = ColorOSVividGreen,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "模型提供商",
                        summary = providerSummary,
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_cpu,
                                tint = ColorOSPurple,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )
                }
            }

            // ── 小布接管 ──────────────────────────────────────────────────
            item(key = "section_breeno_takeover") {
                SmallTitle("小布接管")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "启用小布自定义模型",
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        icon = LucideR.drawable.lucide_ic_cpu,
                        iconTint = ColorOSOrangeRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "仅 /agent 前缀接管",
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        icon = LucideR.drawable.lucide_ic_message_square,
                        iconTint = ColorOSAmberYellow,
                    )
                }
            }

            // ── Gemini ─────────────────────────────────────────────────
            item(key = "section_gemini") {
                SmallTitle("Gemini")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "长按电源键唤起 Gemini",
                        key = Prefs.Keys.POWER_KEY_TAKEOVER,
                        icon = LucideR.drawable.lucide_ic_power,
                        iconTint = ColorOSOrangeRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "自动设置 Google 为默认助理",
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = LucideR.drawable.lucide_ic_sparkles,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "息屏后维持 Hey Google 检测",
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                        icon = LucideR.drawable.lucide_ic_mic,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "锁屏唤起自动语音输入",
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                        icon = LucideR.drawable.lucide_ic_lock,
                        iconTint = ColorOSRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "亮屏唤起自动语音输入",
                        key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                        icon = LucideR.drawable.lucide_ic_mic,
                        iconTint = ColorOSLightBlue,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "将 Google App 转为系统应用",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_shield,
                                tint = ColorOSVividGreen,
                            )
                        },
                        enabled = !installingSystemizer,
                        holdDownState = showSystemizerDialog,
                        onClick = {
                            if (!installingSystemizer) {
                                showSystemizerDialog = true
                            }
                        },
                    )
                }
            }

            // ── 一圈即搜 ────────────────────────────────────────────────
            item(key = "section_circle_to_search") {
                SmallTitle("一圈即搜")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "手势条长按触发一圈即搜",
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = LucideR.drawable.lucide_ic_search,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "双指长按触发一圈即搜",
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = LucideR.drawable.lucide_ic_mouse_pointer_click,
                        iconTint = ColorOSLightBlue,
                    )
                }
            }

            // ── 权限 ────────────────────────────────────────────────────
            item(key = "section_permissions") {
                SmallTitle("权限")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "悬浮窗权限",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_layers,
                                tint = ColorOSOrangeRed,
                            )
                        },
                        endActions = {
                            Text(
                                text = if (overlayGranted) "已授权" else "未授权",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    ColorOSOrangeRed
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "无障碍增强工具",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_accessibility,
                                tint = ColorOSRoyalBlue,
                            )
                        },
                        endActions = {
                            val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                            Text(
                                text = if (enabled) "已启用" else "未启用",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    ColorOSRoyalBlue
                                },
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                }
            }

            // ── 关于 ────────────────────────────────────────────────────
            item(key = "section_about") {
                SmallTitle("关于")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "源代码",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_github,
                                tint = ColorOSPurple,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Mangi-11/Eta"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
}

// ── 带色彩的圆形图标（ColorOS 风格：圆形背景 + 纯白图标） ────────────────────────────────

@Composable
private fun TintedIcon(
    icon: Int,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color.White,
        )
    }
}

// ── Card 内分隔线 ───────────────────────────────────────────────────────────

@Composable
private fun PrefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            // 对齐 BasicComponent 内文字起始位置：
            // insideMargin(16) + 图标 padding end(12) + 圆形宽度(32) = 60dp
            start = 60.dp,
        ),
    )
}

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "将 Google App 转为系统应用",
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = "系统应用享有语音唤醒权限、更少的自启限制，体验接近原生。",
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "通过 Magisk / KernelSU 模块安装，重启后生效。",
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                enabled = !installing,
            )
            TextButton(
                text = "确定",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = !installing,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * XposedService 到达时通过 [remember(prefs, key)] 重算初始值；切换写入用
 * [putBooleanSync] 同步提交，避免 RemotePreferences.apply() 异步 binder 失败后 UI 显示
 * 与 hook 侧不一致。
 */
@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: Int,
    iconTint: Color,
) {
    val enabled = prefs != null
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: default)
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
            } else {
                Toast.makeText(context.applicationContext, "配置写入失败", Toast.LENGTH_SHORT).show()
            }
        },
        startAction = {
            TintedIcon(icon = icon, tint = iconTint)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = android.content.ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun SystemizerInstallResult.toToastMessage(): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> "Google App 已是系统 priv-app"
        SystemizerInstallResult.GoogleAppMissing -> "未安装 Google App"
        SystemizerInstallResult.UnsupportedRootManager -> "未检测到 Magisk 或 KernelSU"
        SystemizerInstallResult.KernelSuMetamoduleMissing -> "KernelSU 需先启用 metamodule 支持"
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> "请在 KernelSU 中授予 Eta root 权限"
            RootManager.MAGISK -> "请在 Magisk 中授予 Eta root 权限"
            RootManager.UNSUPPORTED -> "未获得 root 权限"
        }
        is SystemizerInstallResult.InstalledRebootRequired -> "安装完成，重启后生效"
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
