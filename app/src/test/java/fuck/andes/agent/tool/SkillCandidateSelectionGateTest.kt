package fuck.andes.agent.tool

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillCandidateSelectionGateTest {
    @Test
    fun `single candidate needs no extra path confirmation`() {
        assertNull(
            validate(
                prompt = "安装这个仓库里的 Skill",
                candidates = listOf(candidate("skills/demo")),
                selected = listOf("skills/demo"),
            ),
        )
    }

    @Test
    fun `multiple candidates require every selected path or unique leaf name`() {
        val candidates = listOf(candidate("skills/linear"), candidate("skills/calendar"))

        assertNotNull(validate("安装这个仓库里的 Skill", candidates, listOf("skills/linear")))
        assertNull(validate("安装 linear Skill", candidates, listOf("skills/linear")))
        assertNull(
            validate(
                "安装 skills/linear 和 skills/calendar",
                candidates,
                listOf("skills/linear", "skills/calendar"),
            ),
        )
    }

    @Test
    fun `installer shorthand binds exact leaf name`() {
        assertNull(
            validate(
                prompt = "\$skill-installer linear",
                candidates = listOf(
                    candidate("skills/.curated/linear"),
                    candidate("skills/.curated/openai-docs"),
                ),
                selected = listOf("skills/.curated/linear"),
            ),
        )
        assertNotNull(
            validate(
                prompt = "\$skill-installer linear-extra",
                candidates = listOf(
                    candidate("skills/.curated/linear"),
                    candidate("skills/.curated/openai-docs"),
                ),
                selected = listOf("skills/.curated/linear"),
            ),
        )
        assertNull(
            validate(
                prompt = "Please install linear Skill",
                candidates = listOf(
                    candidate("skills/.curated/linear"),
                    candidate("skills/.curated/openai-docs"),
                ),
                selected = listOf("skills/.curated/linear"),
            ),
        )
    }

    @Test
    fun `all request must select the full inspected set within limit`() {
        val candidates = listOf(candidate("skills/linear"), candidate("skills/calendar"))

        assertNull(
            validate(
                "安装所有 Skills",
                candidates,
                listOf("skills/linear", "skills/calendar"),
            ),
        )
        assertNull(
            validate(
                "\$skill-installer 全部技能",
                candidates,
                listOf("skills/linear", "skills/calendar"),
            ),
        )
        assertNotNull(validate("安装所有 Skills", candidates, listOf("skills/linear")))
        assertNotNull(
            validate(
                "安装 linear Skill，而不是所有 Skills",
                candidates,
                listOf("skills/linear", "skills/calendar"),
            ),
        )
        listOf(
            "安装所有 Skills，除了 calendar",
            "安装所有 Skills，但不要 calendar",
            "安装所有 Skills except calendar",
            "install all Skills，除了 calendar",
            "install all Skills except calendar",
        ).forEach { prompt ->
            assertNotNull(
                prompt,
                validate(
                    prompt,
                    candidates,
                    listOf("skills/linear", "skills/calendar"),
                ),
            )
        }
        assertNotNull(
            validate(
                "install all Skills",
                (1..21).map { candidate("skills/skill-$it") },
                (1..20).map { "skills/skill-$it" },
            ),
        )
    }

    @Test
    fun `duplicate leaf names require a complete relative path`() {
        val candidates = listOf(
            candidate("teams/a/linear"),
            candidate("teams/b/linear"),
        )

        assertNotNull(validate("安装 linear Skill", candidates, listOf("teams/a/linear")))
        assertNull(validate("安装 teams/a/linear", candidates, listOf("teams/a/linear")))
    }

    @Test
    fun `generic leaf names never count as an explicit candidate selection`() {
        val promptsByGenericName = mapOf(
            "skill" to "安装这个仓库里的 Skill",
            "skills" to "install Skills from this repository",
            "技能" to "安装这个仓库里的技能",
            "install" to "install a Skill from this repository",
            "import" to "import a Skill from this repository",
            "update" to "update this Skill",
            "this" to "install this Skill",
            "这个" to "安装这个 Skill",
            "该" to "安装该技能",
        )

        promptsByGenericName.forEach { (name, prompt) ->
            val candidates = listOf(
                SkillCandidateSelectionGate.Candidate("repo/$name", name),
                candidate("repo/other"),
            )
            assertNotNull(
                name,
                validate(prompt, candidates, listOf("repo/$name")),
            )
            assertNull(
                validate("安装 repo/$name", candidates, listOf("repo/$name")),
            )
        }
    }

    @Test
    fun `arbitrary words in an install sentence are not candidate selections`() {
        val prompt = "Please install a Skill from this repository"
        listOf("a", "please", "install", "from", "this", "repository").forEach { name ->
            val candidates = listOf(
                SkillCandidateSelectionGate.Candidate("repo/$name", name),
                candidate("repo/other"),
            )

            assertNotNull(
                name,
                validate(prompt, candidates, listOf("repo/$name")),
            )
        }
    }

    private fun validate(
        prompt: String,
        candidates: List<SkillCandidateSelectionGate.Candidate>,
        selected: List<String>,
    ) = SkillCandidateSelectionGate.validate(prompt, candidates, selected)

    private fun candidate(path: String) = SkillCandidateSelectionGate.Candidate(
        path = path,
        name = path.substringAfterLast('/'),
    )
}
