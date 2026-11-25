package io.github.mangi.eta

import android.content.Context
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.voice.asr.SpeechOssUpload
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 主进程显式依赖与运行时装配容器。
 *
 * 集中声明主进程存储、仓库、设备运行时的初始化顺序及后台预热任务，
 * 避免 `:voice` / `:recognition` 等辅助进程误触发主进程重型依赖，
 * 并作为后续逐步将全局单例仓库演进为构造器注入的统一入口。
 */
internal class AppContainer(
    private val appContext: Context,
) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val appearanceSettingsRepository: AppearanceSettingsRepository = AppearanceSettingsRepository
    val agentMemoryRepository: AgentMemoryRepository = AgentMemoryRepository
    val providerRepository: ProviderRepository = ProviderRepository
    val mcpServerRepository: McpServerRepository = McpServerRepository
    val scheduledTaskRepository: ScheduledTaskRepository = ScheduledTaskRepository
    val linuxEnvironmentSettingsRepository: LinuxEnvironmentSettingsRepository = LinuxEnvironmentSettingsRepository

    /**
     * 按依赖顺序完成主进程同步装配，并启动非阻塞后台预热任务。
     */
    fun initialize() {
        TerminalRuntime.initialize(appContext)
        RootAccess.initialize(appContext)
        SettingsDataStore.init(appContext)

        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            appearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(appContext.applicationInfo, predictiveBackEnabled)

        agentMemoryRepository.init(appContext)
        providerRepository.init(appContext)
        mcpServerRepository.init(appContext)
        scheduledTaskRepository.init(appContext)

        scheduleBackgroundWarmup()
    }

    private fun scheduleBackgroundWarmup() {
        applicationScope.launch {
            TerminalRuntime.backgroundJobs(appContext).resume()
        }
        applicationScope.launch {
            runCatching {
                scheduledTaskRepository.reconcileAfterRestartOrClockChange(
                    recomputeAllSchedules = false,
                    rescheduleAlarm = true,
                    context = appContext,
                )
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Scheduled task reconcile failed: type=${throwable.safeLogType()}",
                )
            }
        }
        applicationScope.launch {
            try {
                SpeechOssUpload(appContext).retryPending(appContext)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            linuxEnvironmentSettingsRepository.initialize(appContext)
            runCatching {
                SkillRuntime.createIndexService(appContext).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}",
                )
            }
        }
    }
}
