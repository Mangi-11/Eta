package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialSessionsTest {
    @Test
    fun loginAndLogoutInvalidatePreviousAccountBindings() {
        val storage = MemoryStorage().apply { write(PROVIDER_ID, credential()) }
        val sessions = ProviderCredentialSessions(storage, FakeOAuthClient(login = { credential() })) { NOW }
        val original = sessions.binding(PROVIDER_ID, AUTH_MODE)
        sessions.login(PROVIDER_ID, AUTH_MODE, AgentRunController()) {}
        assertFalse(sessions.isCurrent(original))
        val replacement = sessions.binding(PROVIDER_ID, AUTH_MODE)
        sessions.disconnect(PROVIDER_ID, AUTH_MODE, AgentRunController())
        assertFalse(sessions.isCurrent(replacement))
        assertTrue(requireNotNull(sessions.account(PROVIDER_ID, AUTH_MODE)).needsReauthentication)
    }

    @Test
    fun simultaneousRequestsRefreshOnceAndShareTheRotatedCredential() {
        val storage = MemoryStorage().apply { write(PROVIDER_ID, credential()) }
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val client = FakeOAuthClient(refresh = { old, _ ->
            refreshStarted.countDown()
            await(releaseRefresh)
            old.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh", expiresAt = NOW + 3_600_000)
        })
        val sessions = ProviderCredentialSessions(storage, client) { NOW }
        val executor = Executors.newFixedThreadPool(6)
        try {
            val started = CountDownLatch(6)
            val requests = List(6) {
                executor.submit<String> {
                    started.countDown()
                    sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController())
                }
            }
            await(started)
            await(refreshStarted)
            releaseRefresh.countDown()
            assertEquals(setOf("rotated-access"), requests.map { it.get(5, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, client.refreshes.get())
            assertEquals("rotated-refresh", storage.read(PROVIDER_ID)?.refreshToken)
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun forgottenSessionCannotBeRecreatedByLateRefreshResponse() {
        val storage = MemoryStorage().apply { write(PROVIDER_ID, credential()) }
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val client = FakeOAuthClient(refresh = { old, _ ->
            refreshStarted.countDown()
            await(releaseRefresh)
            old.copy(accessToken = "late-access", expiresAt = NOW + 3_600_000)
        })
        val sessions = ProviderCredentialSessions(storage, client) { NOW }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = executor.submit<String> { sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController()) }
            await(refreshStarted)
            sessions.forget(PROVIDER_ID)
            assertNull(storage.read(PROVIDER_ID))
            releaseRefresh.countDown()
            val failure = assertThrows(ExecutionException::class.java) { request.get(5, TimeUnit.SECONDS) }
            assertEquals("oauth_session_changed", (failure.cause as ProviderAuthException).code)
            assertNull(storage.read(PROVIDER_ID))
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedBrowserLoginDoesNotDiscardRotationOfTheExistingSession() {
        val storage = MemoryStorage().apply { write(PROVIDER_ID, credential()) }
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val client = FakeOAuthClient(
            login = { throw ProviderAuthException("oauth_access_denied", "测试拒绝登录") },
            refresh = { old, _ ->
                refreshStarted.countDown()
                await(releaseRefresh)
                old.copy(accessToken = "rotated-access", refreshToken = "rotated-refresh", expiresAt = NOW + 3_600_000)
            },
        )
        val sessions = ProviderCredentialSessions(storage, client) { NOW }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = executor.submit<String> { sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController()) }
            await(refreshStarted)
            assertEquals("oauth_access_denied", assertThrows(ProviderAuthException::class.java) {
                sessions.login(PROVIDER_ID, AUTH_MODE, AgentRunController()) {}
            }.code)
            releaseRefresh.countDown()
            assertEquals("rotated-access", request.get(5, TimeUnit.SECONDS))
            assertEquals("rotated-refresh", storage.read(PROVIDER_ID)?.refreshToken)
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationAfterTokenRotationStillPersistsTheNewRefreshToken() {
        val storage = MemoryStorage().apply { write(PROVIDER_ID, credential()) }
        val client = FakeOAuthClient(refresh = { old, controller ->
            controller.cancel()
            old.copy(accessToken = "new-access", refreshToken = "new-refresh", expiresAt = NOW + 3_600_000)
        })
        val sessions = ProviderCredentialSessions(storage, client) { NOW }

        assertThrows(AgentRunCancelledException::class.java) {
            sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController())
        }
        assertEquals("new-refresh", storage.read(PROVIDER_ID)?.refreshToken)
        assertEquals("new-access", sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController()))
        assertEquals(1, client.refreshes.get())
    }

    @Test
    fun temporaryRefreshFailurePreservesTheExistingAuthorization() {
        val original = credential()
        val storage = MemoryStorage().apply { write(PROVIDER_ID, original) }
        val sessions = ProviderCredentialSessions(storage, FakeOAuthClient(refresh = { _, _ ->
            throw ProviderAuthException("oauth_network_error", "测试网络错误")
        })) { NOW }

        val failure = assertThrows(ProviderAuthException::class.java) {
            sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController())
        }
        assertEquals("oauth_network_error", failure.code)
        assertEquals(original, storage.read(PROVIDER_ID))
        assertFalse(requireNotNull(sessions.account(PROVIDER_ID, AUTH_MODE)).needsReauthentication)
    }

    @Test
    fun rejectedRefreshInvalidatesTokensAndRequiresNewLogin() {
        val original = credential()
        val storage = MemoryStorage().apply { write(PROVIDER_ID, original) }
        val client = FakeOAuthClient(refresh = { _, _ ->
            throw ProviderAuthException("oauth_session_expired", "测试失效")
        })
        val sessions = ProviderCredentialSessions(storage, client) { NOW }

        assertEquals("oauth_session_expired", assertThrows(ProviderAuthException::class.java) {
            sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController())
        }.code)
        assertEquals(original.withoutTokens(), storage.read(PROVIDER_ID))
        assertTrue(requireNotNull(sessions.account(PROVIDER_ID, AUTH_MODE)).needsReauthentication)
        assertEquals("oauth_login_required", assertThrows(ProviderAuthException::class.java) {
            sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController())
        }.code)
        assertEquals(1, client.refreshes.get())
    }

    @Test
    fun latestLoginWinsWhenEarlierAuthorizationCompletesLate() {
        val storage = MemoryStorage()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val logins = AtomicInteger()
        val client = FakeOAuthClient(login = {
            val ordinal = logins.incrementAndGet()
            if (ordinal == 1) {
                firstStarted.countDown()
                await(releaseFirst)
            }
            credential().copy(accessToken = "login-$ordinal", expiresAt = NOW + 3_600_000)
        })
        val sessions = ProviderCredentialSessions(storage, client) { NOW }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<ProviderAccountSummary> {
                sessions.login(PROVIDER_ID, AUTH_MODE, AgentRunController()) {}
            }
            await(firstStarted)
            sessions.login(PROVIDER_ID, AUTH_MODE, AgentRunController()) {}
            releaseFirst.countDown()
            val failure = assertThrows(ExecutionException::class.java) { first.get(5, TimeUnit.SECONDS) }
            assertEquals("oauth_session_changed", (failure.cause as ProviderAuthException).code)
            assertEquals("login-2", storage.read(PROVIDER_ID)?.accessToken)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun delayedUnauthorizedResponseDoesNotRefreshANewerAccessToken() {
        val storage = MemoryStorage().apply {
            write(PROVIDER_ID, credential().copy(accessToken = "new-access", expiresAt = NOW + 3_600_000))
        }
        val client = FakeOAuthClient()
        val sessions = ProviderCredentialSessions(storage, client) { NOW }

        assertEquals("new-access", sessions.accessToken(PROVIDER_ID, AUTH_MODE, AgentRunController(), "old-access"))
        assertEquals(0, client.refreshes.get())
    }

    private class MemoryStorage : ProviderCredentialStorage {
        private val credentials = ConcurrentHashMap<String, ProviderCredential>()
        override fun read(providerId: String): ProviderCredential? = credentials[providerId]
        override fun write(providerId: String, credential: ProviderCredential) { credentials[providerId] = credential }
        override fun delete(providerId: String) { credentials.remove(providerId) }
    }

    private class FakeOAuthClient(
        private val login: () -> ProviderCredential = { error("Unexpected login") },
        private val refresh: (ProviderCredential, AgentRunController) -> ProviderCredential = { _, _ -> error("Unexpected refresh") },
    ) : ProviderOAuthClient {
        val refreshes = AtomicInteger()
        override fun login(provider: OAuthProvider, controller: AgentRunController,
            onPrompt: (ProviderLoginPrompt) -> Unit): ProviderCredential = login()
        override fun refresh(credential: ProviderCredential, controller: AgentRunController): ProviderCredential {
            refreshes.incrementAndGet()
            return refresh.invoke(credential, controller)
        }
        override fun revoke(credential: ProviderCredential, controller: AgentRunController): Boolean = true
    }

    private companion object {
        const val PROVIDER_ID = "provider-fixture"
        const val AUTH_MODE = ProviderAuthModes.OPENAI_CHATGPT
        const val NOW = 1_000_000L
        fun credential() = ProviderCredential(
            authMode = AUTH_MODE, clientId = CodexOAuth.CLIENT_ID, subject = "fixture-account",
            accessToken = "old-access", refreshToken = "old-refresh", idToken = "fixture-id",
            accountId = "workspace", expiresAt = NOW - 1, scopes = CodexOAuth.SCOPE.split(' ').toSet(),
        )
        fun await(latch: CountDownLatch) { assertTrue("异步操作未在限时内完成", latch.await(5, TimeUnit.SECONDS)) }
    }
}
