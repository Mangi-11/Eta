package io.github.mangi.eta.agent.voice.asr

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.AgentProviderClient
import io.github.mangi.eta.agent.model.provider.openai.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.model.provider.ProviderEvent
import io.github.mangi.eta.agent.model.provider.ProviderRequest
import io.github.mangi.eta.agent.model.provider.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.provider.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure

class SpeechTranscriptRefinerTest {
    private val requests = mutableListOf<ProviderRequest>()

    private fun config(capabilities: ModelReasoningCapabilities? = null) = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        systemPrompt = "PROVIDER_IDENTITY", reasoningEffort = ReasoningEffort.HIGH,
        reasoningCapabilities = capabilities,
    )

    private fun refiner(
        config: AgentModelClient.ModelConfig? = config(),
        reply: (AgentRunController) -> ProviderResponse,
    ) = SpeechTranscriptRefiner(loadConfig = { config }, providerFor = {
        object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                reply(runController).also { requests += request }
        }
    })

    private fun reply(text: String, finish: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", finish))

    @Test fun correctedTextIsReturnedFromToolFreeRequest() = runBlocking {
        val result = refiner { reply("用 Python 解析 JSON") }.refine("用配森解析杰森")
        assertEquals("用 Python 解析 JSON", result)
        val request = requests.single()
        assertEquals(ProviderRequestPurpose.TRANSCRIPT_REFINE, request.purpose)
        assertEquals(0, request.effectiveTools.length())
        // 识别文字只作为用户数据进入请求，系统提示不拼接用户内容。
        assertEquals(SpeechTranscriptRefiner.SYSTEM_PROMPT, request.messages.getJSONObject(0).getString("content"))
        assertTrue(request.messages.getJSONObject(1).toString().contains("用配森解析杰森"))
    }

    @Test fun suspiciousOrIncompleteOutputKeepsOriginal() = runBlocking {
        val original = "帮我打开设置"
        assertEquals(original, refiner { reply("好的，我已经帮你把这句话整理成了更通顺的表达：请帮我打开系统设置页面") }.refine(original))
        assertEquals(original, refiner { reply("") }.refine(original))
        assertEquals(original, refiner { reply("帮我打开设置", finish = "length") }.runCatching { refine(original) }.getOrDefault(original))
    }

    @Test fun lengthLimitsSkipModelCall() = runBlocking {
        assertEquals("嗯", refiner { error("不应请求模型") }.refine("嗯"))
        val long = "很长".repeat(1_001)
        assertEquals(long, refiner { error("不应请求模型") }.refine(long))
    }

    @Test fun missingChatModelIsConfigurationFailure() = runBlocking {
        val failure = runCatching { refiner(config = null) { reply("x") }.refine("测试文本") }.exceptionOrNull()
        assertEquals(SpeechErrorCode.CONFIGURATION, (failure as SpeechFailure).code)
    }

    @Test fun reasoningUsesLowestSelectableEffort() = runBlocking {
        refiner(config(ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
        ))) { reply("测试文本") }.refine("测试文本")
        refiner(config(ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), mandatory = true,
        ))) { reply("测试文本") }.refine("测试文本")
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.LOW), requests.map { it.config.reasoningEffort })
        // 未声明推理能力的模型保留用户配置，不发送它可能不支持的参数。
        refiner { reply("测试文本") }.refine("测试文本")
        assertEquals(ReasoningEffort.HIGH, requests.last().config.reasoningEffort)
    }

    @Test fun timeoutCancelsBlockedProviderCall() {
        val cancelled = CountDownLatch(1)
        val failure = runCatching {
            runBlocking {
                refiner { controller ->
                    val released = CountDownLatch(1)
                    controller.register { cancelled.countDown(); released.countDown() }
                    released.await(30, TimeUnit.SECONDS)
                    reply("迟到")
                }.refine("测试文本")
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
