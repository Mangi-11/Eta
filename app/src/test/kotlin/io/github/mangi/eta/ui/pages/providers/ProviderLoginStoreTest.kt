package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLoginStoreTest {
    @Test
    fun savesConfigurationBeforeStartingAuthorizationAndIgnoresDoubleClick() = runBlocking {
        val operations = FakeOperations(waitForCancellation = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            store.login(provider)
            withTimeout(2_000) { store.state.first { it.prompt != null } }

            assertEquals(listOf("save", "login"), operations.calls.toList())
            store.cancel()
            withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertNull(store.state.value.prompt)
            assertNull(store.state.value.message)
            assertTrue(operations.controller!!.isCancelled)
        } finally {
            store.close()
        }
    }

    @Test
    fun failedSaveNeverStartsAuthorizationOrExposesUnexpectedException() = runBlocking {
        val operations = FakeOperations(saveError = IllegalStateException("sensitive payload"))
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }

            assertEquals(listOf("save"), operations.calls.toList())
            assertEquals("登录失败，请重试", state.message)
            assertNull(state.prompt)
        } finally {
            store.close()
        }
    }

    @Test
    fun controlledAuthFailureRemainsActionable() = runBlocking {
        val operations = FakeOperations(saveError = ProviderAuthException("reauth_required", "请重新登录"))
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals("请重新登录", state.message)
        } finally {
            store.close()
        }
    }

    @Test
    fun restoredLoginAttemptRequiresExplicitRetryWithoutReopeningBrowser() = runBlocking {
        val operations = FakeOperations()
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.load(provider.authMode, interrupted = true)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }

            assertEquals("登录已中断，请重试", state.message)
            assertNull(state.prompt)
            assertTrue(operations.calls.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun leavingPageCancelsBlockingLoginResource() = runBlocking {
        val operations = FakeOperations(waitForCancellation = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { it.prompt != null } }
            store.close()
            assertTrue(operations.controller!!.isCancelled)
        } finally {
            store.close()
        }
    }

    @Test
    fun disconnectClearsAccountEvenWhenRemoteRevocationIsUnconfirmed() = runBlocking {
        val operations = FakeOperations(revoked = false)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.load(provider.authMode)
            withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(account, store.state.value.account)

            store.disconnect()
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertNull(state.account)
            assertEquals("已退出登录，远端撤销未确认", state.message)
            assertFalse(state.isBusy)
        } finally {
            store.close()
        }
    }

    @Test
    fun successfulLoginFetchesAndSynchronizesModelsBeforeCompleting() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val operations = FakeOperations(syncRelease = release)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val syncing = withTimeout(2_000) { store.state.first { it.phase == ProviderLoginPhase.SyncingModels } }
            assertEquals(account, syncing.account)
            assertNull(syncing.prompt)
            release.complete(Unit)
            val done = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(listOf("save", "login", "sync"), operations.calls.toList())
            assertEquals("已登录，已同步 2 个远端模型", done.message)
        } finally { store.close() }
    }

    @Test
    fun catalogFailureKeepsTheSuccessfulLoginAndDoesNotClaimSynchronizationSucceeded() = runBlocking {
        val operations = FakeOperations(syncError = IllegalStateException("sensitive payload"))
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(account, state.account)
            assertEquals("已登录，模型同步失败，请在模型页重新拉取", state.message)
            assertEquals(listOf("save", "login", "sync"), operations.calls.toList())
        } finally { store.close() }
    }

    @Test
    fun cancellationDuringCatalogFetchDoesNotRemoveTheNewAccount() = runBlocking {
        val operations = FakeOperations(syncRelease = CompletableDeferred())
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { it.phase == ProviderLoginPhase.SyncingModels } }
            store.cancel()
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(account, state.account)
            assertNull(state.message)
        } finally { store.close() }
    }

    private class FakeOperations(
        val waitForCancellation: Boolean = false,
        val saveError: Exception? = null,
        val revoked: Boolean = true,
        val syncError: Exception? = null,
        val syncRelease: CompletableDeferred<Unit>? = null,
    ) : ProviderLoginOperations {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var controller: AgentRunController? = null

        override suspend fun save(provider: ProviderSetting) {
            calls += "save"
            saveError?.let { throw it }
        }

        override fun account(providerId: String, authMode: String): ProviderAccountSummary = account

        override fun login(
            providerId: String,
            authMode: String,
            controller: AgentRunController,
            onPrompt: (ProviderLoginPrompt) -> Unit,
        ): ProviderAccountSummary {
            calls += "login"
            this.controller = controller
            onPrompt(ProviderLoginPrompt("https://auth.example.com/authorize", "TEST-CODE"))
            if (waitForCancellation) controller.awaitRetryDelay(60_000)
            return account
        }

        override suspend fun syncModels(providerId: String, authMode: String): Int {
            assertEquals(provider.authMode, authMode)
            calls += "sync"
            syncError?.let { throw it }
            syncRelease?.await()
            return 2
        }

        override fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean = revoked
    }

    private companion object {
        val provider = CustomProviderSetting(
            id = "test-provider",
            name = "测试服务商",
            baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val account = ProviderAccountSummary("test@example.com", null, false, true)
    }
}
