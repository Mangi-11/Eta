package io.github.mangi.eta.ui.state

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.files.FileToolBackend
import io.github.mangi.eta.agent.terminal.files.FileToolKind
import io.github.mangi.eta.agent.terminal.files.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.files.ShellFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.terminal.runOneShotShell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

internal class WorkspaceFileStore(
    context: Context,
    private val backendFor: (File) -> FileToolBackend = ::workspaceBackend,
) {
    private val appContext = context.applicationContext
    private val root: File get() = TerminalPrivateStorage.workspace(appContext.filesDir).canonicalFile

    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        if (!root.mkdirs() && !root.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
        val backend = backendFor(root)
        val directory = resolve(backend, path)
        val entries = mutableListOf<WorkspaceEntry>()
        var offset = 0
        var revision: String? = null
        do {
            ensureActive()
            val page = backend.list(directory, offset, 200, revision)
            revision = page.revision
            page.entries.forEach { entry ->
                val target = if (entry.kind == FileToolKind.SYMLINK) backend.stat(backend.resolve(entry.path)) else entry
                if (File(target.path).toPath().startsWith(root.toPath()) && target.kind in setOf(FileToolKind.FILE, FileToolKind.DIRECTORY)) {
                    entries += WorkspaceEntry(File(entry.path).relativeTo(root).path, entry.name,
                        target.kind == FileToolKind.DIRECTORY, target.sizeBytes)
                }
            }
            if (!page.hasMore) break
            check(page.nextOffset > offset) { "WORKSPACE_INVALID_PAGE" }
            offset = page.nextOffset
        } while (true)
        entries.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        AgentFileReferenceGateway.importDocumentUri(appContext, uri) is AgentFileReferenceGateway.Resolution.Success
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        output.use {
            val file = File(source)
            if (file.canRead()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } else {
                var offset = 0L
                while (offset < stat.sizeBytes) {
                    ensureActive()
                    val bytes = backend.readBytes(source, offset, 16 * 1024)
                    check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                    output.write(bytes)
                    offset += bytes.size
                }
            }
        }
    }

    private fun resolve(backend: FileToolBackend, path: String): String = backend.resolve(path).also {
        require(File(it).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
    }
}

private fun workspaceBackend(root: File): FileToolBackend {
    if (!RootAccess.isGranted) return LocalFileToolBackend(root)
    val supervisor = ShellProcessSupervisor()
    return ShellFileToolBackend("android", "root", root.path, root.path) { command, input, limit ->
        runOneShotShell(supervisor, "root", command, timeoutSeconds = 20, stdin = input, maxOutputBytes = limit)
    }
}
