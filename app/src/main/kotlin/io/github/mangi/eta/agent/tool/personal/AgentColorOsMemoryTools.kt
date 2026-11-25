package io.github.mangi.eta.agent.tool.personal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import io.github.mangi.eta.core.isPlaintextColorOsMemoryDatabase
import java.io.File
import java.io.IOException
import org.json.JSONObject

private const val COLOROS_MEMORY_DATABASE_MAX_BYTES = 64L * 1024 * 1024
private const val COLOROS_MEMORY_SIDECAR_MAX_BYTES = 64L * 1024 * 1024
private val COLOROS_MEMORY_SIDECARS = listOf("-wal", "-shm", "-journal")

internal fun buildColorOsMemorySnapshotCommand(source: String, snapshot: File): String = buildString {
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    append("[ -f ").append(shellQuote(source)).append(" ] || exit 21; ")
    append("[ ! -L ").append(shellQuote(source)).append(" ] || exit 22; ")
    append("[ \"\$(stat -c %s ").append(shellQuote(source)).append(")\" -le ")
        .append(COLOROS_MEMORY_DATABASE_MAX_BYTES).append(" ] || exit 23; ")
    append("cp ").append(shellQuote(source)).append(' ').append(shellQuote(snapshot.absolutePath))
        .append(" || exit 24; ")
    COLOROS_MEMORY_SIDECARS.forEach { suffix ->
        val extraSource = source + suffix
        val extraTarget = snapshot.absolutePath + suffix
        append("if [ -f ").append(shellQuote(extraSource)).append(" ]; then ")
        append("[ ! -L ").append(shellQuote(extraSource)).append(" ] || exit 25; ")
        append("[ \"\$(stat -c %s ").append(shellQuote(extraSource)).append(")\" -le ")
            .append(COLOROS_MEMORY_SIDECAR_MAX_BYTES).append(" ] || exit 26; ")
        append("cp ").append(shellQuote(extraSource)).append(' ').append(shellQuote(extraTarget))
            .append(" || exit 27; else rm -f ").append(shellQuote(extraTarget)).append("; fi; ")
    }
}

/**
 * 优先让小布记忆 Hook 在目标进程内执行只读查询；Hook 不可用时兼容旧的 Root 快照路径。
 */
internal class AgentColorOsMemoryTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) {
    fun search(args: JSONObject): AgentModelClient.ToolResult =
        query(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, args)

    fun searchOrders(args: JSONObject): AgentModelClient.ToolResult =
        query(ColorOsMemoryBridgeProtocol.OPERATION_ORDERS, args)

    fun searchSavedPlaces(args: JSONObject): AgentModelClient.ToolResult =
        query(ColorOsMemoryBridgeProtocol.OPERATION_PLACES, args)

    private fun query(operation: String, args: JSONObject): AgentModelClient.ToolResult {
        queryHook(operation, args)?.let { return sensitive(it) }
        return querySnapshot { database ->
            ColorOsMemoryDatabaseQuery.execute(database, operation, args)
        }
    }

    private fun queryHook(operation: String, args: JSONObject): String? {
        val encodedRequest = runCatching {
            ColorOsMemoryBridgeProtocol.encodeRequest(operation, args)
        }.getOrNull() ?: return null
        val command = runCatching {
            ColorOsMemoryBridgeProtocol.buildRootCommand(encodedRequest)
        }.getOrNull() ?: return null
        val result = root.execute(
            command = command,
            timeoutMillis = HOOK_TIMEOUT_MS,
            maxOutputBytes = HOOK_MAX_OUTPUT_BYTES,
        )
        if (!result.ok || result.truncated) return null
        return ColorOsMemoryBridgeProtocol.decodeShellResponse(result.stdout)
    }

