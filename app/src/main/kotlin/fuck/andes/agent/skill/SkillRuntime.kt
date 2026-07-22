package fuck.andes.agent.skill

import android.content.Context
import android.content.res.AssetManager
import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.data.db.SkillRegistryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

private const val BUILTIN_SKILL_MANIFEST_ASSET = "builtin_skills/manifest.json"
internal const val BUILTIN_SKILL_SOURCE = "builtin"
internal const val USER_SKILL_SOURCE = "user"
private const val BUILTIN_SOURCE = BUILTIN_SKILL_SOURCE
private const val USER_SOURCE = USER_SKILL_SOURCE
private const val INSTALL_STATE_INSTALLED = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN = "removed_builtin"

internal fun isSafeBuiltinSkillInstallation(targetDir: File): Boolean {
    val skillFile = File(targetDir, "SKILL.md")
    return !Files.isSymbolicLink(targetDir.toPath()) &&
        !Files.isSymbolicLink(skillFile.toPath()) &&
        skillFile.isFile
}

/** 内置技能 manifest 条目。 */
private data class BuiltinSkillAsset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val assetPath: String = "",
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasEvals: Boolean = false,
)

/** 注册表中的技能状态记录。 */
private data class SkillRegistryEntry(
    val enabled: Boolean = true,
    val source: String = USER_SOURCE,
    val installState: String = INSTALL_STATE_INSTALLED,
)

// =====================================================================================
// SkillRegistryStore — 持久化技能安装元数据；技能正文仍保留在文件树中。
// =====================================================================================

private class SkillRegistryStore(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun read(): LinkedHashMap<String, SkillRegistryEntry> {
        return runCatching { readStrict() }.getOrElse { linkedMapOf() }
    }

    fun readStrict(): LinkedHashMap<String, SkillRegistryEntry> = runBlocking(Dispatchers.IO) {
        FuckAndesDatabase.get(appContext)
            .skillDao()
            .registryEntries()
            .associateTo(linkedMapOf()) { entity ->
                entity.skillId to SkillRegistryEntry(
                    enabled = entity.enabled,
                    source = entity.source.ifBlank { USER_SOURCE },
                    installState = entity.installState.ifBlank { INSTALL_STATE_INSTALLED },
                )
            }
    }

    fun write(entries: Map<String, SkillRegistryEntry>) {
        runBlocking(Dispatchers.IO) {
            FuckAndesDatabase.get(appContext)
                .skillDao()
                .replaceRegistry(
                    entries.toSortedMap().map { (skillId, value) ->
                        SkillRegistryEntity(
                            skillId = skillId,
                            enabled = value.enabled,
                            source = value.source,
                            installState = value.installState,
                        )
                    }
                )
        }
    }

    fun set(skillId: String, entry: SkillRegistryEntry) {
        runBlocking(Dispatchers.IO) {
            FuckAndesDatabase.get(appContext)
                .skillDao()
                .upsertRegistryEntry(
                    SkillRegistryEntity(
                        skillId = skillId,
                        enabled = entry.enabled,
                        source = entry.source,
                        installState = entry.installState,
                    )
                )
        }
    }

    fun remove(skillId: String) {
        runBlocking(Dispatchers.IO) {
            FuckAndesDatabase.get(appContext)
                .skillDao()
                .deleteRegistryEntry(skillId)
        }
    }
}

// =====================================================================================
// BuiltinSkillAssetStore — 从 assets 读取并安装内置技能
// =====================================================================================

