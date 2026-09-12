package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 同一角色的会话共享剧情记忆；现实 MEMORY.md 始终使用独立存储。 */
internal object CharacterMemoryRepository {
    private val stores = ConcurrentHashMap<String, AgentMemoryStore>()

    fun snapshot(context: Context, characterId: String): AgentMemorySnapshot =
        store(context, characterId).snapshot()

    fun read(
        context: Context,
        characterId: String,
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = 12_000,
    ): AgentMemoryReadResult = store(context, characterId).read(query, startLine, maxChars)

    fun mutate(
        context: Context,
        characterId: String,
        mutation: AgentMemoryMutation,
    ): AgentMemoryWriteResult = store(context, characterId).mutate(mutation)

    fun replaceAll(context: Context, characterId: String, content: String): AgentMemorySnapshot =
        store(context, characterId).replaceAll(content)

    fun replaceAllIfRevision(
        context: Context,
        characterId: String,
        revision: String,
        content: String,
    ): AgentMemoryWriteResult = store(context, characterId).replaceAllIfRevision(content, revision)

    fun discard(context: Context, characterId: String) {
        require(characterId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "角色记忆标识无效" }
        val root = File(context.applicationContext.filesDir, "roleplay/$characterId").canonicalFile
        stores.remove(root.path)
        root.deleteRecursively()
    }

    private fun store(context: Context, characterId: String): AgentMemoryStore {
        require(characterId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "角色记忆标识无效" }
        val root = File(context.applicationContext.filesDir, "roleplay/$characterId").canonicalFile
        return stores.getOrPut(root.path) { AgentMemoryStore(root) }
    }
}
