package io.github.mangi.eta.agent.terminal.linux

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 一条共享文件夹配置：Android 侧目录 [sourcePath] 在 Linux 环境中出现在 /workspace/mounts/[name]。 */
internal data class SharedFolderMount(
    val name: String,
    val sourcePath: String,
)

/**
 * 共享文件夹配置的持久化与校验。
 *
 * 挂载不在 Android 全局执行：每个 Linux 会话在自己的 mount namespace 建立时按当前配置做 bind mount，
 * 因此配置改动对下一条命令/下一个会话生效，不留需要卸载或重启后清理的全局状态。
 * 配置存于 localAgent SharedPreferences（JSON），执行路径同步读取。
 */
internal object SharedFolderMounts {
    const val PREFS_KEY = "linux_shared_mounts"
    const val MAX_MOUNTS = 16
    const val LINUX_MOUNTS_ROOT = "/workspace/mounts"
    const val ANDROID_MOUNTS_ROOT = "/data/local/tmp/eta/mounts"

    private val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,48}")

    /** 挂载源禁区：系统关键树、workspace 自身（环境里本就完整可见）与 /。 */
    private val FORBIDDEN_ROOTS = listOf(
        "/",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/apex",
        "/product",
        "/data/local/tmp/eta",
    )

    enum class SourceError {
        INVALID_PATH,
        FORBIDDEN_ROOT,
        DUPLICATE,
    }

    enum class NameError {
        INVALID,
        DUPLICATE,
    }

    fun current(preferences: SharedPreferences? = Prefs.localAgentPreferences()): List<SharedFolderMount> {
        val json = runCatching { preferences?.getString(PREFS_KEY, null) }.getOrNull()
        return decode(json.orEmpty())
    }

    fun save(
        mounts: List<SharedFolderMount>,
        preferences: SharedPreferences? = Prefs.localAgentPreferences(),
    ): Boolean {
        val prefs = preferences ?: return false
        return runCatching { prefs.edit().putString(PREFS_KEY, encode(mounts)).commit() }.getOrDefault(false)
    }

    /** 词法归一化：不解析符号链接，保留用户选择时的路径形态（/sdcard 不被改写）。 */
    fun normalizeSourcePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        if (segments.isEmpty()) return null
        return "/" + segments.joinToString("/")
    }

    fun validateSource(
        sourcePath: String,
        existing: List<SharedFolderMount>,
        extraForbiddenRoots: List<String> = emptyList(),
    ): SourceError? {
        val normalized = normalizeSourcePath(sourcePath) ?: return SourceError.INVALID_PATH
        val forbidden = FORBIDDEN_ROOTS + extraForbiddenRoots.mapNotNull(::normalizeSourcePath)
        if (forbidden.any { normalized == it || normalized.startsWith("$it/") }) {
            return SourceError.FORBIDDEN_ROOT
        }
        if (existing.any { it.sourcePath == normalized }) return SourceError.DUPLICATE
        return null
    }

    fun validateName(name: String, existing: List<SharedFolderMount>): NameError? {
        val trimmed = name.trim()
        if (!NAME_PATTERN.matches(trimmed) || trimmed == "." || trimmed == "..") return NameError.INVALID
        if (existing.any { it.name == trimmed }) return NameError.DUPLICATE
        return null
    }

    /** 从源目录 basename 推导默认挂载名；非 ASCII 字符剔除后为空时退化为 share。 */
    fun defaultName(sourcePath: String): String {
        val base = sourcePath.trimEnd('/').substringAfterLast('/').trim()
        val candidate = base.filter { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '.' || char == '_' || char == '-'
        }.take(48)
        return candidate.ifBlank { "share" }
    }

    fun encode(mounts: List<SharedFolderMount>): String {
        val array = JSONArray()
        mounts.forEach { mount ->
            array.put(
                JSONObject()
                    .put("name", mount.name)
                    .put("source", mount.sourcePath)
            )
        }
        return array.toString()
    }

    /** 解码失败或条目不合法时跳过该条目；整体损坏返回空列表。 */
    fun decode(json: String): List<SharedFolderMount> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val mounts = mutableListOf<SharedFolderMount>()
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val name = item.optString("name").trim()
            val source = normalizeSourcePath(item.optString("source")) ?: continue
            if (!NAME_PATTERN.matches(name) || name == "." || name == "..") continue
            if (mounts.any { it.name == name || it.sourcePath == source }) continue
            mounts += SharedFolderMount(name = name, sourcePath = source)
        }
        return mounts.take(MAX_MOUNTS)
    }
}
