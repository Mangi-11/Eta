package io.github.mangi.eta.agent.context

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import io.github.mangi.eta.agent.phone.PhoneProviderAccess
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VivoPersonalContextQueryTest {
    private lateinit var database: SQLiteDatabase
    private lateinit var access: PhoneProviderAccess
    private lateinit var service: VivoPersonalContextQuery

    @Before fun setup() {
        database = SQLiteDatabase.create(null)
        database.execSQL("CREATE TABLE notestable (_id INTEGER PRIMARY KEY, note_title TEXT, content_no_tag TEXT, curtimemillis INTEGER, createtime INTEGER, dirty INTEGER, has_passwd INTEGER, isEncrypted INTEGER, move_private INTEGER)")
        database.execSQL("CREATE TABLE notes_todo (_id INTEGER PRIMARY KEY, content TEXT, type INTEGER, notice_time INTEGER, modified_time INTEGER, created_time INTEGER, dirty INTEGER, move_private INTEGER)")
        access = ReadOnlyAccess(database)
        service = VivoPersonalContextQuery(access, 12)
    }

    @After fun cleanup() { database.close() }

    @Test fun searchesAndDetailReadsExcludeRecycledEncryptedAndPrivateNotes() {
        note(1)
        note(2, dirty = 2)
        note(3, password = 2)
        note(4, encrypted = 1)
        note(5, privateNote = 1)
        val result = service.execute("search_notes", JSONObject())
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(1, result.getInt("count"))
        assertEquals("eta-vivo:notes:1", result.getJSONArray("items").getJSONObject(0).getString("ref"))
        for (id in 2..5) {
            assertEquals("PERSONAL_CONTEXT_NOT_FOUND", service.execute("read_personal_item",
                JSONObject().put("ref", "eta-vivo:notes:$id")).getString("code"))
        }
    }

    @Test fun paginationAndTimestampBoundsAreStableAndHalfOpen() {
        note(1, time = 1_000)
        note(2, time = 2_000)
        note(3, time = 2_000)
        note(4, time = 3_000)
        val args = JSONObject().put("start_time", "1970-01-01T00:00:01.0001Z")
            .put("end_time", "1970-01-01T00:00:03Z").put("limit", 1)
        val first = service.execute("search_notes", args)
        assertEquals("3", first.getJSONArray("items").getJSONObject(0).getString("id"))
        assertTrue(first.getBoolean("has_more"))
        val second = service.execute("search_notes", args.put("offset", first.getInt("next_offset")))
        assertEquals("2", second.getJSONArray("items").getJSONObject(0).getString("id"))
        assertFalse(second.getBoolean("has_more"))
    }

    @Test fun keywordsAreBoundParametersAndWildcardCharactersAreLiteral() {
        note(1, title = "100%_complete")
        note(2, title = "100 percent complete")
        val matched = service.execute("search_notes", JSONObject().put("query", "%_"))
        assertEquals(1, matched.getInt("count"))
        val injected = service.execute("search_notes", JSONObject().put("query", "' OR 1=1 --"))
        assertTrue(injected.getBoolean("ok"))
        assertEquals(0, injected.getInt("count"))
    }

    @Test fun disappearingProtectionColumnFailsClosedEvenForDetailReads() {
        database.execSQL("ALTER TABLE notestable RENAME TO old_notes")
        database.execSQL("CREATE TABLE notestable (_id INTEGER PRIMARY KEY, note_title TEXT, content_no_tag TEXT, curtimemillis INTEGER, createtime INTEGER, dirty INTEGER, has_passwd INTEGER, isEncrypted INTEGER)")
        for ((tool, args) in listOf("search_notes" to JSONObject(), "read_personal_item" to JSONObject().put("ref", "eta-vivo:notes:1"))) {
            assertEquals("PERSONAL_CONTEXT_SCHEMA_UNSUPPORTED", service.execute(tool, args).getString("code"))
        }
    }

    @Test fun queryRejectsUntrustedReferencesAndInvalidTimeOrPageBeforeReading() {
        for (args in listOf(JSONObject().put("ref", "eta-index:notes:1"), JSONObject().put("ref", "eta-vivo:notes:9223372036854775808"))) {
            assertEquals("INVALID_ARGUMENT", service.execute("read_personal_item", args).getString("code"))
        }
        for (args in listOf(JSONObject().put("limit", 1.5), JSONObject().put("start_time", "tomorrow"), JSONObject().put("uri", "content://sms"))) {
            assertEquals("INVALID_ARGUMENT", service.execute("search_notes", args).getString("code"))
        }
    }

    @Test fun todosKeepPlannedReminderSeparateFromModificationTimeAndExcludePrivateRows() {
        database.execSQL("INSERT INTO notes_todo VALUES (1,'water plants',0,5000,2000,1000,0,0)")
        database.execSQL("INSERT INTO notes_todo VALUES (2,'private',0,5000,2000,1000,0,1)")
        database.execSQL("INSERT INTO notes_todo VALUES (3,'recycled',0,5000,2000,1000,2,0)")
        val result = service.execute("search_todos", JSONObject())
        assertEquals(1, result.getInt("count"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals("2000", item.getString("time"))
        assertEquals("5000", item.getString("planned_time"))
        assertEquals("eta-vivo:todos:1", item.getString("ref"))
    }

    @Test fun visibleRecordBecomingPrivateCannotBeReadUsingEarlierReference() {
        note(1)
        val ref = service.execute("search_notes", JSONObject()).getJSONArray("items").getJSONObject(0).getString("ref")
        database.execSQL("UPDATE notestable SET move_private=1 WHERE _id=1")
        assertEquals("PERSONAL_CONTEXT_NOT_FOUND", service.execute("read_personal_item", JSONObject().put("ref", ref)).getString("code"))
    }

    private fun note(id: Int, dirty: Int = 0, password: Int = 0, encrypted: Int = 0, privateNote: Int = 0, time: Long = 1_000, title: String = "note") {
        database.execSQL("INSERT INTO notestable VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(id, title, "test content", time, 500, dirty, password, encrypted, privateNote))
    }

    private class ReadOnlyAccess(private val database: SQLiteDatabase) : PhoneProviderAccess {
        override val callerPackage = "root"
        override fun query(uri: Uri, columns: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor {
            assertEquals("12@com.provider.notes", uri.authority)
            val table = when (uri.path) { "/note" -> "notestable"; "/todo" -> "notes_todo"; else -> throw AssertionError("Unexpected URI") }
            return database.rawQuery("SELECT ${columns.joinToString()} FROM $table" +
                (selection?.let { " WHERE $it" } ?: "") + (sort?.let { " ORDER BY $it" } ?: ""), args)
        }
        override fun insert(uri: Uri, values: ContentValues): Uri? = throw AssertionError("Write attempted")
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw AssertionError("Write attempted")
        override fun call(uri: Uri, method: String, arg: String?, extras: Bundle): Bundle? = throw AssertionError("Write attempted")
        override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = throw AssertionError("Write attempted")
    }
}
