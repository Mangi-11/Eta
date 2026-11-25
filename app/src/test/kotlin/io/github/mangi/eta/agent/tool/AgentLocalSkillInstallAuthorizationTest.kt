package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalSkillInstallAuthorizationTest {
    @Test
    fun installationRequiresInspectionInTheSameExecutor() {
        val tools = tools()

        val result = tools.execute(installCall(replaceExisting = false))

        assertEquals(
            "SKILL_INSPECTION_REQUIRED",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun replacementStillRequiresLinkedPriorConflict() {
        val tools = tools()

        val result = tools.execute(installCall(replaceExisting = true))

        assertEquals(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun oneConfirmationCannotAuthorizeReplacingMultiplePaths() {
        val tools = tools()

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                paths = listOf("skills/demo", "skills/other"),
            ),
        )

        assertEquals(
            "SKILL_REPLACE_SCOPE_TOO_BROAD",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun replacementMustExactlyReplayLinkedConflictCapability() {
        val tools = tools(
            pending = pendingConflict(),
        )

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                repository = "example/skills",
                ref = COMMIT_SHA,
                paths = listOf("skills/other"),
                expectedReplacementId = "demo",
            ),
        )

        assertEquals(
            "SKILL_REPLACE_CAPABILITY_MISMATCH",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun linkedConflictCanBeReplayedWithoutPromptWording() {
        val tools = tools(
            pending = pendingConflict(),
        )

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                repository = "example/skills",
                ref = COMMIT_SHA,
                paths = listOf("skills/demo"),
                expectedReplacementId = "demo",
            ),
        )

        assertEquals(
            "SKILL_INSTALLER_UNAVAILABLE",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    private fun tools(
        pending: PendingSkillConflictCapability? = null,
    ): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        pendingSkillConflict = pending,
    )

    private fun installCall(
        replaceExisting: Boolean,
        paths: List<String> = listOf("skills/.curated/openai-docs"),
        repository: String = "openai/skills",
        ref: String? = null,
        expectedReplacementId: String = "openai-docs",
    ) = AgentModelClient.ToolCall(
        id = "install-1",
        name = "skills_install_from_github",
        argumentsJson = JSONObject()
            .put("repository", repository)
            .also { if (ref != null) it.put("ref", ref) }
            .put("paths", org.json.JSONArray(paths))
            .put("replaceExisting", replaceExisting)
            .also { if (replaceExisting) it.put("expectedReplacementId", expectedReplacementId) }
            .toString(),
    )

    private fun pendingConflict() = PendingSkillConflictCapability(
        repository = "example/skills",
        commitSha = COMMIT_SHA,
        selectedPath = "skills/demo",
        expectedReplacementId = "demo",
        expectedReplacementName = "Demo Skill",
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
