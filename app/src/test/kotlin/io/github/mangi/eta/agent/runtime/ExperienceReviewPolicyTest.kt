package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExperienceReviewPolicyTest {
    @Test
    fun skillReviewRequiresDifficultGuiWorkOrExplicitRequest() {
        assertFalse(ExperienceReviewPolicy.hasSkillSignal("你好", listOf(finished("tap", true))))
        assertTrue(
            ExperienceReviewPolicy.hasSkillSignal(
                "打开便签",
                List(8) { finished("observe_screen", true) })
        )
        assertTrue(
            ExperienceReviewPolicy.hasSkillSignal(
                "打开便签",
                listOf(finished("tap_element", false), finished("tap_element", true))
            )
        )
        assertFalse(
            ExperienceReviewPolicy.hasSkillSignal(
                "查天气",
                listOf(finished("web_search", false), finished("web_search", true))
            )
        )
        assertTrue(ExperienceReviewPolicy.hasSkillSignal("记住这个操作方法", emptyList()))
    }

    @Test
    fun reviewCannotClearMemoryOperateDeviceOrInstallRemoteCode() {
        assertTrue(
            ExperienceReviewPolicy.permits(
                "memory_write",
                JSONObject().put("mode", "append"),
                true,
                true
            )
        )
        assertFalse(
            ExperienceReviewPolicy.permits(
                "memory_write",
                JSONObject().put("mode", "clear"),
                true,
                true
            )
        )
        assertFalse(
            ExperienceReviewPolicy.permits(
                "memory_write",
                JSONObject().put("mode", "replace_range"),
                true,
                true
            )
        )
        for (tool in listOf("terminal", "tap", "skills_install_from_github", "tasks_create"))
            assertFalse(ExperienceReviewPolicy.permits(tool, JSONObject(), true, true))
    }

    @Test
    fun optOutRevokesWritesAndReadsOnNextDispatch() {
        assertFalse(ExperienceReviewPolicy.permits("memory_get", JSONObject(), false, true))
        assertFalse(ExperienceReviewPolicy.permits("skills_manage", JSONObject(), true, false))
        assertTrue(ExperienceReviewPolicy.permits("skills_read", JSONObject(), false, true))
    }

    @Test
    fun digestOmitsReasoningAndMarksTruncatedEvidence() {
        val response = AgentModelClient.ModelResponse.Text(
            "完成",
            reasoningContent = "secret-reasoning",
            transcript = List(10) {
                AgentModelClient.ConversationMessage(
                    "assistant",
                    "x".repeat(2000),
                    reasoningContent = "secret-reasoning"
                )
            })
        val digest = ExperienceReviewPolicy.digest("任务", response, emptyList(), 1000)
        assertFalse(digest.contains("secret-reasoning"))
        assertTrue(JSONObject(digest).getBoolean("evidence_truncated"))
        assertTrue(digest.length < 2000)
    }

    private fun finished(name: String, success: Boolean) =
        AgentEvent.ToolFinished(1, "call", name, "result", 0, 0, success)
}
