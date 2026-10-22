package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LoopbackAuthorizationReceiverTest {
    @Test fun listenerRejectsForeignHostThenConsumesOneValidCallbackAndCloses() {
        LoopbackAuthorizationReceiver(port = 0).use { receiver ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<String> {
                    receiver.awaitCode("expected", AgentRunController())
                }
                val port = URI(receiver.redirectUri).port
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write(("GET /auth/callback?code=test&state=expected HTTP/1.1\r\n" +
                        "Host: example.invalid\r\n\r\n").toByteArray())
                    assertEquals("HTTP/1.1 400 Bad Request", socket.getInputStream().bufferedReader().readLine())
                }
                val connection = URI(receiver.redirectUri + "?code=test&state=expected").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
                assertEquals("test", future.get(3, TimeUnit.SECONDS))
                assertThrows(java.io.IOException::class.java) {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun cancellingClosesTheWaitingListener() {
        LoopbackAuthorizationReceiver(port = 0).use { receiver ->
            val controller = AgentRunController()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<String> { receiver.awaitCode("state", controller) }
                controller.cancel()
                val failure = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
                assertEquals(AgentRunCancelledException::class.java, failure.cause!!.javaClass)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun acceptsCodeOnlyForTheMatchingTransaction() {
        LoopbackAuthorizationReceiver(port = 0).use { receiver ->
            val result = receiver.validateCallback("/auth/callback?code=test-code&state=expected", "expected")
            assertEquals("test-code", result)
        }
    }

    @Test fun rejectsWrongPathStateIssuerAndDuplicateFields() {
        LoopbackAuthorizationReceiver(port = 0).use { receiver ->
            val invalid = listOf(
                "/callback?code=test&state=expected",
                "/auth/callback?code=test&state=wrong",
                "/auth/callback?code=test&state=expected&state=expected",
                "/auth/callback?code=test&state=expected&iss=https://example.invalid",
            )
            invalid.forEach { target -> assertThrows(ProviderAuthException::class.java) {
                receiver.validateCallback(target, "expected")
            } }
        }
    }

    @Test fun distinguishesConsentDeclineFromUntrustedCallbacks() {
        LoopbackAuthorizationReceiver(port = 0).use { receiver ->
            val error = assertThrows(ProviderAuthException::class.java) {
                receiver.validateCallback("/auth/callback?error=access_denied&state=expected", "expected")
            }
            assertEquals("oauth_access_denied", error.code)
        }
    }
}