private class BuiltinSkillAssetStore(
    private val context: Context,
    private val skillsRoot: File,
) {
    private val builtins: List<BuiltinSkillAsset> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open(BUILTIN_SKILL_MANIFEST_ASSET).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val arr = json.optJSONArray("skills") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    BuiltinSkillAsset(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        description = obj.optString("description"),
                        assetPath = obj.optString("assetPath"),
                        hasScripts = obj.optBoolean("hasScripts"),
                        hasReferences = obj.optBoolean("hasReferences"),
                        hasAssets = obj.optBoolean("hasAssets"),
                        hasEvals = obj.optBoolean("hasEvals"),
                    )
                }.filter { it.id.isNotBlank() && it.assetPath.isNotBlank() }
            }
        }.getOrElse { emptyList() }
    }

    fun listBuiltins(): List<BuiltinSkillAsset> = builtins

    fun findBuiltin(skillId: String): BuiltinSkillAsset? =
        listBuiltins().firstOrNull { it.id == skillId }

    fun seedMissingBuiltins(registryStore: SkillRegistryStore) {
        val registry = registryStore.read()
        var changed = false
        listBuiltins().forEach { builtin ->
            val entry = registry[builtin.id]
            if (entry?.installState == INSTALL_STATE_REMOVED_BUILTIN) return@forEach
            if (entry?.source == USER_SOURCE) return@forEach
            val targetDir = File(skillsRoot, builtin.id)
            if (!isSafeBuiltinSkillInstallation(targetDir)) {
                installBuiltinInternal(builtin)
            }
            if (entry?.source == BUILTIN_SOURCE && entry.installState == INSTALL_STATE_INSTALLED) {
                return@forEach
            }
            registry[builtin.id] = SkillRegistryEntry(
                enabled = true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED,
            )
            changed = true
        }
        if (changed) registryStore.write(registry)
    }

    fun installBuiltin(skillId: String, registryStore: SkillRegistryStore) {
        val builtin = findBuiltin(skillId)
            ?: throw IllegalArgumentException("未找到内置 skill：$skillId")
        installBuiltinInternal(builtin)
        registryStore.set(
            skillId,
            SkillRegistryEntry(enabled = true, source = BUILTIN_SOURCE, installState = INSTALL_STATE_INSTALLED),
        )
    }

    private fun installBuiltinInternal(builtin: BuiltinSkillAsset) {
        val targetDir = File(skillsRoot, builtin.id)
        if (Files.exists(targetDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, targetDir)) {
                error("无法安全清理内置 Skill 目录：${builtin.id}")
            }
        }
        copyAssetRecursively(context.assets, builtin.assetPath, targetDir)
    }

    private fun copyAssetRecursively(assetManager: AssetManager, assetPath: String, target: File) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        if (!target.exists()) target.mkdirs()
        children.forEach { child ->
            copyAssetRecursively(assetManager, "$assetPath/$child", File(target, child))
        }
    }
}

// =====================================================================================
// SkillIndexService — 扫描、索引、管理技能
// =====================================================================================

