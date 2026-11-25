package io.github.mangi.eta.agent.terminal.files

import android.app.Application
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FileToolDispatcherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingOrNonTextFileArgumentsNeverWrite() {
        val file = temporary.newFile("memo.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        val nonTextValues = listOf(JSONObject.NULL, JSONObject().put("text", "changed"), JSONArray().put("changed"))
        listOf("path", "content").forEach { key ->
            listOf<Any?>(null).plus(nonTextValues).forEach { value ->
                val args = JSONObject().put("path", file.name).put("content", "changed")
                if (value == null) args.remove(key) else args.put(key, value)
                rejectsText(key) { FileToolDispatcher.execute(backend, "write_file", args) }
                assertEquals("original", file.readText())
                assertEquals(listOf(file.name), temporary.root.list()!!.toList())
            }
        }
    }

    @Test
    fun missingOrNonTextReplacementNeverEdits() {
        val file = temporary.newFile("memo.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        listOf("old_text", "new_text").forEach { key ->
            listOf(null, JSONObject.NULL, JSONObject().put("text", "changed")).forEach { value ->
                val args = JSONObject().put("path", file.name).put("old_text", "original").put("new_text", "changed")
                if (value == null) args.remove(key) else args.put(key, value)
                rejectsText(key) { FileToolDispatcher.execute(backend, "edit_file", args) }
                assertEquals("original", file.readText())
            }
        }
    }

    @Test
    fun explicitEmptyTextStillClearsFilesAndDeletesMatches() {
        val file = temporary.newFile("memo.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        val edited = JSONObject(FileToolDispatcher.execute(backend, "edit_file", JSONObject()
            .put("path", file.name).put("old_text", "origin").put("new_text", "")))
        assertTrue(edited.toString(), edited.getBoolean("ok"))
        assertEquals("al", file.readText())
        val written = JSONObject(FileToolDispatcher.execute(backend, "write_file", JSONObject()
            .put("path", file.name).put("content", "")))
        assertTrue(written.toString(), written.getBoolean("ok"))
        assertEquals("", file.readText())
    }

    private fun rejectsText(key: String, operation: () -> Unit) {
        try {
            operation()
            throw AssertionError("accepted non-text $key")
        } catch (failure: JSONException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(key))
        }
    }
}
