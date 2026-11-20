package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentState
import io.github.mangi.eta.agent.terminal.AlpineInstallProgress
import io.github.mangi.eta.agent.terminal.AlpineInstallResult
import io.github.mangi.eta.agent.terminal.AlpineInstallStage
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallProgress
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallResult
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallStage
import io.github.mangi.eta.agent.terminal.DebianEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.DebianEnvironmentState
import io.github.mangi.eta.agent.terminal.DebianInstallProgress
import io.github.mangi.eta.agent.terminal.DebianInstallResult
import io.github.mangi.eta.agent.terminal.DebianInstallStage
import io.github.mangi.eta.agent.terminal.LinuxApkAnalysisInstaller
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.LinuxPackageProfile
import io.github.mangi.eta.agent.terminal.LinuxPackageProfileInstaller
import io.github.mangi.eta.agent.terminal.LinuxPackageProfiles
import io.github.mangi.eta.agent.terminal.PackageProfileInstallProgress
import io.github.mangi.eta.agent.terminal.PackageProfileInstallResult
import io.github.mangi.eta.agent.terminal.PackageProfileInstallStage
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.launchForegroundExecution
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.app.rememberExecutionNotificationRequest
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.components.coui.CouiCardDivider as CouiDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference

private enum class InstallTarget {
    BASE,
    TOOLS,
    APK_ANALYSIS,
    PYTHON,
    NODE,
    SSH,
}

private data class PackageProfileUi(
    val target: InstallTarget,
    val profile: LinuxPackageProfile,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val readyRes: Int,
    @param:StringRes val debianSummaryRes: Int = summaryRes,
    @param:StringRes val debianReadyRes: Int = readyRes,
)