class SkillIndexService(
    private val context: Context,
    private val skillsRoot: File,
) {
    private val indexLock = Any()
    private val registryStore = SkillRegistryStore(context.applicationContext)
    private val builtinStore = BuiltinSkillAssetStore(context.applicationContext, skillsRoot)

    @Volatile
    private var builtinsSeeded = false

    @Volatile
    private var cachedManagementEntries: List<SkillIndexEntry>? = null

    /**
     * 所有可读写索引的入口都先在同一把跨进程锁内完成待处理文件事务与 Room 快照恢复。
     * 仅能读取文件、不具备 registry 恢复能力的 Loader 会由锁实现保持 fail-closed。
     */
    internal fun <T> withMutationLock(block: () -> T): T = SkillMutationLock.withLock(
        skillsRoot = skillsRoot,
        recoveryHandler = ::restoreRecoveredRegistry,
        block = block,
    )

    fun seedBuiltinSkillsIfNeeded() {
        withMutationLock {
            if (builtinsSeeded) return@withMutationLock
            synchronized(indexLock) {
                seedBuiltinSkillsLocked()
            }
        }
    }

    fun listSkillsForManagement(forceRefresh: Boolean = false): List<SkillIndexEntry> =
        withMutationLock {
            synchronized(indexLock) {
                if (forceRefresh) builtinsSeeded = false
                seedBuiltinSkillsLocked()
                if (forceRefresh) cachedManagementEntries = null
                cachedManagementEntries ?: buildManagementEntries().also {
                    cachedManagementEntries = it
                }
            }
        }

    fun listInstalledSkills(): List<SkillIndexEntry> =
        listSkillsForManagement().filter { it.installed && it.enabled }

    fun findInstalledSkill(identifier: String): SkillIndexEntry? {
        val normalized = SkillParser.normalizeSkillLookup(identifier)
        if (normalized.isBlank()) return null
        val entries = listSkillsForManagement().filter { it.installed && it.enabled }
        return entries.firstOrNull { SkillParser.normalizeSkillLookup(it.id) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.name) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.skillFilePath) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.rootPath) == normalized }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean): SkillIndexEntry {
        return withMutationLock {
            synchronized(indexLock) {
                val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                    ?: throw IllegalArgumentException("未找到已安装 skill：$skillId")
                registryStore.set(
                    entry.id,
                    SkillRegistryEntry(enabled = enabled, source = entry.source, installState = INSTALL_STATE_INSTALLED),
                )
                invalidateIndexLocked()
                entry.copy(enabled = enabled)
            }
        }
    }

    fun deleteSkill(skillId: String): Boolean {
        return withMutationLock {
            synchronized(indexLock) {
                val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                    ?: return@synchronized false
                val targetDir = managedSkillDirectory(entry) ?: return@synchronized false
                val registrySnapshot = captureRegistryRecoverySnapshots(listOf(entry.id)).single()
                val operation = runCatching {
                    createSkillRecoveryOperationDirectory(skillsRoot)
                }.getOrElse { return@synchronized false }
                val workRoot = skillInstallerWorkRoot(skillsRoot)
                val backupRoot = File(operation, "backup")
                if (!backupRoot.mkdir()) {
                    deleteSkillPathWithoutFollowingLinks(workRoot, operation)
                    return@synchronized false
                }
                val backupDir = File(backupRoot, entry.id)
                var registryMutationStarted = false
                try {
                    val journal = PendingSkillRecoveryJournal.begin(
                        skillsRoot = skillsRoot,
                        operationDirectory = operation,
                        records = listOf(
                            SkillRecoveryRecord(
                                id = entry.id,
                                originalTargetExisted = true,
                                registrySnapshot = registrySnapshot,
                            )
                        ),
                    )
                    moveSkillDirectoryAtomically(targetDir, backupDir)
                    journal.markBackupCompleted(entry.id)
                    registryMutationStarted = true
                    if (entry.source == BUILTIN_SOURCE) {
                        registryStore.set(
                            entry.id,
                            SkillRegistryEntry(
                                enabled = false,
                                source = BUILTIN_SOURCE,
                                installState = INSTALL_STATE_REMOVED_BUILTIN,
                            ),
                        )
                    } else {
                        registryStore.remove(entry.id)
                    }
                    invalidateIndexLocked()
                    journal.clear()
                    true
                } catch (error: Exception) {
                    val journalExists = Files.exists(
                        File(operation, JOURNAL_FILE_NAME).toPath(),
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    val rollbackComplete = !journalExists || runCatching {
                        val recovered = recoverPendingSkillOperations(skillsRoot)
                        restoreRecoveredRegistry(recovered)
                        completeRecoveredSkillOperations(skillsRoot, recovered)
                    }.isSuccess
                    if (!rollbackComplete) {
                        throw SkillRecoveryRequiredException(
                            "Skill 删除失败，且文件或 registry 尚未完整恢复",
                            error,
                        )
                    }
                    invalidateIndexLocked()
                    if (registryMutationStarted) throw error
                    false
                } finally {
                    if (!Files.exists(
                            File(operation, JOURNAL_FILE_NAME).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        )
                    ) {
                        // 成功删除时备份内可能含历史 symlink，必须只 unlink，不能递归跟随。
                        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
                    }
                }
            }
        }
    }

    fun installBuiltinSkill(skillId: String): SkillIndexEntry {
        return withMutationLock {
            synchronized(indexLock) {
                builtinStore.installBuiltin(skillId, registryStore)
                invalidateIndexLocked()
                findInstalledSkill(skillId)
                    ?: throw IllegalStateException("安装内置 skill 后索引失败：$skillId")
            }
        }
    }

    /** 文件提交成功后，以单次 Room 事务登记用户 Skill，并同步清除索引缓存。 */
    internal fun registerInstalledUserSkills(skillIds: List<String>) {
        withMutationLock {
            synchronized(indexLock) {
                require(skillIds.none { builtinStore.findBuiltin(it) != null }) {
                    "不能把内置 Skill 登记为用户 Skill"
                }
                val registry = registryStore.readStrict()
                skillIds.distinct().forEach { skillId ->
                    registry[skillId] = SkillRegistryEntry(
                        enabled = true,
                        source = USER_SOURCE,
                        installState = INSTALL_STATE_INSTALLED,
                    )
                }
                registryStore.write(registry)
                invalidateIndexLocked()
            }
        }
    }

    /** 在正式目录发生任何移动前，持久化事务涉及 id 的完整旧 registry 状态。 */
    internal fun captureRegistryRecoverySnapshots(
        skillIds: List<String>,
    ): List<SkillRegistryRecoverySnapshot> = synchronized(indexLock) {
        val registry = registryStore.readStrict()
        skillIds.distinct().map { skillId ->
            val entry = registry[skillId]
            if (entry == null) {
                SkillRegistryRecoverySnapshot(skillId = skillId, entryExisted = false)
            } else {
                SkillRegistryRecoverySnapshot(
                    skillId = skillId,
                    entryExisted = true,
                    enabled = entry.enabled,
                    source = entry.source,
                    installState = entry.installState,
                )
            }
        }
    }

    /** 文件恢复完成后，以单次 Room 事务还原全部旧快照；失败时调用方保留 journal。 */
    internal fun restoreRecoveredRegistry(recovered: List<RecoveredSkillOperation>) {
        if (recovered.isEmpty()) return
        synchronized(indexLock) {
            val registry = registryStore.readStrict()
            recovered.flatMap { it.records }.forEach { record ->
                val snapshot = record.registrySnapshot
                if (snapshot.entryExisted) {
                    registry[snapshot.skillId] = SkillRegistryEntry(
                        enabled = snapshot.enabled,
                        source = snapshot.source,
                        installState = snapshot.installState,
                    )
                } else {
                    registry.remove(snapshot.skillId)
                }
            }
            registryStore.write(registry)
            invalidateIndexLocked()
        }
    }

    internal fun isBuiltinSkillId(skillId: String): Boolean =
        synchronized(indexLock) { builtinStore.findBuiltin(skillId) != null }

    private fun seedBuiltinSkillsLocked() {
        if (builtinsSeeded) return
        if (!skillsRoot.exists() && !skillsRoot.mkdirs()) {
            error("无法创建 Skills 目录：${skillsRoot.absolutePath}")
        }
        builtinStore.seedMissingBuiltins(registryStore)
        builtinsSeeded = true
        invalidateIndexLocked()
    }

    private fun buildManagementEntries(): List<SkillIndexEntry> {
        val registry = registryStore.read()
        val builtinAssets = builtinStore.listBuiltins().associateBy { it.id }
        val installed = scanInstalledEntries(registry, builtinAssets)
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        val removedBuiltins = builtinAssets.values
            .asSequence()
            .filter { it.id !in installedIds && registry[it.id]?.installState == INSTALL_STATE_REMOVED_BUILTIN }
            .map { buildBuiltinPlaceholder(it, registry[it.id]) }
            .toList()
        return (installed + removedBuiltins).sortedWith(
            compareByDescending<SkillIndexEntry> { it.installed }
                .thenBy { sourceRank(it.source) }
                .thenBy { it.name.lowercase() },
        )
    }

    private fun invalidateIndexLocked() {
        cachedManagementEntries = null
    }

    private fun scanInstalledEntries(
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
    ): List<SkillIndexEntry> {
        if (!skillsRoot.exists()) return emptyList()
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        return skillsRoot.walkTopDown()
            .onEnter { dir ->
                dir.name != ".git" &&
                    !Files.isSymbolicLink(dir.toPath()) &&
                    runCatching { dir.canonicalFile.toPath().startsWith(canonicalRoot) }.getOrDefault(false)
            }
            .filter {
                it.isFile && it.name == "SKILL.md" && !Files.isSymbolicLink(it.toPath())
            }
            .mapNotNull { skillFile ->
                buildInstalledEntry(skillFile.parentFile ?: return@mapNotNull null, registry, builtinAssets)
            }
            .distinctBy { it.rootPath }
            .toList()
    }

    private fun buildInstalledEntry(
        skillDir: File,
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
    ): SkillIndexEntry? {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val canonicalDir = skillDir.canonicalFile
        val canonicalPath = canonicalDir.toPath()
        if (!canonicalPath.startsWith(canonicalRoot) || canonicalPath == canonicalRoot) return null
        val skillFile = File(canonicalDir, "SKILL.md")
        if (Files.isSymbolicLink(skillFile.toPath())) return null
        val parsed = SkillParser.parseSkillFile(skillFile) ?: return null
        val frontmatter = parsed.frontmatter
        val id = SkillParser.sanitizeSkillId(canonicalDir.name, frontmatter["name"])
        val metadata = frontmatter["metadata"]?.let { SkillParser.parseIndentedBlock(it) } ?: emptyMap()
        val registryState = registry[id]
        val builtinAsset = builtinAssets[id]
        return SkillIndexEntry(
            id = id,
            name = frontmatter["name"]?.ifBlank { id } ?: id,
            description = frontmatter["description"]?.trim().orEmpty(),
            compatibility = frontmatter["compatibility"]?.trim(),
            metadata = metadata,
            rootPath = canonicalDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = File(canonicalDir, "scripts").isDirectory,
            hasReferences = File(canonicalDir, "references").isDirectory,
            hasAssets = File(canonicalDir, "assets").isDirectory,
            hasEvals = File(canonicalDir, "evals").isDirectory,
            enabled = registryState?.enabled ?: true,
            source = registryState?.source?.ifBlank { null }
                ?: if (builtinAsset != null) BUILTIN_SOURCE else USER_SOURCE,
            installed = true,
        )
    }

    private fun buildBuiltinPlaceholder(
        builtin: BuiltinSkillAsset,
        registryState: SkillRegistryEntry?,
    ): SkillIndexEntry {
        val targetDir = File(skillsRoot, builtin.id)
        val skillFile = File(targetDir, "SKILL.md")
        return SkillIndexEntry(
            id = builtin.id,
            name = builtin.name.ifBlank { builtin.id },
            description = builtin.description,
            rootPath = targetDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = builtin.hasScripts,
            hasReferences = builtin.hasReferences,
            hasAssets = builtin.hasAssets,
            hasEvals = builtin.hasEvals,
            enabled = registryState?.enabled ?: false,
            source = BUILTIN_SOURCE,
            installed = false,
        )
    }

    private fun sourceRank(source: String): Int = when (source) {
        BUILTIN_SOURCE -> 0
        else -> 2
    }

    private fun managedSkillDirectory(entry: SkillIndexEntry): File? {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val requested = File(entry.rootPath)
        if (Files.isSymbolicLink(requested.toPath())) return null
        val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
        val candidatePath = canonical.toPath()
        return canonical.takeIf {
            candidatePath.startsWith(canonicalRoot) && candidatePath != canonicalRoot && it.isDirectory
        }
    }
}

