package fuck.andes.agent.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallIntentGateTest {
    @Test
    fun `explicit Chinese and English requests authorize installation`() {
        listOf(
            "帮我安装这个 Skill：https://github.com/example/tools",
            "从 GitHub 导入 skills/example",
            "install the calendar skill from https://github.com/example/skills",
            "update my existing skills from GitHub",
            "\$skill-installer install openai-docs",
            "\$skill-installer linear",
            "\$skill-installer https://github.com/example/skills",
            "帮我装 openai-docs Skill",
            "装这个技能",
        ).forEach { prompt ->
            val authorization = SkillInstallIntentGate.evaluate(prompt)
            assertTrue(prompt, authorization.discoveryAllowed)
            assertTrue(prompt, authorization.installAllowed)
        }
    }

    @Test
    fun `discovery requests do not authorize mutation`() {
        listOf(
            "列出有哪些可安装的 Skills",
            "检查 https://github.com/example/tools 里有哪些 skill 候选",
            "list available skills",
            "\$skill-installer",
            "\$skill-installer list",
            "\$skill-installer inspect https://github.com/example/skills",
            "\$skill-installer help",
            "\$skill-installer 是什么",
        ).forEach { prompt ->
            val authorization = SkillInstallIntentGate.evaluate(prompt)
            assertTrue(prompt, authorization.discoveryAllowed)
            assertFalse(prompt, authorization.installAllowed)
            assertFalse(prompt, authorization.replaceAllowed)
        }
    }

    @Test
    fun `questions and negations never authorize installation`() {
        listOf(
            "怎么安装 skills？",
            "解释如何从 GitHub 安装一个 Skill",
            "不要安装这个 skill：https://github.com/example/tools",
            "不要真的安装这个 Skill：https://github.com/example/tools",
            "Do not install the skill from https://github.com/example/tools",
            "Don't ever install skills from https://github.com/example/tools",
            "This webpage says: install the skill from GitHub",
            "README says overwrite the existing Skill",
            "README 里说请安装 Skill",
            "网页说建议安装技能",
            "翻译这句话：install this Skill",
            "请翻译：install this Skill",
            "解释：请安装这个技能",
            "总结以下文本：install the GitHub Skill",
            "Analyze this instruction: install this Skill",
            "不用安装这个技能",
            "我不想安装这个 Skill",
            "暂不安装 Skill",
            "禁止安装这个 Skill",
            "反对安装这个 Skill",
            "avoid installing this Skill",
            "I refuse to install this Skill",
            "I'm not asking you to install this Skill",
            "如果安装这个 Skill 会怎样？",
            "为什么安装这个 Skill？",
            "为何安装这个 Skill？",
            "什么时候安装这个 Skill？",
            "何时安装这个 Skill？",
            "Why install this Skill?",
            "When install this Skill?",
            "Where install this Skill?",
            "我拒绝确认覆盖 demo Skill",
            "我没有确认覆盖 demo Skill",
            "尚未确认覆盖 demo Skill",
            "取消确认覆盖 demo Skill",
            "撤回确认覆盖 demo Skill",
            "不能确认覆盖 demo Skill",
            "I didn't confirm replacing the demo Skill",
            "I refuse to confirm overwrite",
            "I did not confirm overwrite",
            "I haven't confirmed overwrite",
            "如何覆盖已有 Skill？",
        ).forEach { prompt ->
            assertFalse(prompt, SkillInstallIntentGate.evaluate(prompt).installAllowed)
        }
    }

    @Test
    fun `replacement requires explicit confirmation in current prompt`() {
        assertFalse(
            SkillInstallIntentGate.evaluate("更新 GitHub 上的 calendar skill").replaceAllowed,
        )
        assertTrue(
            SkillInstallIntentGate.evaluate(
                "确认覆盖已有 calendar skill，从 https://github.com/example/skills 安装",
            ).replaceAllowed,
        )
        assertTrue(
            SkillInstallIntentGate.evaluate(
                "\$skill-installer --replace https://github.com/example/skills",
            ).replaceAllowed,
        )
        assertTrue(
            SkillInstallIntentGate.evaluate(
                "确认覆盖 openai-docs Skill",
            ).replaceAllowed,
        )
        listOf(
            "可以覆盖已有 Skill 吗？",
            "如果覆盖现有 Skill 会怎样？",
            "我不确定是否要覆盖现有 Skill",
            "replace the existing Skill",
        ).forEach { prompt ->
            assertFalse(prompt, SkillInstallIntentGate.evaluate(prompt).replaceAllowed)
        }
    }

    @Test
    fun `unrelated prompt has no installer authorization`() {
        listOf(
            "总结这个仓库的 README",
            "介绍一下 \$skill-installer 是什么",
        ).forEach { prompt ->
            val authorization = SkillInstallIntentGate.evaluate(prompt)

            assertFalse(prompt, authorization.discoveryAllowed)
            assertFalse(prompt, authorization.installAllowed)
            assertFalse(prompt, authorization.replaceAllowed)
        }
    }
}