private val packageProfileUis = listOf(
    PackageProfileUi(
        target = InstallTarget.PYTHON,
        profile = LinuxPackageProfiles.PYTHON,
        titleRes = R.string.linux_python_tools,
        summaryRes = R.string.linux_python_tools_summary,
        readyRes = R.string.linux_python_tools_ready,
        debianSummaryRes = R.string.linux_python_tools_summary_debian,
        debianReadyRes = R.string.linux_python_tools_ready_debian,
    ),
    PackageProfileUi(
        target = InstallTarget.NODE,
        profile = LinuxPackageProfiles.NODE,
        titleRes = R.string.linux_node_tools,
        summaryRes = R.string.linux_node_tools_summary,
        readyRes = R.string.linux_node_tools_ready,
    ),
    PackageProfileUi(
        target = InstallTarget.SSH,
        profile = LinuxPackageProfiles.SSH,
        titleRes = R.string.linux_ssh_tools,
        summaryRes = R.string.linux_ssh_tools_summary,
        readyRes = R.string.linux_ssh_tools_ready,
    ),
)

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val capabilities = rememberDeviceCapabilities()
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val coroutineScope = rememberCoroutineScope()
    val selectionFlow = remember(appContext) {
        LinuxEnvironmentSettingsRepository.selectedFlow(appContext)
    }
    val selectedDistribution by selectionFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, selectedDistribution, capabilities.root.isGranted) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, selectedDistribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, selectedDistribution),
    )
    val requiresRoot = backend == LinuxExecutionBackend.CHROOT && !capabilities.root.isGranted
    val installer = remember(appContext, backend) { AlpineEnvironmentInstaller(appContext) }
    val debianInstaller = remember(appContext, backend) { DebianEnvironmentInstaller(appContext) }
    val apkAnalysisInstaller = remember(appContext, selectedDistribution, backend) {
        LinuxApkAnalysisInstaller(appContext, selectedDistribution)
    }
    val profileInstallers = remember(appContext, selectedDistribution, backend) {
        packageProfileUis.associate { profileUi ->
            profileUi.target to LinuxPackageProfileInstaller(
                context = appContext,
                distribution = selectedDistribution,
                profile = profileUi.profile,
            )
        }
    }
    var status by remember(installer) { mutableStateOf(installer.status()) }
    var debianStatus by remember(debianInstaller) { mutableStateOf(debianInstaller.status()) }
    var busyTarget by remember { mutableStateOf<InstallTarget?>(null) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var debianProgress by remember { mutableStateOf<DebianInstallProgress?>(null) }
    var profileProgressSummary by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var profileReady by remember(selectedDistribution, backend) {
        mutableStateOf(packageProfileUis.associate { it.target to profileInstallers.getValue(it.target).isReady() })
    }
    var apkAnalysisReady by remember(selectedDistribution, backend) {
        mutableStateOf(apkAnalysisInstaller.isReady())
    }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    val selectedBaseReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status.state != AlpineEnvironmentState.NOT_INSTALLED
        LinuxDistribution.DEBIAN -> debianStatus.state != DebianEnvironmentState.NOT_INSTALLED
    }
    val selectedToolsReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status.state == AlpineEnvironmentState.READY
        LinuxDistribution.DEBIAN -> debianStatus.state == DebianEnvironmentState.READY
    }

    fun launchInstallation(block: suspend () -> Unit) {
        val operation: suspend () -> Unit = {
            try {
                block()
            } finally {
                progress = null
                debianProgress = null
                profileProgressSummary = null
                apkAnalysisProgress = null
                busyTarget = null
            }
        }
        if (backend == LinuxExecutionBackend.PROOT) {
            requestExecutionNotifications()
            coroutineScope.launchForegroundExecution(
                context = appContext,
                onUnavailable = {
                    busyTarget = null
                    resultMessage = context.getString(R.string.capability_background_failed)
                },
                block = operation,
            )
        } else {
            coroutineScope.launch { operation() }
        }
    }

    fun installBase() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.BASE
        resultMessage = null
        launchInstallation {
            resultMessage = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> installer.installBase { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }.toMessage(context)
                LinuxDistribution.DEBIAN -> debianInstaller.installBase { update ->
                    withContext(Dispatchers.Main.immediate) { debianProgress = update }
                }.toMessage(context)
            }
            status = installer.status()
            debianStatus = debianInstaller.status()
            progress = null
            debianProgress = null
            busyTarget = null
        }
    }

    fun installTools() {
        if (busyTarget != null || requiresRoot) return
        busyTarget = InstallTarget.TOOLS
        resultMessage = null
        launchInstallation {
            resultMessage = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> installer.installTools { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }.toMessage(context)
                LinuxDistribution.DEBIAN -> debianInstaller.installTools { update ->
                    withContext(Dispatchers.Main.immediate) { debianProgress = update }
                }.toMessage(context)
            }
            status = installer.status()
            debianStatus = debianInstaller.status()
            profileReady = packageProfileUis.associate {
                it.target to profileInstallers.getValue(it.target).isReady()
            }
            apkAnalysisReady = apkAnalysisInstaller.isReady()
            progress = null
            debianProgress = null
            busyTarget = null
        }
    }

    val uiStyle = LocalUiStyle.current
    val listContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item(key = "status-card") {
            val version = when (selectedDistribution) {
                LinuxDistribution.ALPINE -> status.version
                LinuxDistribution.DEBIAN -> debianStatus.version
            }
            val activeProgress = when (busyTarget) {
                InstallTarget.BASE, InstallTarget.TOOLS -> when (selectedDistribution) {
                    LinuxDistribution.ALPINE -> progress?.summary(context)
                    LinuxDistribution.DEBIAN -> debianProgress?.summary(context)
                }
                InstallTarget.APK_ANALYSIS -> apkAnalysisProgress?.summary(context)
                else -> profileProgressSummary
            }
            LinuxEnvironmentStatusCard(
                title = listOfNotNull(selectedDistribution.displayName(), version?.takeIf { it.isNotBlank() })
                    .joinToString(" "),
                mode = backend.displayName(),
                summary = when {
                    requiresRoot -> stringResource(R.string.capability_linux_root_lost)
                    busyTarget != null -> activeProgress ?: stringResource(R.string.linux_installing)
                    selectedToolsReady -> stringResource(R.string.linux_environment_tools_ready)
                    selectedBaseReady -> stringResource(R.string.linux_environment_base_ready)
                    else -> stringResource(R.string.linux_not_installed) + "\n" + stringResource(
                        when {
                            backend == LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_requirements
                            selectedDistribution == LinuxDistribution.ALPINE -> R.string.linux_requirements
                            else -> R.string.linux_debian_requirements
                        },
                    )
                },
                busy = busyTarget != null,
                message = resultMessage,
                actionText = when {
                    requiresRoot -> stringResource(R.string.capability_enhancements)
                    selectedToolsReady -> null
                    busyTarget != null -> stringResource(R.string.linux_installing)
                    selectedBaseReady -> stringResource(R.string.linux_install_base_tools)
                    else -> stringResource(R.string.linux_install_base)
                },
                actionEnabled = busyTarget == null,
                onAction = {
                    if (requiresRoot) onNavigate(AppRoute.SystemEnhance)
                    else if (selectedBaseReady) installTools() else installBase()
                },
            )
        }
        if (uiStyle != AppearanceUiStyle.COUI) {
            item(key = "configuration-title") {
                SmallTitle(stringResource(R.string.linux_environment_configuration))
            }
        }
        item(key = "configuration-card") {
            LinuxEnvironmentConfiguration(
                distribution = selectedDistribution,
                backend = backend,
                rootGranted = capabilities.root.isGranted,
                enabled = busyTarget == null,
                onDistributionSelected = { distribution ->
                    if (busyTarget == null && distribution != selectedDistribution) {
                        resultMessage = null
                        coroutineScope.launch { LinuxEnvironmentSettingsRepository.select(distribution) }
                    }
                },
                onBackendSelected = { selectedBackend ->
                    if (busyTarget == null && selectedBackend != backend &&
                        (selectedBackend == LinuxExecutionBackend.PROOT || capabilities.root.isGranted)
                    ) {
                        resultMessage = null
                        coroutineScope.launch {
                            LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, selectedBackend)
                        }
                    }
                },
            )
        }
        if (uiStyle != AppearanceUiStyle.COUI) {
            item(key = "files-title") {
                SmallTitle(stringResource(R.string.linux_environment_files))
            }
        }
        item(key = "files-card") {
            if (uiStyle == AppearanceUiStyle.COUI) {
                CouiPreferenceGroup(title = stringResource(R.string.linux_environment_files)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.capability_workspace),
                            summary = stringResource(R.string.capability_workspace_summary),
                            onClick = { onNavigate(AppRoute.Workspace) },
                        )
                    }
                    if (selectedBaseReady) {
                        item {
                            CouiPreference(
                                title = stringResource(R.string.shared_folders_entry_title),
                                summary = stringResource(R.string.linux_environment_shared_folders_summary),
                                onClick = { onNavigate(AppRoute.SharedFolders) },
                            )
                        }
                        item {
                            CouiPreference(
                                title = stringResource(R.string.linux_files_entry_title),
                                summary = stringResource(R.string.linux_files_entry_summary),
                                onClick = { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) },
                            )
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace),
                        summary = stringResource(R.string.capability_workspace_summary),
                        onClick = { onNavigate(AppRoute.Workspace) },
                    )
                    if (selectedBaseReady) {
                        ArrowPreference(
                            title = stringResource(R.string.shared_folders_entry_title),
                            summary = stringResource(R.string.linux_environment_shared_folders_summary),
                            onClick = { onNavigate(AppRoute.SharedFolders) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.linux_files_entry_title),
                            summary = stringResource(R.string.linux_files_entry_summary),
                            onClick = { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) },
                        )
                    }
                }
            }
        }

        if (selectedToolsReady) {
            if (uiStyle != AppearanceUiStyle.COUI) {
                item(key = "optional-tools-title") {
                    SmallTitle(stringResource(R.string.ui_optional_tools_3097d6), modifier = Modifier.animateItem())
                }
            }
            item(key = "optional-tools-card") {
                val optionalItemsContent: @Composable () -> Unit = {
                    packageProfileUis.forEachIndexed { _, profileUi ->
                        val ready = profileReady[profileUi.target] == true
                        val summaryRes = if (selectedDistribution == LinuxDistribution.DEBIAN) {
                            profileUi.debianSummaryRes
                        } else {
                            profileUi.summaryRes
                        }
                        val readyRes = if (selectedDistribution == LinuxDistribution.DEBIAN) {
                            profileUi.debianReadyRes
                        } else {
                            profileUi.readyRes
                        }
                        val rowTitle = stringResource(profileUi.titleRes)
                        val rowSummary = if (busyTarget == profileUi.target) {
                            profileProgressSummary ?: stringResource(summaryRes)
                        } else if (ready) {
                            stringResource(readyRes)
                        } else {
                            stringResource(summaryRes)
                        }
                        val primaryText = when {
                            ready -> stringResource(R.string.linux_installed)
                            busyTarget == profileUi.target -> stringResource(R.string.linux_installing)
                            else -> stringResource(R.string.linux_install)
                        }
                        val primaryEnabled = !requiresRoot && busyTarget == null && !ready
                        val onPrimaryClick: () -> Unit = {
                            if (busyTarget == null && !ready) {
                                busyTarget = profileUi.target
                                resultMessage = null
                                val profileTitle = context.getString(profileUi.titleRes)
                                launchInstallation {
                                    val profileInstaller = profileInstallers.getValue(profileUi.target)
                                    val result = profileInstaller.install { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            profileProgressSummary = update.summary(context, profileTitle)
                                        }
                                    }
                                    profileReady = profileReady +
                                        (profileUi.target to profileInstaller.isReady())
                                    profileProgressSummary = null
                                    busyTarget = null
                                    resultMessage = result.toMessage(context, profileTitle)
                                }
                            }
                        }
                        if (uiStyle == AppearanceUiStyle.COUI) {
                            CouiPreference(
                                title = rowTitle,
                                summary = rowSummary,
                                showChevron = false,
                                trailing = {
                                    CouiButton(
                                        text = primaryText,
                                        enabled = primaryEnabled,
                                        minHeight = 32.dp,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        onClick = onPrimaryClick,
                                    )
                                },
                            )
                            CouiDivider()
                        } else {
                            BasicComponent(
                                title = rowTitle,
                                summary = rowSummary,
                                endActions = {
                                    TextButton(
                                        text = primaryText,
                                        enabled = primaryEnabled,
                                        minWidth = 0.dp,
                                        minHeight = 32.dp,
                                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        onClick = onPrimaryClick,
                                    )
                                },
                            )
                        }
                    }

                    val apkSummary = apkAnalysisProgress?.summary(context) ?: if (apkAnalysisReady) {
                        context.getString(R.string.linux_apk_tools_ready)
                    } else {
                        context.getString(R.string.linux_apk_tools_summary)
                    }
                    val apkButtonText = when {
                        apkAnalysisReady -> context.getString(R.string.linux_installed)
                        busyTarget == InstallTarget.APK_ANALYSIS -> context.getString(R.string.linux_installing)
                        else -> context.getString(R.string.linux_install)
                    }
                    val apkEnabled = busyTarget == null && !requiresRoot && !apkAnalysisReady
                    val onApkInstall: () -> Unit = {
                        if (busyTarget == null && !apkAnalysisReady) {
                            busyTarget = InstallTarget.APK_ANALYSIS
                            resultMessage = null
                            launchInstallation {
                                val result = apkAnalysisInstaller.install { update ->
                                    withContext(Dispatchers.Main.immediate) {
                                        apkAnalysisProgress = update
                                    }
                                }
                                apkAnalysisReady = apkAnalysisInstaller.isReady()
                                apkAnalysisProgress = null
                                busyTarget = null
                                resultMessage = result.toMessage(context)
                            }
                        }
                    }
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        CouiPreference(
                            title = stringResource(R.string.ui_apk_analysis_95ad17),
                            summary = apkSummary,
                            showChevron = false,
                            trailing = {
                                CouiButton(
                                    text = apkButtonText,
                                    enabled = apkEnabled,
                                    minHeight = 32.dp,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    onClick = onApkInstall,
                                )
                            },
                        )
                    } else {
                        BasicComponent(
                            title = stringResource(R.string.ui_apk_analysis_95ad17),
                            summary = apkSummary,
                            endActions = {
                                TextButton(
                                    text = apkButtonText,
                                    enabled = apkEnabled,
                                    minWidth = 0.dp,
                                    minHeight = 32.dp,
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    onClick = onApkInstall,
                                )
                            },
                        )
                    }
                }
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiPreferenceGroup(
                        modifier = Modifier.animateItem(),
                        title = stringResource(R.string.ui_optional_tools_3097d6),
                    ) {
                        item {
                            optionalItemsContent()
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        optionalItemsContent()
                    }
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = stringResource(R.string.ui_linux_tool_environment_314d22),
                onBack = onBack,
                content = { listContent() },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.ui_linux_tool_environment_314d22),
                onBack = onBack,
                content = listContent,
            )
        }
    }
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

