package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeServiceRetentionTest {
    @Test
    fun dismissReleasesContinuationHistoryBeforeTheServiceIsDestroyed() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val request = AgentRuntimeWire.RunRequest(
                runId = "completed-run",
                prompt = "请求",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                ),
                images = emptyList(),
                history = listOf(AgentModelClient.ConversationMessage("user", "已完成的历史")),
            )
            val run = RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId)).apply {
                completedContext = RuntimeRunRegistry.CompletedContext(request, AgentModelClient.ModelResponse.Text("完成"))
            }
            val retainedRun = AgentRuntimeService::class.java.getDeclaredField("displayedRun").apply {
                isAccessible = true
            }
            retainedRun.set(service, run)
            assertSame(run, retainedRun.get(service))

            AgentRuntimeService::class.java.getDeclaredMethod("dismissAndStop").apply {
                isAccessible = true
            }.invoke(service)

            assertNull(retainedRun.get(service))
            assertNull(run.completedContext)
        } finally {
            controller.destroy()
        }
    }
}
