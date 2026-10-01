package io.github.mangi.eta.agent.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CodexCredentialStoreLockTest {
    @Test
    fun storeInstancesSerializeCredentialTransactionsAndRedactCorruptPayloadDiagnostics() {
        val context = RuntimeEnvironment.getApplication()
        val firstStore = CodexCredentialStore(context)
        val secondStore = CodexCredentialStore(context)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        val results = (0 until 8).map { index ->
            executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                val store = if (index % 2 == 0) firstStore else secondStore
                store.withExclusiveLock {
                    val nowActive = active.incrementAndGet()
                    maximumActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                    try {
                        Thread.sleep(15)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, maximumActive.get())

            val credentialFile = context.filesDir.resolve("eta_codex_oauth_v1.bin")
            val fakeSecret = "fake-access-token-for-corrupt-payload-test"
            credentialFile.writeText(fakeSecret)
            val readResult = firstStore.readDetailed()
            assertNotNull(readResult.diagnostic)
            assertTrue(readResult.diagnostic.orEmpty().none { it.isWhitespace() })
            assertTrue(!readResult.diagnostic.orEmpty().contains(fakeSecret))
            credentialFile.delete()
        } finally {
            start.countDown()
            executor.shutdownNow()
            context.filesDir.resolve("eta_codex_oauth_v1.bin").delete()
            context.filesDir.resolve("eta_codex_oauth.lock").delete()
        }
    }
}
