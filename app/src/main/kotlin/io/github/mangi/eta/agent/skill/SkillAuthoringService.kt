package io.github.mangi.eta.agent.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** 用现有安装事务提交模型编写的技能；不执行脚本，也不接受任意文件路径。 */
internal class SkillAuthoringService(
    private val index: SkillIndexService,
    private val installer: SkillPackageInstaller,
) {
    fun validate(args: JSONObject): JSONObject = manage(args, reviewOnly = true)

    fun manage(args: JSONObject, reviewOnly: Boolean = false, isCancelled: () -> Boolean = { false }): JSONObject =
        index.withMutationLock {
            try {
                require(args.keys().asSequence().all { it in FIELDS }) { "存在不支持的字段" }
                val action = args.getString("action")
                require(action == "create" || action == "update") { "action 必须为 create 或 update" }
                val id = args.getString("skillId")
                require(id.length in 1..64 && ID.matches(id)) { "skillId 需要 1–64 位小写字母、数字及单个连字符" }
                val description = args.getString("description").trim()
                val body = args.getString("bodyMarkdown").trim()
                require(description.isNotBlank() && description.length <= 1000) { "description 不能为空或超过 1000 字符" }
                require(body.isNotBlank() && body.length <= 64_000 && '\u0000' !in body) { "正文不能为空或超过 64000 字符" }
                require(!index.isBuiltinSkillId(id)) { "不能修改内置技能" }
                val existing = index.listSkillsForManagement(forceRefresh = true)
                    .firstOrNull { it.id == id && it.installed }
                if (action == "create" && existing != null) return@withMutationLock failure(
                    "SKILL_EXISTS",
                    "技能已存在，请先读取版本再更新"
                )
                if (action == "update" && existing == null) return@withMutationLock failure(
                    "NOT_FOUND",
                    "未找到需要更新的技能"
                )
                if (existing != null && (existing.source != USER_SKILL_SOURCE || !existing.enabled))
                    return@withMutationLock failure(
                        "SKILL_NOT_WRITABLE",
                        "只能更新已启用的用户技能"
                    )
                val files = linkedMapOf<String, ByteArray>()
                var preservedFrontmatter = "compatibility: Android\n"
                if (existing != null) {
                    val root = File(existing.rootPath)
                    val skillFile = File(root, "SKILL.md")
                    require(skillFile.length() <= 512 * 1024) { "技能正文超过编辑上限" }
                    require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(skillFile.toPath())) { "技能目录不能是符号链接" }
                    val revision = args.getString("expectedRevision")
                    if (revision != skillRevision(skillFile.readBytes()))
                        return@withMutationLock failure(
                            "SKILL_REVISION_CONFLICT",
                            "技能已变化，请重新 skills_read"
                        )
                    preservedFrontmatter = preserveOtherFrontmatter(skillFile.readText())
                    var total = 0L
                    root.walkTopDown().forEach { file ->
                        require(!Files.isSymbolicLink(file.toPath())) { "技能含符号链接，未修改" }
                        require(
                            Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                        ) { "技能包含特殊文件" }
                        if (file.isFile && file != skillFile) {
                            require(file.name != "SKILL.md") { "不能覆盖嵌套的其他技能" }
                            total += file.length()
                            require(file.length() <= MAX_RESOURCE_BYTES && total <= MAX_RESOURCE_BYTES && files.size < 200) { "技能资源超过编辑上限" }
                            files[file.relativeTo(root).invariantSeparatorsPath] = file.readBytes()
                        }
                    }
                } else require(!args.has("expectedRevision")) { "创建技能不需要 expectedRevision" }
                val content = buildString {
                    append("---\nname: ").append(id).append("\ndescription: >-\n")
                    description.lines()
                        .forEach { append("  ").append(it.replace('\u0000', ' ')).append('\n') }
                    append(preservedFrontmatter).append("---\n\n").append(body).append('\n')
                }.toByteArray(Charsets.UTF_8)
                files["SKILL.md"] = content
                if (reviewOnly) return@withMutationLock JSONObject().put("ok", true)
                    .put("skillId", id).put("revision", skillRevision(content)).put("applied", false)
                val archive = ByteArrayOutputStream().also { out ->
                    ZipOutputStream(out).use { zip ->
                        files.forEach { (path, bytes) ->
                            require(!isCancelled()) { "已停止" }
                            zip.putNextEntry(ZipEntry("$id/$path"))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                    }
                }.toByteArray()
                when (val result = installer.installLocalZip(
                    openStream = { archive.inputStream() },
                    replaceUserSkill = existing != null,
                    expectedReplacementId = id.takeIf { existing != null },
                    expectedArchiveSha256 = skillRevision(archive).takeIf { existing != null },
                    isCancelled = isCancelled,
                )) {
                    is SkillInstallResult.Success -> JSONObject().put("ok", true).put("skillId", id)
                        .put("action", action).put("revision", skillRevision(content))
                        .put("availableNextRun", true)

                    is SkillInstallResult.Conflict -> failure("SKILL_EXISTS", "技能目录冲突，未覆盖")
                    is SkillInstallResult.Failure -> failure(
                        result.error.code.name,
                        result.error.message
                    )
                        .put("recoveryRequired", result.recoveryRequired)
                }
            } catch (error: Exception) {
                failure(
                    "SKILL_AUTHORING_FAILED",
                    if (error is IllegalArgumentException) error.message
                        ?: "参数无效" else "技能提交失败，未跳过路径或版本检查"
                )
            }
        }

    private fun failure(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    private fun preserveOtherFrontmatter(raw: String): String {
        val lines = raw.lines()
        if (lines.firstOrNull()?.trimEnd() != "---") return ""
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return ""
        var skip = false
        return buildString {
            for (line in lines.subList(1, end)) {
                if (line.isNotBlank() && !line.first().isWhitespace()) {
                    skip = line.startsWith("name:") || line.startsWith("description:")
                }
                if (!skip) append(line).append('\n')
            }
        }
    }

    companion object {
        private val ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val FIELDS =
            setOf("action", "skillId", "description", "bodyMarkdown", "expectedRevision")
        private const val MAX_RESOURCE_BYTES = 4L * 1024L * 1024L
    }
}

internal fun skillRevision(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
