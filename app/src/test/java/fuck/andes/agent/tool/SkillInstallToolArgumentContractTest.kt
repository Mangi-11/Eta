package fuck.andes.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillInstallToolArgumentContractTest {
    @Test
    fun `GitHub installation requires an explicit nonempty path array`() {
        assertEquals(
            "paths",
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject().put("repository", "openai/skills"),
            )?.field,
        )
        assertEquals(
            "paths",
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject()
                    .put("repository", "openai/skills")
                    .put("paths", org.json.JSONArray()),
            )?.field,
        )
        assertEquals(
            "paths",
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject()
                    .put("repository", "openai/skills")
                    .put("paths", org.json.JSONArray().put(1)),
            )?.field,
        )
    }

    @Test
    fun `valid GitHub installation arguments pass contract`() {
        assertNull(
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject()
                    .put("repository", "openai/skills")
                    .put("ref", "main")
                    .put(
                        "paths",
                        org.json.JSONArray().put("skills/.curated/openai-docs"),
                    )
                    .put("replaceExisting", false),
            ),
        )
    }

    @Test
    fun `oversized and duplicate path arguments are rejected`() {
        assertEquals(
            "paths",
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject()
                    .put("repository", "openai/skills")
                    .put(
                        "paths",
                        org.json.JSONArray().put("skills/a").put("skills/a"),
                    ),
            )?.field,
        )
        val oversized = "x".repeat(1_001)
        assertEquals(
            "paths",
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject()
                    .put("repository", "openai/skills")
                    .put("paths", org.json.JSONArray().put(oversized)),
            )?.field,
        )
    }

    @Test
    fun `replacement conditionally requires bounded expected id`() {
        val base = JSONObject()
            .put("repository", "openai/skills")
            .put("ref", "a".repeat(40))
            .put("paths", org.json.JSONArray().put("skills/demo"))
            .put("replaceExisting", true)

        assertEquals(
            "expectedReplacementId",
            ToolArgumentContract.validate("skills_install_from_github", base)?.field,
        )
        assertNull(
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject(base.toString()).put("expectedReplacementId", "demo"),
            ),
        )
        assertEquals(
            "expectedReplacementId",
            ToolArgumentContract.validate(
                "skills_install_from_github",
                JSONObject(base.toString()).put("expectedReplacementId", "x".repeat(501)),
            )?.field,
        )
    }
}
