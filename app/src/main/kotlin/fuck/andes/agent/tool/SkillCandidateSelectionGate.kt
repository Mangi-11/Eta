package fuck.andes.agent.tool

import java.text.Normalizer
import java.util.Locale

/** 把模型选择的 GitHub Skill 路径约束到当前顶层用户输入明确点名的候选。 */
internal object SkillCandidateSelectionGate {
    data class Candidate(
        val path: String,
        val name: String,
    )

    data class Denial(val message: String)

    fun validate(
        prompt: String,
        candidates: Collection<Candidate>,
        selectedPaths: Collection<String>,
        maximumSelection: Int = 20,
    ): Denial? {
        if (candidates.size <= 1) return null

        val normalizedPrompt = normalize(prompt)
        val candidatesByPath = candidates.associateBy { it.path }
        val selected = selectedPaths.toSet()
        val requestsAll = ALL_SKILLS_REQUEST.containsMatchIn(normalizedPrompt)
        if (requestsAll && ALL_SELECTION_EXCLUSION.containsMatchIn(normalizedPrompt)) {
            return Denial("全部安装请求包含排除语义，请改为逐项明确选择 Skill")
        }
        if (requestsAll) {
            if (candidates.size > maximumSelection) {
                return Denial("候选超过 $maximumSelection 个，不能一次安装全部；请明确选择路径")
            }
            if (selected != candidatesByPath.keys) {
                return Denial("用户要求安装全部候选时，paths 必须与本轮检查结果完全一致")
            }
            return null
        }

        val names = candidates.groupingBy { normalize(it.name) }.eachCount()
        selectedPaths.forEach { path ->
            val candidate = candidatesByPath[path]
                ?: return Denial("所选路径不在本轮检查候选中：$path")
            val pathMentioned = path != "." && normalizedPrompt.containsExactTerm(normalize(path))
            val normalizedName = normalize(candidate.name)
            val uniqueNameMentioned =
                names[normalizedName] == 1 &&
                    normalizedPrompt.explicitlySelectsName(normalizedName)
            if (!pathMentioned && !uniqueNameMentioned) {
                return Denial("多候选仓库需要用户明确点名 Skill 路径或唯一名称：$path")
            }
        }
        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()

    private fun String.containsExactTerm(term: String): Boolean {
        if (term.isBlank()) return false
        return Regex(
            "(?<![\\p{L}\\p{N}._/-])${Regex.escape(term)}" +
                "(?![\\p{L}\\p{N}._/-])",
        ).containsMatchIn(this)
    }

    private fun String.explicitlySelectsName(name: String): Boolean {
        if (name.isBlank()) return false
        if (
            Regex("^\\\$skill-installer\\s+${Regex.escape(name)}\\s*$")
                .matches(this)
        ) {
            return true
        }
        if (!CANONICAL_LEAF_NAME.matches(name) || name in GRAMMATICAL_NON_NAMES) {
            return false
        }
        return Regex(
            "(?<![\\p{L}\\p{N}._/-])${Regex.escape(name)}" +
                "(?:\\s+skills?\\b|\\s*技能)",
        ).containsMatchIn(this)
    }

    private val ALL_SKILLS_REQUEST = Regex(
        "^\\\$skill-installer\\s+(?:(?:全部|所有)(?:\\s*(?:skills?|技能))?|" +
            "all(?:\\s+skills?)?)\\s*$|" +
            "^(?!.*(?:不是|并非|不含|除了|排除).{0,16}(?:全部|所有))" +
            ".{0,80}(?:安装|装上|导入|更新).{0,32}(?:全部|所有)(?:的)?\\s*" +
            "(?:skills?|技能|候选)|" +
            "^(?!.*(?:not\\s+all|except|excluding)).{0,80}" +
            "(?:install|import|update).{0,32}\\ball\\s+(?:the\\s+)?" +
            "(?:skills?|candidates)\\b",
    )
    private val ALL_SELECTION_EXCLUSION = Regex(
        "除了|排除|不包括|不包含|不含|但不要|不要|除外|以外|" +
            "\\b(?:except|excluding|exclude|without|not\\s+all|but\\s+not|" +
            "do\\s+not|don't)\\b",
    )
    private val CANONICAL_LEAF_NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    /** 这些词在 `<word> Skill` 中承担语法作用，不能被解释为候选名称。 */
    private val GRAMMATICAL_NON_NAMES = setOf(
        "a",
        "an",
        "the",
        "skill",
        "skills",
        "技能",
        "install",
        "installation",
        "import",
        "update",
        "upgrade",
        "this",
        "that",
        "these",
        "those",
        "some",
        "any",
        "my",
        "your",
        "这个",
        "那个",
        "该",
        "此",
        "一个",
        "某个",
        "这些",
        "那些",
        "candidate",
        "candidates",
        "候选",
        "all",
        "全部",
        "所有",
        "repo",
        "repository",
        "仓库",
    )
}
