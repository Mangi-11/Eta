package fuck.andes.agent.skill

import java.text.Normalizer
import java.util.Locale

/**
 * 仅根据当前顶层用户输入判断 Skill 安装授权。
 *
 * 模型回复、网页内容与 Skill 文档都不能扩大这项授权。发现候选是只读操作，安装与替换则逐级
 * 收紧；尤其是替换已有用户 Skill，必须在同一条用户输入中出现明确确认语义。
 */
internal object SkillInstallIntentGate {
    data class Authorization(
        val discoveryAllowed: Boolean,
        val installAllowed: Boolean,
        val replaceAllowed: Boolean,
    )

    fun evaluate(prompt: String): Authorization {
        val normalized = prompt.normalizedForIntent()
        if (normalized.isBlank()) return Authorization(false, false, false)

        val installerInvocation = INSTALLER_INVOCATION.find(normalized)
        val installerInvoked = installerInvocation != null
        val installerArguments = installerInvocation?.groupValues?.getOrNull(1).orEmpty().trim()
        val hasSkillContext = SKILL_CONTEXT.containsMatchIn(normalized) ||
            GITHUB_REPOSITORY.containsMatchIn(normalized) ||
            REPLACE_CONFIRMATION.containsMatchIn(normalized)
        val asksDiscovery = hasSkillContext && DISCOVERY_REQUEST.containsMatchIn(normalized)
        val negatesInstall = INSTALL_NEGATION.containsMatchIn(normalized)
        val asksHow = HOW_TO_REQUEST.containsMatchIn(normalized)
        val quotesExternalDirective = EXTERNAL_DIRECTIVE.containsMatchIn(normalized) ||
            NON_ACTION_CONTENT.containsMatchIn(normalized)
        val asksOrHedgesMutation = MUTATION_QUESTION_OR_HEDGE.containsMatchIn(normalized)
        val asksOrHedgesReplacement = REPLACE_QUESTION_OR_HEDGE.containsMatchIn(normalized)
        val mutationText = normalized.replace("可安装", "")
        val installerShorthandInstall = installerArguments.isNotBlank() &&
            !INSTALLER_DISCOVERY_ARGUMENT.matches(installerArguments)
        val replaceConfirmed = !asksOrHedgesReplacement &&
            REPLACE_CONFIRMATION.containsMatchIn(normalized)
        val requestsMutation =
            !negatesInstall && !asksHow && !quotesExternalDirective && !asksOrHedgesMutation &&
                hasSkillContext &&
                (
                    INSTALL_REQUEST.containsMatchIn(mutationText) ||
                        CHINESE_SHORT_INSTALL.containsMatchIn(mutationText) ||
                        installerShorthandInstall ||
                        replaceConfirmed
                    )
        val replaceAllowed = requestsMutation && replaceConfirmed

        return Authorization(
            discoveryAllowed = installerInvoked || asksDiscovery || requestsMutation,
            installAllowed = requestsMutation,
            replaceAllowed = replaceAllowed,
        )
    }

