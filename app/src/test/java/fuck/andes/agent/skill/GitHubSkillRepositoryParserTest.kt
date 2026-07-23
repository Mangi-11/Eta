package fuck.andes.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubSkillRepositoryParserTest {
    @Test
    fun `parses slug and repository URLs`() {
        assertEquals(
            GitHubSkillRepository("openai", "skills"),
            GitHubSkillRepositoryParser.parse("openai/skills"),
        )
        assertEquals(
            GitHubSkillRepository("openai", "skills"),
            GitHubSkillRepositoryParser.parse("https://github.com/openai/skills.git"),
        )
    }

    @Test
    fun `parses tree and skill blob URLs into explicit candidate root`() {
        assertEquals(
            GitHubSkillRepository(
                owner = "openai",
                repository = "skills",
                ref = "main",
                path = "skills/.curated/openai-docs",
            ),
            GitHubSkillRepositoryParser.parse(
                "https://github.com/openai/skills/tree/main/skills/.curated/openai-docs",
            ),
        )
        assertEquals(
            GitHubSkillRepository(
                owner = "openai",
                repository = "skills",
                ref = "main",
                path = "skills/.curated/openai-docs",
            ),
            GitHubSkillRepositoryParser.parse(
                "https://github.com/openai/skills/blob/main/skills/.curated/openai-docs/SKILL.md",
            ),
        )
    }

    @Test
    fun `explicit values fill missing URL fields but cannot contradict them`() {
        val resolved = GitHubSkillRepositoryParser.resolve(
            repository = "openai/skills",
            explicitRef = "release/skills-v2",
            explicitPath = "skills/example",
        )

        assertEquals("release/skills-v2", resolved.ref)
        assertEquals("skills/example", resolved.path)
        assertThrows(GitHubSkillSourceException::class.java) {
            GitHubSkillRepositoryParser.resolve(
                repository = "https://github.com/openai/skills/tree/main/skills/.curated",
                explicitRef = "release",
                explicitPath = null,
            )
        }
    }

    @Test
    fun `rejects non GitHub hosts credentials ports and traversal`() {
        listOf(
            "https://example.com/openai/skills",
            "http://github.com/openai/skills",
            "https://token@github.com/openai/skills",
            "https://github.com:443/openai/skills",
            "https://github.com/openai/skills/issues",
            "https://github.com/openai/skills/tree/main/a//b",
            "openai/..",
        ).forEach { source ->
            assertThrows(source, GitHubSkillSourceException::class.java) {
                GitHubSkillRepositoryParser.parse(source)
            }
        }
        assertThrows(GitHubSkillSourceException::class.java) {
            GitHubSkillRepositoryParser.normalizeRelativePath("skills/../secret")
        }
    }

    @Test
    fun `repository root has no implicit ref or path`() {
        val source = GitHubSkillRepositoryParser.parse("https://github.com/openai/skills")

        assertNull(source.ref)
        assertNull(source.path)
    }

    @Test
    fun `slash refs are accepted only when structurally safe`() {
        assertEquals(
            "feature/skill-install",
            GitHubSkillRepositoryParser.resolve(
                repository = "openai/skills",
                explicitRef = "feature/skill-install",
                explicitPath = null,
            ).ref,
        )
        listOf("/main", "main/", "main//next", "main/../secret", "main\\next", "x.lock")
            .forEach { ref ->
                assertThrows(ref, GitHubSkillSourceException::class.java) {
                    GitHubSkillRepositoryParser.resolve("openai/skills", ref, null)
                }
            }
    }
}
