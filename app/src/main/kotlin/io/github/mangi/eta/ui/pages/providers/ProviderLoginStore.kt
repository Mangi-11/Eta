package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ProviderLoginPhase { Idle, Loading, Saving, Authorizing, SyncingModels, Disconnecting }

internal data class ProviderLoginState(
    val authMode: String = ProviderAuthModes.API_KEY,
    val account: ProviderAccountSummary? = null,
    val prompt: ProviderLoginPrompt? = null,
    val phase: ProviderLoginPhase = ProviderLoginPhase.Idle,
    val message: String? = null,
) {
    val isBusy: Boolean get() = phase != ProviderLoginPhase.Idle
}

/** 登录会话归属当前配置页；浏览器切换不取消，离开页面则关闭轮询或回调监听。 */
internal class ProviderLoginStore(
    private val providerId: String,
    parentScope: CoroutineScope,
    private val operations: ProviderLoginOperations = RepositoryProviderLoginOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(ProviderLoginState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var controller: AgentRunController? = null
    private var generation = 0L

    fun load(authMode: String, interrupted: Boolean = false) {
        if (mutableState.value.isBusy) return
        if (!ProviderAuthModes.isOAuth(authMode)) {
            mutableState.value = ProviderLoginState(authMode = authMode)
            return
        }
        runOperation(authMode, ProviderLoginPhase.Loading) { _, _ ->
            val account = withContext(ioDispatcher) { operations.account(providerId, authMode) }
            mutableState.value = mutableState.value.copy(
                account = account,
                message = if (interrupted) "登录已中断，请重试" else null,
            )
        }
    }

    fun login(provider: ProviderSetting) {
        if (mutableState.value.isBusy || provider.id != providerId) return
        runOperation(provider.authMode, ProviderLoginPhase.Saving) { activeController, attempt ->
            withContext(ioDispatcher) { operations.save(provider) }
            activeController.throwIfCancelled()
            mutableState.value = mutableState.value.copy(phase = ProviderLoginPhase.Authorizing)
            val account = withContext(ioDispatcher) {
                operations.login(providerId, provider.authMode, activeController) { prompt ->
                    scope.launch {
                        if (generation == attempt && !activeController.isCancelled &&
                            mutableState.value.phase == ProviderLoginPhase.Authorizing
                        ) {
                            mutableState.value = mutableState.value.copy(prompt = prompt)
                        }
                    }
                }
            }
            mutableState.value = mutableState.value.copy(account = account, prompt = null, phase = ProviderLoginPhase.SyncingModels)
            val count = withContext(ioDispatcher) { operations.syncModels(providerId, provider.authMode) }
            mutableState.value = mutableState.value.copy(message = "已登录，已同步 $count 个远端模型")
        }
    }

    fun disconnect() {
        val authMode = mutableState.value.authMode
        if (mutableState.value.isBusy || !ProviderAuthModes.isOAuth(authMode)) return
        runOperation(authMode, ProviderLoginPhase.Disconnecting) { activeController, _ ->
            val revoked = withContext(ioDispatcher) { operations.disconnect(providerId, authMode, activeController) }
            mutableState.value = mutableState.value.copy(
                account = null,
                message = if (revoked == false) "已退出登录，远端撤销未确认" else "已退出登录",
            )
        }
    }

    fun cancel() {
        controller?.cancel()
        operation?.cancel()
        mutableState.value = mutableState.value.copy(prompt = null)
    }

    fun close() {
        generation++
        controller?.cancel()
        scope.cancel()
    }

    private fun runOperation(
        authMode: String,
        phase: ProviderLoginPhase,
        action: suspend (AgentRunController, Long) -> Unit,
    ) {
        val attempt = ++generation
        val activeController = AgentRunController()
        controller = activeController
        mutableState.value = mutableState.value.copy(authMode = authMode, phase = phase, prompt = null, message = null)
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action(activeController, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AgentRunCancelledException) {
                // 取消是用户结束当前授权，不显示失败提示。
            } catch (error: Exception) {
                if (generation == attempt && !activeController.isCancelled) {
                    mutableState.value = mutableState.value.copy(
                        message = when (mutableState.value.phase) {
                            ProviderLoginPhase.SyncingModels -> "已登录，模型同步失败，请在模型页重新拉取"
                            else -> (error as? ProviderAuthException)?.message ?: when (phase) {
                                ProviderLoginPhase.Loading -> "无法读取登录状态，请重试"
                                ProviderLoginPhase.Disconnecting -> "退出登录失败，请重试"
                                else -> "登录失败，请重试"
                            }
                        },
                    )
                }
            } finally {
                activeController.cancel()
                if (generation == attempt) {
                    controller = null
                    mutableState.value = mutableState.value.copy(phase = ProviderLoginPhase.Idle, prompt = null)
                }
            }
        }
    }
}

internal interface ProviderLoginOperations {
    suspend fun save(provider: ProviderSetting)
    fun account(providerId: String, authMode: String): ProviderAccountSummary?
    fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderAccountSummary
    suspend fun syncModels(providerId: String, authMode: String): Int
    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean?
}

private object RepositoryProviderLoginOperations : ProviderLoginOperations {
    override suspend fun save(provider: ProviderSetting) {
        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
    }

    override fun account(providerId: String, authMode: String): ProviderAccountSummary? =
        ProviderCredentialRepository.account(providerId, authMode)

    override fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderAccountSummary = ProviderCredentialRepository.login(providerId, authMode, controller, onPrompt)

    override suspend fun syncModels(providerId: String, authMode: String): Int {
        val provider = requireNotNull(ProviderRepository.providerById(providerId)) { "服务商连接不存在" }
        if (provider.authMode != authMode) {
            throw ProviderAuthException("oauth_session_changed", "服务商登录方式已变更，请重新拉取模型")
        }
        val catalog = RemoteModelFetcher.fetchCatalog(provider).getOrThrow()
        val result = ModelRepository.syncRemoteCatalog(catalog)
        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
        return result.fetchedCount
    }

    override fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? =
        ProviderCredentialRepository.disconnect(providerId, authMode, controller)
}