    private fun String.normalizedForIntent(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    private val INSTALLER_INVOCATION = Regex("^\\\$skill-installer(?:\\s+(.*))?$")
    private val INSTALLER_DISCOVERY_ARGUMENT = Regex(
        "(?:list|browse|find|search|inspect|help|what|info|列出|列表|查看|浏览|搜索|检查|帮助|是什么|介绍)(?:\\s.*)?",
    )
    private val SKILL_CONTEXT = Regex("\\bskills?\\b|技能")
    private val GITHUB_REPOSITORY = Regex("https?://(?:www\\.)?github\\.com/[^\\s/]+/[^\\s/]+", RegexOption.IGNORE_CASE)
    private val DISCOVERY_REQUEST = Regex(
        "列出|列表|有哪些|有什么|查看|浏览|寻找|搜索|检查|候选|可安装|" +
            "\\blist\\b|\\bbrowse\\b|\\bfind\\b|\\bsearch\\b|\\binspect\\b|\\bavailable\\b",
    )
    private val INSTALL_REQUEST = Regex(
        "安装|装上|装一下|导入|更新|升级|重装|重新安装|" +
            "\\binstall\\b|\\bimport\\b|\\bupdate\\b|\\bupgrade\\b|\\breinstall\\b|\\bset up\\b",
    )
    private val CHINESE_SHORT_INSTALL = Regex(
        "(?:帮我|请|现在|直接)?\\s*装(?:上|一下)?\\s*" +
            "(?:这个|该|[a-z0-9._-]{1,100})\\s*(?:skills?|技能)",
    )
    private val INSTALL_NEGATION = Regex(
        "(?:不要|别|无需|不需要|不用|不想|暂不|禁止|反对|拒绝).{0,24}(?:安装|装|导入|更新|替换|覆盖)|" +
            "(?:拒绝|取消|撤回|收回|没有|没|未|尚未|并未|从未|不能|无法|不愿|不再|不).{0,20}" +
            "(?:确认|同意|允许|强制).{0,12}(?:替换|覆盖)|" +
            "\\b(?:do not|don't|never)\\b.{0,60}" +
            "\\b(?:install|import|update|replace|overwrite)\\b|" +
            "\\b(?:did not|didn't|have not|haven't|has not|hasn't|refuse(?:d)? to)\\b.{0,60}" +
            "\\b(?:confirm|replace|overwrite)\\b|" +
            "\\b(?:cancel(?:led)?|withdraw|withdrew)\\b.{0,60}" +
            "\\b(?:confirm|replace|overwrite)\\b|" +
            "\\b(?:avoid|prohibit|forbid|ban)\\b.{0,60}" +
            "\\b(?:install|installing|import|update|replace|overwrite)\\b|" +
            "\\b(?:refuse(?:d)? to|(?:i(?:'m| am) )?not asking|against|oppose(?:d)?)\\b.{0,60}" +
            "\\b(?:install|installing|import|update|replace|overwrite)\\b|" +
            "\\bwithout\\b.{0,40}" +
            "\\b(?:installing|importing|updating|replacing|overwriting)\\b",
    )
    private val HOW_TO_REQUEST = Regex(
        "(?:如何|怎么|怎样).{0,30}(?:安装|导入|更新|替换|覆盖)|" +
            "(?:安装|导入|更新|替换|覆盖).{0,8}(?:方法|步骤|教程)|" +
            "\\bhow (?:do i|can i|to) (?:install|import|update|replace|overwrite)\\b|" +
            "\\bexplain how to (?:install|import|update|replace|overwrite)\\b",
    )
    private val EXTERNAL_DIRECTIVE = Regex(
        "(?:网页|页面|readme|仓库|网站).{0,24}(?:写着|声称|要求|提示|说|建议).{0,24}(?:安装|装|导入|更新|替换|覆盖)|" +
            "\\b(?:webpage|page|readme|repository|repo|website).{0,24}" +
            "(?:says?|claims?|asks?|instructs?).{0,24}(?:install|import|update|replace|overwrite)\\b",
    )
    private val NON_ACTION_CONTENT = Regex(
        "^(?:(?:请|请帮我|帮我)\\s*)?(?:翻译|解释|总结|分析|改写|润色)(?:一下)?" +
            "(?:这句|这句话|以下|下面|文本|内容|指令|提示词)?.{0,12}[：:]|" +
            "^(?:please\\s+)?(?:translate|explain|summarize|analyze|rewrite)\\b.{0,80}" +
            "(?:sentence|text|phrase|instruction|prompt|[：:])",
    )
    private val MUTATION_QUESTION_OR_HEDGE = Regex(
        "(?:为什么|为何|什么时候|何时|哪里|在哪).{0,32}" +
            "(?:安装|装|导入|更新|升级)|" +
            "(?:如果|假如|是否|能否|可以|要不要|万一|可能).{0,32}" +
            "(?:安装|装|导入|更新|升级)|" +
            "(?:安装|装|导入|更新|升级).{0,20}(?:会怎样|怎么样|会如何|吗|[？?])|" +
            "\\b(?:if|whether|can|could|should|would|maybe)\\b.{0,60}" +
            "\\b(?:install|import|update|upgrade)\\b|" +
            "\\b(?:why|when|where|who)\\b.{0,60}" +
            "\\b(?:install|import|update|upgrade)\\b|" +
            "\\b(?:install|import|update|upgrade)\\b.{0,60}[?]",
    )
    private val REPLACE_CONFIRMATION = Regex(
        "确认(?:替换|覆盖)|同意(?:替换|覆盖)|允许(?:替换|覆盖)|" +
            "强制(?:替换|覆盖)|" +
            "\\bconfirm(?:ed)? (?:the )?(?:replace|replacement|overwrite)\\b|" +
            "\\byes[, ]+(?:replace|overwrite)\\b|" +
            "\\bforce (?:replace|overwrite)\\b|" +
            "^\\\$skill-installer\\s+--replace(?:\\s|$)",
    )
    private val REPLACE_QUESTION_OR_HEDGE = Regex(
        "(?:可以|能否|是否|要不要|如果|假如|不确定|犹豫|可能).{0,32}(?:替换|覆盖)|" +
            "(?:替换|覆盖).{0,12}(?:吗|会怎样|怎么样|\\?)|" +
            "\\b(?:can|could|should|would|if|whether|unsure|uncertain|maybe)\\b.{0,60}" +
            "\\b(?:replace|overwrite)\\b",
    )
}
