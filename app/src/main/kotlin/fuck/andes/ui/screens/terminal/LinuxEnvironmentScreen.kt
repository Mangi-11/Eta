package fuck.andes.ui.screens.terminal

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.agent.terminal.AlpineEnvironmentInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentState
import fuck.andes.agent.terminal.AlpineEnvironmentStatus
import fuck.andes.agent.terminal.AlpineInstallProgress
import fuck.andes.agent.terminal.AlpineInstallResult
import fuck.andes.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val installer = remember(context.applicationContext) {
        AlpineEnvironmentInstaller(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf(installer.status()) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(
        title = "Linux 工具环境",
        onBack = onBack,
    ) {
        item(key = "status-title") { SmallTitle("环境状态") }
        item(key = "status-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = status.title(),
                    summary = progress?.summary() ?: status.summary(),
                    endActions = {
                        TextButton(
                            text = when {
                                installing -> "安装中"
                                status.state == AlpineEnvironmentState.READY -> "已就绪"
                                status.state == AlpineEnvironmentState.BASE_READY -> "继续安装"
                                else -> "下载并安装"
                            },
                            enabled = !installing && status.state != AlpineEnvironmentState.READY,
                            onClick = {
                                if (installing) return@TextButton
                                installing = true
                                resultMessage = null
                                coroutineScope.launch {
                                    val result = installer.install { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = update
                                        }
                                    }
                                    status = installer.status()
                                    progress = null
                                    installing = false
                                    resultMessage = result.toMessage()
                                }
                            },
                        )
                    },
                )
            }
        }

        resultMessage?.let { message ->
            item(key = "result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }

        item(key = "details-title") { SmallTitle("说明") }
        item(key = "details-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = "与 Android Root Shell 分离",
                    summary = "系统、应用、日志和 Magisk 操作仍使用 Android 环境；Python、Git、jq、zip 等通用工具使用 Alpine 环境。",
                )
                BasicComponent(
                    title = "安装内容",
                    summary = "先下载约 4 MB 的 Alpine 3.24 基础文件系统，再联网安装 Bash、Python、Git、curl、wget、jq、zip/unzip、OpenSSL、SQLite、Vim 与 Nano；当前安装后约占 120 MB。",
                )
                BasicComponent(
                    title = "按需扩展",
                    summary = "编译任务可在 Linux 环境中执行 apk add build-base clang cmake；额外占用由所选软件包决定。",
                )
                BasicComponent(
                    title = "权限边界",
                    summary = "环境通过 Root chroot 运行，并用独立 mount namespace 避免挂载泄漏；它提供工具链，不是安全沙箱。",
                )
            }
        }
    }
}

private fun AlpineEnvironmentStatus.title(): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> "尚未安装"
    AlpineEnvironmentState.BASE_READY -> "基础环境已就绪"
    AlpineEnvironmentState.READY -> "Alpine ${version ?: ""} 已就绪".trim()
}

private fun AlpineEnvironmentStatus.summary(): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> "需要 Root 与 Magisk、KernelSU 或 APatch BusyBox"
    AlpineEnvironmentState.BASE_READY -> "常用工具安装尚未完成，可以从当前进度继续"
    AlpineEnvironmentState.READY -> "Agent 可通过 terminal 的 environment=linux 使用完整工具环境"
}

private fun AlpineInstallProgress.summary(): String {
    if (stage.displayName != "下载 Alpine 基础环境" || totalBytes <= 0L) {
        return stage.displayName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return "${stage.displayName} · $percent%"
}

private fun AlpineInstallResult.toMessage(): String = when (this) {
    AlpineInstallResult.AlreadyReady -> "Linux 工具环境已经就绪"
    is AlpineInstallResult.Installed -> "Alpine $version 与常用工具安装完成"
    is AlpineInstallResult.UnsupportedAbi -> "暂不支持设备架构：$abi"
    AlpineInstallResult.RootUnavailable -> "未获得 Root 权限，请在 Root 管理器中授权 Eta"
    AlpineInstallResult.BusyBoxUnavailable -> "Root 环境缺少可用的 BusyBox 或必要 applet"
    AlpineInstallResult.EnvironmentUnavailable -> "当前 Root 环境无法创建隔离 mount namespace 或 chroot"
    is AlpineInstallResult.Failed -> "${stage.displayName}失败，请检查网络或稍后重试"
}
