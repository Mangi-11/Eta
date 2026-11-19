package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.File
import java.nio.file.Files

/**
 * 管理内置与自定义 Subagent Profile。
 *
 * 自定义 Subagent 存放在默认工作区的 `agents/<name>.md`，采用 YAML frontmatter + Markdown 正文格式：
 * ```markdown
 * ---
 * name: bill-analyzer
 * description: 专门检索与汇总个人账单与订单
 * whenToUse: 当需要统计账单、核对订单或分析消费时使用
 * tools: summarize_bills, search_bills, search_personal_orders, read_personal_item
 * ---
 * 你是专门负责账单与消费分析的 Subagent...
 * ```
 */
internal object SubagentProfileCatalog {
    private val NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private const val MAX_PROFILE_FILE_BYTES = 64 * 1024L

    fun defaultAgentsDir(): File = File(TerminalRuntime.userWorkspacePath, "agents")

    fun isValidName(name: String): Boolean =
        name.length in 2..48 && NAME_REGEX.matches(name)

    fun listAll(agentsDir: File = defaultAgentsDir()): List<SubagentProfile> {
        val merged = linkedMapOf<String, SubagentProfile>()
        SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
            merged[profile.name] = profile
        }
        discoverCustomProfiles(agentsDir).forEach { custom ->
            val existing = merged[custom.name]
            if (existing != null && existing.builtin && !custom.override) {
                return@forEach
            }
            merged[custom.name] = custom
        }
        return merged.values.toList()
    }

    fun listCustom(agentsDir: File = defaultAgentsDir()): List<SubagentProfile> =
        discoverCustomProfiles(agentsDir)

    fun existsCustomProfile(name: String, agentsDir: File = defaultAgentsDir()): Boolean {
        val normalizedName = name.trim().lowercase()
        if (!isValidName(normalizedName) || !agentsDir.exists()) return false
        val targetFile = File(agentsDir, "$normalizedName.md")
        return targetFile.isFile && !Files.isSymbolicLink(targetFile.toPath())
    }

    fun get(name: String, agentsDir: File = defaultAgentsDir()): SubagentProfile? {
        val normalized = name.trim().lowercase()
        if (normalized.isEmpty()) return null
        return listAll(agentsDir).firstOrNull { it.name == normalized }
    }

    fun saveCustomProfile(
        name: String,
        description: String,
        prompt: String,
        whenToUse: String = "",
        tools: List<String> = emptyList(),
        disallowedTools: List<String> = emptyList(),
        overrideBuiltin: Boolean = false,
        agentsDir: File = defaultAgentsDir(),
    ): SubagentProfile {
        val normalizedName = name.trim().lowercase()
        require(isValidName(normalizedName)) {
            "Subagent 名称必须为 2~48 位小写字母、数字与连字符（kebab-case，例如 bill-analyzer）"
        }
        if (normalizedName in SubagentProfile.BUILTIN_NAMES && !overrideBuiltin) {
            throw IllegalArgumentException(
                "不能覆盖内置 Subagent \"$normalizedName\"；请换用其他名称（例如 custom-$normalizedName）或显式开启 override",
            )
        }
        val cleanDescription = description.trim().replace(Regex("\\s+"), " ")
        require(cleanDescription.isNotEmpty()) { "description 不能为空" }
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotEmpty()) { "prompt 不能为空" }

        val cleanWhenToUse = whenToUse.trim().replace(Regex("\\s+"), " ")
        val cleanTools = tools.map { it.trim() }
            .filter { it.isNotEmpty() && it !in SubagentProfile.FORBIDDEN_TOOLS }
            .distinct()
        val cleanDisallowed = disallowedTools.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (!agentsDir.exists() && !agentsDir.mkdirs()) {
            error("无法创建 agents 目录：${agentsDir.absolutePath}")
        }
        val canonicalRoot = agentsDir.canonicalFile.toPath()
        val targetFile = File(agentsDir, "$normalizedName.md")
        val canonicalTarget = targetFile.canonicalFile.toPath()
        require(canonicalTarget.startsWith(canonicalRoot) && canonicalTarget != canonicalRoot) {
            "非法的 Subagent 文件路径"
        }
        require(!Files.isSymbolicLink(targetFile.toPath())) {
            "拒绝写入符号链接文件"
        }

        val markdown = renderProfileMarkdown(
            name = normalizedName,
            description = cleanDescription,
            whenToUse = cleanWhenToUse,
            tools = cleanTools,
            disallowedTools = cleanDisallowed,
            overrideBuiltin = overrideBuiltin,
            prompt = cleanPrompt,
        )
        targetFile.writeText(markdown, Charsets.UTF_8)
        return parseProfileFile(targetFile)
            ?: error("保存 Subagent 文件后解析失败：$normalizedName")
    }

    fun deleteCustomProfile(
        name: String,
        agentsDir: File = defaultAgentsDir(),
    ): Boolean {
        val normalizedName = name.trim().lowercase()
        if (!isValidName(normalizedName) || !agentsDir.exists()) return false
        val canonicalRoot = agentsDir.canonicalFile.toPath()
        val targetFile = File(agentsDir, "$normalizedName.md")
        if (!targetFile.exists() || Files.isSymbolicLink(targetFile.toPath())) return false
        val canonicalTarget = targetFile.canonicalFile.toPath()
        if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
        return targetFile.delete()
    }

    private fun discoverCustomProfiles(agentsDir: File): List<SubagentProfile> {
        if (!agentsDir.exists() || !agentsDir.isDirectory) return emptyList()
        if (Files.isSymbolicLink(agentsDir.toPath())) return emptyList()
        val canonicalRoot = runCatching { agentsDir.canonicalFile.toPath() }.getOrNull() ?: return emptyList()
        return agentsDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    file.name.endsWith(".md") &&
                    file.length() in 1..MAX_PROFILE_FILE_BYTES &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false)
            }
            .mapNotNull(::parseProfileFile)
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()
    }

    internal fun parseProfileFile(file: File): SubagentProfile? {
        val parsed = SkillParser.parseSkillFile(file) ?: return null
        val frontmatter = parsed.frontmatter
        val rawName = frontmatter["name"]?.trim()?.lowercase()
            .takeUnless { it.isNullOrBlank() }
            ?: file.nameWithoutExtension.trim().lowercase()
        if (!isValidName(rawName)) return null

        val description = unescapeYamlDoubleQuoted(frontmatter["description"]?.trim().orEmpty())
        if (description.isEmpty()) return null
        val body = parsed.body.trim()
        if (body.isEmpty()) return null

        val whenToUse = (frontmatter["whenToUse"] ?: frontmatter["when_to_use"])?.trim()
            ?.let(::unescapeYamlDoubleQuoted)
            .takeUnless { it.isNullOrBlank() }
            ?: description
        val override = frontmatter["override"]?.trim()?.equals("true", ignoreCase = true) == true
        val rawTools = parseListField(frontmatter["tools"])
        val rawDisallowed = parseListField(frontmatter["disallowedTools"] ?: frontmatter["disallowed_tools"])
        val explicitTools = when {
            rawTools == null -> setOf("*")
            rawTools.isEmpty() -> setOf("*")
            else -> rawTools.filterNot { it in SubagentProfile.FORBIDDEN_TOOLS }.toSet()
        }
        val highlights = when {
            rawTools == null || rawTools.isEmpty() || "*" in rawTools -> listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
            else -> rawTools.filterNot { it in SubagentProfile.FORBIDDEN_TOOLS }.take(8)
        }

        return SubagentProfile(
            name = rawName,
            summary = description,
            whenToUse = whenToUse,
            toolHighlights = highlights,
            roleInstruction = body,
            allowWriteFilesAndTerminal = true,
            builtin = false,
            override = override,
            explicitTools = explicitTools,
            disallowedTools = rawDisallowed.orEmpty().toSet(),
            filePath = file.absolutePath,
        )
    }

    private fun parseListField(raw: String?): List<String>? {
        if (raw == null) return null
        val trimmed = raw.trim().removeSurrounding("[", "]")
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.lines()
            .flatMap { line ->
                val cleaned = line.trim().removePrefix("-").trim()
                cleaned.split(",")
            }
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
    }

    private fun renderProfileMarkdown(
        name: String,
        description: String,
        whenToUse: String,
        tools: List<String>,
        disallowedTools: List<String>,
        overrideBuiltin: Boolean,
        prompt: String,
    ): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: ${quoteYamlIfNeeded(description)}")
        if (whenToUse.isNotEmpty() && whenToUse != description) {
            appendLine("whenToUse: ${quoteYamlIfNeeded(whenToUse)}")
        }
        if (overrideBuiltin) {
            appendLine("override: true")
        }
        if (tools.isNotEmpty()) {
            appendLine("tools: ${tools.joinToString(", ")}")
        }
        if (disallowedTools.isNotEmpty()) {
            appendLine("disallowedTools: ${disallowedTools.joinToString(", ")}")
        }
        appendLine("---")
        appendLine()
        appendLine(prompt)
    }

    private fun quoteYamlIfNeeded(value: String): String {
        val singleLine = value.replace("\n", " ").trim()
        return if (singleLine.any { it in ":[]{},#&*!|>'\"%@`\\" }) {
            "\"${singleLine.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            singleLine
        }
    }

    /** 与 [quoteYamlIfNeeded] 对称反转义，避免引号与反斜杠在编辑保存后逐次翻倍。 */
    private fun unescapeYamlDoubleQuoted(value: String): String {
        if ('\\' !in value) return value
        return buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                if (ch == '\\' && index + 1 < value.length) {
                    val next = value[index + 1]
                    if (next == '"' || next == '\\') {
                        append(next)
                        index += 2
                        continue
                    }
                }
                append(ch)
                index += 1
            }
        }
    }
}
