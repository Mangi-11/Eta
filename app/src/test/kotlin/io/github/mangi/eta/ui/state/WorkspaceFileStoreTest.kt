package io.github.mangi.eta.ui.state

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.terminal.files.FileToolBackend
import io.github.mangi.eta.agent.terminal.files.FileToolStat
import io.github.mangi.eta.agent.terminal.files.FileToolKind
import io.github.mangi.eta.agent.terminal.files.LocalFileToolBackend
import org.junit.Assert.assertArrayEquals
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkspaceFileStoreTest {
    @Test
    fun exportUsesThePrivilegedBackendForAnAppUnreadableArtifact() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = ByteArray(70_000) { (it % 256).toByte() }
        var reads = 0
        val store = WorkspaceFileStore(context) { root ->
            object : FileToolBackend by LocalFileToolBackend(root) {
                override val identity = "root"
                override fun stat(path: String) = FileToolStat(path, "root.bin", FileToolKind.FILE, bytes.size.toLong(), "revision")
                override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
                    reads++
                    return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + limit))
                }
            }
        }
        val destination = File(context.cacheDir, "export.bin")
        store.exportFile("root.bin", Uri.fromFile(destination))
        assertArrayEquals(bytes, destination.readBytes())
        assertTrue(reads > 1)
    }

    @Test
    fun freshWorkspaceCanBeListedWithoutRuntimeInitialization() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = WorkspaceFileStore(context)

        assertTrue(store.list("").isEmpty())
        assertTrue(TerminalPrivateStorage.workspace(context.filesDir).isDirectory)
    }

    @Test
    fun existingWorkspaceFilesRemainVisible() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val legacy = File(context.filesDir, "terminal/workspace").apply { mkdirs() }
        File(legacy, "saved.txt").writeText("saved")

        val entries = WorkspaceFileStore(context).list("")

        assertEquals(listOf("saved.txt"), entries.map { it.path })
        assertEquals("saved", File(legacy, "saved.txt").readText())
    }
}