private fun AlpineInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != AlpineInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun DebianInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != DebianInstallStage.DOWNLOADING || totalBytes <= 0L) return stageName
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun PackageProfileInstallProgress.summary(context: Context, profileTitle: String): String =
    when (stage) {
        PackageProfileInstallStage.CHECKING -> context.getString(R.string.linux_profile_stage_checking)
        PackageProfileInstallStage.DOWNLOADING -> if (totalBytes > 0L) {
            context.getString(
                R.string.linux_profile_stage_downloading_percent,
                profileTitle,
                (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L),
            )
        } else {
            context.getString(R.string.linux_profile_stage_downloading, profileTitle)
        }
        PackageProfileInstallStage.INSTALLING ->
            context.getString(R.string.linux_profile_stage_installing, profileTitle)
        PackageProfileInstallStage.COMPLETE -> context.getString(R.string.linux_profile_stage_complete)
    }

private fun ApkAnalysisInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> context.getString(R.string.linux_tool)
    }
    return context.getString(R.string.linux_tool_progress_percent, stageName, name, percent)
}

private fun AlpineInstallResult.toMessage(context: Context): String = when (this) {
    AlpineInstallResult.AlreadyReady -> context.getString(R.string.linux_already_ready)
    is AlpineInstallResult.BaseInstalled -> context.getString(R.string.linux_base_install_complete, version)
    is AlpineInstallResult.ToolsInstalled -> context.getString(R.string.linux_install_complete, version)
    AlpineInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun DebianInstallResult.toMessage(context: Context): String = when (this) {
    DebianInstallResult.AlreadyReady -> context.getString(R.string.linux_debian_already_ready)
    is DebianInstallResult.BaseInstalled -> context.getString(R.string.linux_debian_base_install_complete, version)
    is DebianInstallResult.ToolsInstalled -> context.getString(R.string.linux_debian_install_complete, version)
    DebianInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is DebianInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    DebianInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    DebianInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    DebianInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is DebianInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun PackageProfileInstallResult.toMessage(
    context: Context,
    profileTitle: String,
): String = when (this) {
    PackageProfileInstallResult.AlreadyReady ->
        context.getString(R.string.linux_profile_already_ready, profileTitle)
    PackageProfileInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is PackageProfileInstallResult.DependencyMissing -> {
        val dependencyTitle = packageProfileUis
            .firstOrNull { it.profile.id == profileId }
            ?.let { context.getString(it.titleRes) }
            ?: profileId
        context.getString(R.string.linux_profile_dependency_missing, dependencyTitle)
    }
    PackageProfileInstallResult.Installed ->
        context.getString(R.string.linux_profile_installed, profileTitle)
    is PackageProfileInstallResult.Failed -> context.getString(
        R.string.linux_profile_stage_failed,
        PackageProfileInstallProgress(stage).summary(context, profileTitle),
    )
}

private fun ApkAnalysisInstallResult.toMessage(context: Context): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> context.getString(R.string.linux_apk_analysis_ready)
    ApkAnalysisInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is ApkAnalysisInstallResult.InsufficientSpace ->
        context.getString(
            R.string.linux_insufficient_space,
            requiredBytes.toReadableSize(context),
            availableBytes.toReadableSize(context),
        )
    ApkAnalysisInstallResult.Installed -> context.getString(R.string.linux_apk_analysis_installed)
    is ApkAnalysisInstallResult.Failed -> context.getString(R.string.linux_apk_stage_failed, stage.displayName(context))
}

private fun AlpineInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        AlpineInstallStage.CHECKING -> R.string.linux_stage_checking
        AlpineInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        AlpineInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        AlpineInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AlpineInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun DebianInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        DebianInstallStage.CHECKING -> R.string.linux_stage_checking
        DebianInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        DebianInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        DebianInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        DebianInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun ApkAnalysisInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        ApkAnalysisInstallStage.CHECKING -> R.string.linux_apk_stage_checking
        ApkAnalysisInstallStage.DOWNLOADING -> R.string.linux_apk_stage_downloading
        ApkAnalysisInstallStage.PREPARING -> R.string.linux_apk_stage_preparing
        ApkAnalysisInstallStage.INSTALLING_JAVA -> R.string.linux_apk_stage_installing_java
        ApkAnalysisInstallStage.ACTIVATING -> R.string.linux_apk_stage_activating
        ApkAnalysisInstallStage.VERIFYING -> R.string.linux_apk_stage_verifying
        ApkAnalysisInstallStage.COMPLETE -> R.string.linux_apk_stage_complete
    },
)
