package io.github.mangi.eta.data.repository

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelFetcherCancellationTest {
    @Test
    fun cancelledCatalogFetchDoesNotWaitForBodyOrBecomeFailureResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{\"data\":[".toByteArray())
            exchange.responseBody.flush()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val pending = async {
                RemoteModelFetcher.fetch(
                    OpenAiCompatibleProviderSetting(
                        id = "fixture", name = "fixture", baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                    ),
                )
            }
            kotlinx.coroutines.yield()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
