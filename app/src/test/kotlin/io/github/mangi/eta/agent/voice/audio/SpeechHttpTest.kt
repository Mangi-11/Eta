package io.github.mangi.eta.agent.voice.audio

import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class SpeechHttpTest {
    @Test fun cancellationClosesConnectionWhileBodyIsStillArriving() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val headersSent = CompletableDeferred<Unit>()
            val disconnected = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* 读完请求头，再模拟未完成的响应。 */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nContent-Type: application/json\r\n\r\n{".toByteArray())
                        flush()
                    }
                    headersSent.complete(Unit)
                    input.read() == -1
                }
            }
            try {
                val job = launch {
                    SpeechHttp.json(Request.Builder().url("http://127.0.0.1:${server.localPort}/audio").build())
                    fail("An incomplete canceled body must not succeed")
                }
                withTimeout(3000) { headersSent.await() }
                job.cancelAndJoin()
                assertTrue(disconnected.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun authenticationAndRateLimitAreDistinctFromServerErrors() {
        assertEquals(SpeechErrorCode.AUTHENTICATION, assertThrows(SpeechFailure::class.java) { SpeechHttp.checkStatus(401) }.code)
        assertEquals(SpeechErrorCode.RATE_LIMITED, assertThrows(SpeechFailure::class.java) { SpeechHttp.checkStatus(429) }.code)
        assertEquals(SpeechErrorCode.SERVER, assertThrows(SpeechFailure::class.java) { SpeechHttp.checkStatus(503) }.code)
    }
}
