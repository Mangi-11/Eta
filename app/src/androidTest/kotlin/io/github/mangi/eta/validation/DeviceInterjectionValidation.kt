package io.github.mangi.eta.validation

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Real Binder + Runtime + HTTP on the phone, with no external model or account changes. */
internal class DeviceInterjectionValidation(private val context: Context, private val record: (String) -> Unit) {
    fun run() {
        val runId = "validation-interjection-${UUID.randomUUID()}"
        val original = runBlocking { SettingsDataStore.settings() }
        runBlocking { SettingsDataStore.updateSettings { it.copy(memoryEnabled = false, autoMemoryEnabled = false, autoSkillsEnabled = false) } }
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val streamed = CountDownLatch(1)
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<AgentRuntimeWire.RunResult>()
        val failure = AtomicReference<Throwable>()
        val acceptor = thread(name = "fixture-http", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept().also(sockets::add)
                    executor.execute {
                        socket.use {
                            try {
                                val input = socket.getInputStream().buffered()
                                val headers = StringBuilder()
                                while (!headers.endsWith("\r\n\r\n") && headers.length < 16_384) {
                                    val byte = input.read()
                                    if (byte < 0) error("FIXTURE_REQUEST_TRUNCATED")
                                    headers.append(byte.toChar())
                                }
                                val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                                    ?.substringAfter(':')?.trim()?.toInt() ?: 0
                                require(length in 1..2_000_000)
                                val body = ByteArray(length)
                                var offset = 0
                                while (offset < length) {
                                    val count = input.read(body, offset, length - offset)
                                    if (count < 0) error("FIXTURE_BODY_TRUNCATED")
                                    offset += count
                                }
                                requests += body.toString(Charsets.UTF_8)
                                val output = socket.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                                if (requests.size == 1) {
                                    output.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"unfinished-fixture-text\"}}]}\n\n".toByteArray())
                                    output.flush()
                                    received.countDown()
                                    release.await(15, TimeUnit.SECONDS)
                                } else {
                                    output.write(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"redirected-fixture\"},\"finish_reason\":\"stop\"}]}\n\n" +
                                        "data: [DONE]\n\n").toByteArray())
                                    output.flush()
                                }
                            } catch (_: Exception) { /* A cancelled first HTTP request closes its socket. */ }
                        }
                    }
                }
            } catch (_: Exception) { /* Closing the server stops the accept loop. */ }
        }
        val logger = object : AgentLogger {
            override fun debug(message: () -> String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }
        val client = AgentRuntimeClient(context, logger)
        val worker = thread(name = "device-runtime-fixture", isDaemon = true) {
            try {
                val config = AgentModelClient.ModelConfig(baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    apiKey = "fixture", model = "fixture", systemPrompt = "Fixture only", autoCompactionEnabled = false,
                    browserTools = false, deviceDirectTools = false, terminalTools = false)
                result.set(client.run(AgentRuntimeWire.RunRequest(runId, "original fixture task", config, emptyList())) {
                    events += it
                    if (it is AgentEvent.AssistantBlockDelta) streamed.countDown()
                })
            } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            verify("real Runtime sends first local HTTP request", received.await(8, TimeUnit.SECONDS))
            verify("real Runtime delivers partial output", streamed.await(3, TimeUnit.SECONDS))
            verify("wrong run cannot receive interjection", !client.steerRun("wrong-run", "wrong fixture instruction"))
            val started = SystemClock.elapsedRealtime()
            verify("active run accepts interjection through Binder", client.steerRun(runId, "redirect fixture now"))
            verify("stalled request is redirected promptly", finished.await(3, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("RUNTIME_FIXTURE_FAILED", it) }
            verify("redirected run succeeds", result.get()?.ok == true && result.get()?.content == "redirected-fixture")
            verify("interjection reaches next model request", requests.size == 2 && requests[1].contains("redirect fixture now"))
            verify("partial output is discarded from next request", !requests[1].contains("unfinished-fixture-text"))
            verify("partial output is discarded from final transcript", result.get()?.transcript.toString().contains("unfinished-fixture-text").not())
            verify("model interruption event crosses Binder", events.any { it is AgentEvent.ModelRequestInterrupted })
            verify("accepted user interjection is preserved", events.any { it is AgentEvent.UserSupplementReceived && it.text == "redirect fixture now" })
            verify("interjection does not produce a failure or retry", events.none { it is AgentEvent.RunFailed || it is AgentEvent.ModelRetryScheduled })
            verify("completed run rejects late interjection", !client.steerRun(runId, "late fixture message"))
            record("Runtime interjection latency: ${SystemClock.elapsedRealtime() - started} ms (includes result validation)")
        } finally {
            release.countDown()
            client.cancelRun(runId)
            server.close()
            sockets.forEach { runCatching { it.close() } }
            worker.join(2_000)
            acceptor.join(1_000)
            executor.shutdownNow()
            AgentRunCheckpointStore.remove(context, runId)
            runBlocking { SettingsDataStore.updateSettings { original } }
        }
    }

    private fun verify(label: String, success: Boolean) { check(success) { label }; record("PASS: $label") }
}
