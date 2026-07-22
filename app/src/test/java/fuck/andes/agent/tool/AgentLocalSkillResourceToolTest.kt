package fuck.andes.agent.tool

import android.content.Context
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.skill.SkillIndexService
import fuck.andes.agent.skill.SkillResourceReader
import fuck.andes.core.AgentLogger
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalSkillResourceToolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("fuck_andes.db")
    }

    @Test
    fun enabledSkillResourceIsReadAndTruncatedWithoutTerminalPermission() {
        val fixture = fixture()
        val fullText = "guide-" + "x".repeat(700)
        File(fixture.skillRoot, "references/guide.md").apply {
            parentFile?.mkdirs()
            writeText(fullText)
        }

        val json = execute(
            fixture,
            relativePath = "references/guide.md",
            maxChars = 512,
        )

        assertTrue(json.toString(), json.getBoolean("ok"))
        assertEquals("references/guide.md", json.getString("relativePath"))
        assertEquals(512, json.getString("text").length)
        assertEquals(fullText.length, json.getInt("totalChars"))
        assertTrue(json.getBoolean("truncated"))
    }

    @Test
    fun traversalIsRejectedByCoreResourceReader() {
        val fixture = fixture()

        val json = execute(fixture, relativePath = "../outside.txt", maxChars = 512)

        assertFalse(json.optBoolean("ok", false))
        assertEquals("INVALID_RELATIVE_PATH", json.getString("code"))
    }

    private fun fixture(): Fixture {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillRoot = File(skillsRoot, "resource-demo").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText(
            """
            ---
            name: resource-demo
            description: Read bounded reference files in tests.
            ---

            # Resource demo
            """.trimIndent(),
        )
        val indexService = SkillIndexService(context, skillsRoot)
        indexService.listSkillsForManagement(forceRefresh = true)
        return Fixture(
            skillRoot = skillRoot,
            tools = AgentLocalTools(
                context = context,
                logger = NoOpLogger,
                terminalToolsEnabled = { false },
                skillIndexService = indexService,
                skillResourceReader = SkillResourceReader(skillsRoot),
                runAvailableSkillIds = setOf("resource-demo"),
            ),
        )
    }

    private fun execute(fixture: Fixture, relativePath: String, maxChars: Int): JSONObject {
        val result = fixture.tools.execute(
            AgentModelClient.ToolCall(
                id = "resource-1",
                name = "skills_read_resource",
                argumentsJson = JSONObject()
                    .put("skillId", "resource-demo")
                    .put("relativePath", relativePath)
                    .put("maxChars", maxChars)
                    .toString(),
            ),
        )
        fixture.tools.close()
        return JSONObject(result.content)
    }

    private data class Fixture(
        val skillRoot: File,
        val tools: AgentLocalTools,
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