    private fun querySnapshot(
        block: (SQLiteDatabase) -> String,
    ): AgentModelClient.ToolResult = synchronized(snapshotLock) {
        val snapshotCreation = createSnapshot()
        val snapshot = snapshotCreation.file
            ?: return@synchronized sensitive(
                error(snapshotCreation.errorCode, "ColorOS 系统记忆快照暂时不可用"),
            )
        try {
            if (!isPlaintextColorOsMemoryDatabase(snapshot)) {
                return@synchronized sensitive(
                    error(
                        "COLOROS_MEMORY_ENCRYPTED_REQUIRES_HOOK",
                        "系统记忆已加密，需要启用小布记忆的 Xposed 作用域并重新启动小布记忆",
                    ),
                )
            }
            val database = runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            }.getOrElse { throwable ->
                return@synchronized sensitive(
                    error(
                        if (throwable is SQLiteException) {
                            "COLOROS_MEMORY_SNAPSHOT_INVALID"
                        } else {
                            "COLOROS_MEMORY_OPEN_FAILED"
                        },
                        "ColorOS 系统记忆快照无法读取",
                    ),
                )
            }
            database.use { db ->
                runCatching { sensitive(block(db)) }
                    .getOrElse {
                        sensitive(error("COLOROS_MEMORY_QUERY_FAILED", "ColorOS 系统记忆查询失败"))
                    }
            }
        } catch (_: IOException) {
            sensitive(error("COLOROS_MEMORY_SNAPSHOT_INVALID", "ColorOS 系统记忆快照无法读取"))
        } finally {
            deleteSnapshot(snapshot)
        }
    }

    private fun createSnapshot(): SnapshotCreation {
        cleanupStaleSnapshots()
        val snapshot = runCatching {
            File.createTempFile(SNAPSHOT_PREFIX, DATABASE_SUFFIX, context.cacheDir)
        }.getOrNull() ?: return SnapshotCreation.failure("COLOROS_MEMORY_SNAPSHOT_TEMP_UNAVAILABLE")
        val sidecars = listOf(WAL_SUFFIX, SHM_SUFFIX, JOURNAL_SUFFIX).map { suffix ->
            File(snapshot.absolutePath + suffix)
        }
        if (!sidecars.all { file -> runCatching { file.createNewFile() }.getOrDefault(false) }) {
            deleteSnapshot(snapshot)
            return SnapshotCreation.failure("COLOROS_MEMORY_SNAPSHOT_TARGET_UNAVAILABLE")
        }
        val userId = context.dataDir.parentFile?.name?.toIntOrNull() ?: run {
            deleteSnapshot(snapshot)
            return SnapshotCreation.failure("COLOROS_MEMORY_USER_UNAVAILABLE")
        }
        val source =
            "/data/user/$userId/${ColorOsMemoryBridgeProtocol.PACKAGE_NAME}/databases/" +
                ColorOsMemoryBridgeProtocol.DATABASE_NAME
        val result = root.execute(
            buildColorOsMemorySnapshotCommand(source, snapshot),
            timeoutMillis = SNAPSHOT_TIMEOUT_MS,
            maxOutputBytes = 8 * 1024,
        )
        if (result.ok) return SnapshotCreation(snapshot)
        deleteSnapshot(snapshot)
        return SnapshotCreation.failure(snapshotFailureCode(result))
    }

    private fun snapshotFailureCode(result: BoundedRootCommandExecutor.Result): String = when {
        result.errorCode == "ROOT_UNAVAILABLE" -> "COLOROS_MEMORY_ROOT_UNAVAILABLE"
        result.timedOut -> "COLOROS_MEMORY_SNAPSHOT_TIMEOUT"
        result.exitCode == 21 -> "COLOROS_MEMORY_DATABASE_MISSING"
        result.exitCode == 22 -> "COLOROS_MEMORY_DATABASE_SYMLINK"
        result.exitCode == 23 -> "COLOROS_MEMORY_DATABASE_TOO_LARGE"
        result.exitCode == 24 -> "COLOROS_MEMORY_DATABASE_COPY_FAILED"
        result.exitCode == 25 -> "COLOROS_MEMORY_SIDECAR_SYMLINK"
        result.exitCode == 26 -> "COLOROS_MEMORY_SIDECAR_TOO_LARGE"
        result.exitCode == 27 -> "COLOROS_MEMORY_SIDECAR_COPY_FAILED"
        else -> "COLOROS_MEMORY_UNAVAILABLE"
    }

    private fun cleanupStaleSnapshots() {
        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(SNAPSHOT_PREFIX) }
            ?.forEach(File::delete)
    }

    private fun deleteSnapshot(snapshot: File) {
        listOf(
            snapshot,
            File(snapshot.absolutePath + WAL_SUFFIX),
            File(snapshot.absolutePath + SHM_SUFFIX),
            File(snapshot.absolutePath + JOURNAL_SUFFIX),
        ).forEach(File::delete)
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private data class SnapshotCreation(
        val file: File?,
        val errorCode: String = "",
    ) {
        companion object {
            fun failure(errorCode: String) = SnapshotCreation(null, errorCode)
        }
    }

    private companion object {
        const val SNAPSHOT_PREFIX = "eta-coloros-memory-"
        const val DATABASE_SUFFIX = ".db"
        const val WAL_SUFFIX = "-wal"
        const val SHM_SUFFIX = "-shm"
        const val JOURNAL_SUFFIX = "-journal"
        const val SNAPSHOT_TIMEOUT_MS = 15_000L
        const val HOOK_TIMEOUT_MS = 15_000L
        const val HOOK_MAX_OUTPUT_BYTES = 512 * 1024

        val snapshotLock = Any()
    }
}
