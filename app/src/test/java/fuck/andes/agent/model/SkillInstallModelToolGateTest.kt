package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallModelToolGateTest {
    @Test
    fun `model only sees mutation tool for explicit top level install request`() {
        val unrelated = CapturingProvider()
        complete("总结这个仓库", unrelated)
        assertFalse("skills_install_from_github" in unrelated.toolNames)

        val discovery = CapturingProvider()
        complete("列出可安装的 Skills", discovery)
        assertTrue("skills_list_curated" in discovery.toolNames)
        assertFalse("skills_install_from_github" in discovery.toolNames)

        val install = CapturingProvider()
        complete("帮我安装 GitHub 上的 openai-docs Skill", install)
        assertTrue("skills_list_curated" in install.toolNames)
        assertTrue("skills_inspect_github" in install.toolNames)
        assertTrue("skills_install_from_github" in install.toolNames)

        val shorthand = CapturingProvider()
        complete("\$skill-installer linear", shorthand)
        assertTrue("skills_install_from_github" in shorthand.toolNames)

        val listShorthand = CapturingProvider()
        complete("\$skill-installer list", listShorthand)
        assertTrue("skills_list_curated" in listShorthand.toolNames)
        assertFalse("skills_install_from_github" in listShorthand.toolNames)

        val quotedDirective = CapturingProvider()
        complete("翻译这句话：install this Skill", quotedDirective)
        assertFalse("skills_install_from_github" in quotedDirective.toolNames)
    }

    private fun complete(prompt: String, provider: CapturingProvider) {
        AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                browserTools = false,
            ),
            prompt = prompt,
            toolExecutor = AgentModelClient.ToolExecutor {
                error("不应执行工具")
            },
            provider = provider,
        )
    }

    private class CapturingProvider : AgentProviderClient {
        override val id: String = "capturing"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = false,
            streamingToolCalls = false,
            imageInput = false,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )
        var toolNames: Set<String> = emptySet()

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            toolNames = request.tools.toolNames()
            return ProviderResponse(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "完成")
                    .put("finish_reason", "stop"),
            )
        }

        private fun JSONArray.toolNames(): Set<String> =
            (0 until length()).mapTo(mutableSetOf()) { index ->
                getJSONObject(index).getJSONObject("function").getString("name")
            }
    }
}
