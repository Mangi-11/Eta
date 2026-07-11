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

private const val BUILTIN_SKILL_MANIFEST_ASSET = "builtin_skills/manifest.json"
private const val BUILTIN_SOURCE = "builtin"
private const val USER_SOURCE = "user"
private const val INSTALL_STATE_INSTALLED = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN = "removed_builtin"

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
        return runCatching {
            runBlocking(Dispatchers.IO) {
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
        }.getOrElse { linkedMapOf() }
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
            val targetSkillFile = File(File(skillsRoot, builtin.id), "SKILL.md")
            if (!targetSkillFile.isFile) {
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
        if (targetDir.exists()) targetDir.deleteRecursively()
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

    fun seedBuiltinSkillsIfNeeded() {
        if (builtinsSeeded) return
        synchronized(indexLock) {
            seedBuiltinSkillsLocked()
        }
    }

    fun listSkillsForManagement(forceRefresh: Boolean = false): List<SkillIndexEntry> =
        synchronized(indexLock) {
            if (forceRefresh) builtinsSeeded = false
            seedBuiltinSkillsLocked()
            if (forceRefresh) cachedManagementEntries = null
            cachedManagementEntries ?: buildManagementEntries().also {
                cachedManagementEntries = it
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
        synchronized(indexLock) {
            val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
                ?: throw IllegalArgumentException("未找到已安装 skill：$skillId")
            registryStore.set(
                entry.id,
                SkillRegistryEntry(enabled = enabled, source = entry.source, installState = INSTALL_STATE_INSTALLED),
            )
            invalidateIndexLocked()
            return entry.copy(enabled = enabled)
        }
    }

    fun deleteSkill(skillId: String): Boolean {
        synchronized(indexLock) {
            val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed } ?: return false
            val targetDir = File(entry.rootPath)
            if (entry.source == BUILTIN_SOURCE) {
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                    if (targetDir.exists()) return false
                }
                registryStore.set(
                    entry.id,
                    SkillRegistryEntry(
                        enabled = false,
                        source = BUILTIN_SOURCE,
                        installState = INSTALL_STATE_REMOVED_BUILTIN,
                    ),
                )
                invalidateIndexLocked()
                return true
            }
            val deleted = !targetDir.exists() || targetDir.deleteRecursively()
            registryStore.remove(entry.id)
            invalidateIndexLocked()
            return deleted
        }
    }

    fun installBuiltinSkill(skillId: String): SkillIndexEntry {
        synchronized(indexLock) {
            builtinStore.installBuiltin(skillId, registryStore)
            invalidateIndexLocked()
            return findInstalledSkill(skillId)
                ?: throw IllegalStateException("安装内置 skill 后索引失败：$skillId")
        }
    }

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
        return skillsRoot.walkTopDown()
            .onEnter { dir -> dir.name != ".git" }
            .filter { it.isFile && it.name == "SKILL.md" }
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
        val canonicalDir = skillDir.canonicalFile
        val skillFile = File(canonicalDir, "SKILL.md")
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
}

// =====================================================================================
// SkillLoader — 加载技能正文和附属资源
// =====================================================================================

class SkillLoader(private val skillsRoot: File) {

    fun load(entry: SkillIndexEntry, triggerReason: String): ResolvedSkillContext? {
        if (!entry.installed) return null
        val skillDir = File(entry.rootPath)
        val parsed = SkillParser.parseSkillFile(File(skillDir, "SKILL.md")) ?: return null
        val referencesDir = File(skillDir, "references")
        val loadedReferences = if (referencesDir.isDirectory) {
            referencesDir.listFiles()
                ?.filter { it.isFile }
                ?.map { it.absolutePath }
                ?.sorted()
                ?: emptyList()
        } else {
            emptyList()
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
}