// =====================================================================================
// SkillLoader — 加载技能正文和附属资源
// =====================================================================================

class SkillLoader(private val skillsRoot: File) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val resourceReader = SkillResourceReader(skillsRoot)

    fun load(entry: SkillIndexEntry, triggerReason: String): ResolvedSkillContext? =
        SkillMutationLock.withLock(skillsRoot) {
            loadAfterRecovery(entry, triggerReason)
        }

    private fun loadAfterRecovery(
        entry: SkillIndexEntry,
        triggerReason: String,
    ): ResolvedSkillContext? {
        if (!entry.installed) return null
        val requestedRoot = File(entry.rootPath)
        if (Files.isSymbolicLink(requestedRoot.toPath())) return null
        val skillDir = runCatching { requestedRoot.canonicalFile }.getOrNull() ?: return null
        val rootPath = canonicalSkillsRoot.toPath()
        val skillPath = skillDir.toPath()
        if (!skillPath.startsWith(rootPath) || skillPath == rootPath) return null
        val skillFile = File(skillDir, "SKILL.md")
        if (Files.isSymbolicLink(skillFile.toPath())) return null
        val parsed = SkillParser.parseSkillFile(skillFile) ?: return null
        val loadedReferences = when (val resources = resourceReader.listResources(entry, "references")) {
            is SkillResourceListResult.Success -> resources.resources.map { it.relativePath }
            is SkillResourceListResult.Failure -> emptyList()
        }
        return ResolvedSkillContext(
            skillId = entry.id,
            frontmatter = parsed.frontmatter,
            metadata = entry.metadata,
            bodyMarkdown = parsed.body,
            loadedReferences = loadedReferences,
            scriptsDir = File(skillDir, "scripts").takeIf { it.isDirectory }?.absolutePath,
            assetsDir = File(skillDir, "assets").takeIf { it.isDirectory }?.absolutePath,
            triggerReason = triggerReason,
        )
    }
}

