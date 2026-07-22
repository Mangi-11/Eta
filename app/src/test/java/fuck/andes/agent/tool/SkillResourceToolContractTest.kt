package fuck.andes.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillResourceToolContractTest {
    @Test
    fun `resource reader requires bounded string identifiers and path`() {
        assertEquals(
            "skillId",
            ToolArgumentContract.validate("skills_read_resource", JSONObject())?.field,
        )
        assertEquals(
            "relativePath",
            ToolArgumentContract.validate(
                "skills_read_resource",
                JSONObject().put("skillId", "demo"),
            )?.field,
        )
        assertEquals(
            "maxChars",
            ToolArgumentContract.validate(
                "skills_read_resource",
                JSONObject()
                    .put("skillId", "demo")
                    .put("relativePath", "references/guide.md")
                    .put("maxChars", 64_001),
            )?.field,
        )
    }

    @Test
    fun `valid resource reader arguments pass contract`() {
        assertNull(
            ToolArgumentContract.validate(
                "skills_read_resource",
                JSONObject()
                    .put("skillId", "demo")
                    .put("relativePath", "references/guide.md")
                    .put("maxChars", 512),
            ),
        )
    }
}
