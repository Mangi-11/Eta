package fuck.andes.agent.tool

import fuck.andes.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSkillConflictCapabilityParserTest {
    @Test
    fun parsesLatestLinkedInstallConflict() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult("install-1", conflictResult()),
                assistant("需要你确认覆盖 demo"),
            ),
        )

        assertEquals(
            PendingSkillConflictCapability(
                repository = "example/skills",
                commitSha = COMMIT_SHA,
                selectedPath = "skills/demo",
                expectedReplacementId = "demo",
                expectedReplacementName = "Demo Skill",
            ),
            capability,
        )
    }

    @Test
    fun rejectsConflictJsonReturnedByAnotherTool() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("查看 README"),
                assistantToolCall("read-1", "terminal_exec"),
                toolResult("read-1", conflictResult()),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun latestInstallSuccessInvalidatesOlderConflict() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult("install-1", conflictResult()),
                assistantToolCall("install-2", "skills_install_from_github"),
                toolResult(
                    "install-2",
                    JSONObject()
                        .put("ok", true)
                        .put("installed", JSONArray().put(JSONObject().put("id", "demo")))
                        .toString(),
                ),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun ignoresConflictBeforeLatestUserTurn() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult("install-1", conflictResult()),
                assistant("需要你确认覆盖 demo"),
                user("先解释一下覆盖的影响"),
                assistant("覆盖会替换用户安装的同名 Skill"),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun malformedLatestInstallResultDoesNotReviveOlderConflict() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult("install-1", conflictResult()),
                assistantToolCall("install-2", "skills_install_from_github"),
                toolResult("install-2", "{not-json"),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun newerInstallCallWithoutResultInvalidatesOlderConflict() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult("install-1", conflictResult()),
                assistantToolCall("install-2", "skills_install_from_github"),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun olderResultInParallelBatchCannotStandInForMissingLatestResult() {
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装两个 Skills"),
                assistantToolCalls(
                    "install-1" to "skills_install_from_github",
                    "install-2" to "skills_install_from_github",
                ),
                toolResult("install-1", conflictResult()),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun rejectsMalformedConflictShape() {
        val malformed = JSONObject(conflictResult())
            .put("commitSha", "main")
            .put(
                "conflicts",
                JSONArray().put(
                    JSONObject()
                        .put("id", "demo")
                        .put("name", "Demo Skill")
                        .put("replaceAllowed", "true"),
                ),
            )
            .toString()

        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult("install-1", malformed),
            ),
        )

        assertNull(capability)
    }

    @Test
    fun acceptsSha256LengthCommitIdentity() {
        val sha256 = "a".repeat(64)
        val capability = PendingSkillConflictCapabilityParser.parse(
            listOf(
                user("安装 demo Skill"),
                assistantToolCall("install-1", "skills_install_from_github"),
                toolResult(
                    "install-1",
                    JSONObject(conflictResult()).put("commitSha", sha256).toString(),
                ),
            ),
        )

        assertEquals(sha256, capability?.commitSha)
    }

    private fun conflictResult(): String = JSONObject()
        .put("ok", false)
        .put("code", "SKILL_CONFLICT")
        .put("repository", "example/skills")
        .put("commitSha", COMMIT_SHA)
        .put("selectedPaths", JSONArray().put("skills/demo"))
        .put(
            "conflicts",
            JSONArray().put(
                JSONObject()
                    .put("id", "demo")
                    .put("name", "Demo Skill")
                    .put("replaceAllowed", true),
            ),
        )
        .toString()

    private fun assistantToolCall(id: String, name: String) =
        assistantToolCalls(id to name)

    private fun assistantToolCalls(vararg calls: Pair<String, String>) =
        AgentModelClient.ConversationMessage(
            role = "assistant",
            toolCallsJson = JSONArray().also { array ->
                calls.forEach { (id, name) ->
                    array.put(
                        JSONObject()
                            .put("id", id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", name)
                                    .put("arguments", "{}"),
                            ),
                    )
                }
            }.toString(),
        )

    private fun toolResult(id: String, content: String) =
        AgentModelClient.ConversationMessage(
            role = "tool",
            toolCallId = id,
            content = content,
        )

    private fun user(content: String) = AgentModelClient.ConversationMessage(
        role = "user",
        content = content,
    )

    private fun assistant(content: String) = AgentModelClient.ConversationMessage(
        role = "assistant",
        content = content,
    )

    private companion object {
        const val COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