// =====================================================================================
// SkillCompatibilityChecker — 兼容性检查
// =====================================================================================

object SkillCompatibilityChecker {
    fun evaluate(entry: SkillIndexEntry): SkillCompatibilityResult {
        val raw = buildString {
            append(entry.compatibility.orEmpty())
            if (entry.metadata.isNotEmpty()) {
                append(' ')
                append(entry.metadata.values.joinToString(" "))
            }
            append(' ')
            append(entry.description)
        }.lowercase()
        return when {
            raw.contains("apple-") || raw.contains("homekit") || raw.contains("healthkit") ->
                SkillCompatibilityResult(available = false, reason = "不支持 Apple 专属运行时")
            raw.contains("ios") && !raw.contains("android") ->
                SkillCompatibilityResult(available = false, reason = "该 Skill 标注为 iOS 专属")
            else -> SkillCompatibilityResult(available = true)
        }
    }
}

// =====================================================================================
// SkillRuntime — 工厂入口
// =====================================================================================

object SkillRuntime {
    @Volatile
    private var sharedIndexService: SkillIndexService? = null

    fun skillsRoot(context: Context): File = File(context.filesDir, "skills")

    fun createIndexService(context: Context): SkillIndexService {
        sharedIndexService?.let { return it }
        return synchronized(this) {
            sharedIndexService ?: SkillIndexService(
                context = context.applicationContext,
                skillsRoot = skillsRoot(context),
            ).also { sharedIndexService = it }
        }
    }

    fun createLoader(context: Context): SkillLoader =
        SkillLoader(skillsRoot(context))

    fun createPackageInstaller(context: Context): SkillPackageInstaller =
        SkillPackageInstaller(
            skillsRoot = skillsRoot(context),
            indexService = createIndexService(context),
        )

    fun createResourceReader(context: Context): SkillResourceReader =
        SkillResourceReader(skillsRoot(context))
}
