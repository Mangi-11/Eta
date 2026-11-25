package io.github.mangi.eta.data.repository.backup

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.ProviderRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupStreamingTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        AgentMemoryRepository.replaceAll("")
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
    }

    @Test
    fun aBackupAboveTheOld64MiBLimitRoundTripsWithoutCollectingAllMessages() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("large")))
        val text = "首行😀\n\"\\\t" + "x".repeat(512 * 1024)
        repeat(132) { index ->
            dao.insertMessages(listOf(ConversationMessageEntity("message-$index", "large", index, "thinking", text)))
        }
        val file = File(context.cacheDir, "large-backup-test.json")
        try {
            val exported = file.outputStream().use { EtaBackupRepository.export(context, it) }
            assertTrue(file.length() > 64L * 1024L * 1024L)
            assertEquals(132, exported.messageCount)
            assertEquals(exported, file.inputStream().use { EtaBackupRepository.inspect(context, it) })
            dao.deleteConversations()

            val imported = file.inputStream().use { EtaBackupRepository.import(context, it) }

            assertEquals(exported, imported)
            assertEquals(132, dao.messageCount("large"))
            assertEquals(text, dao.messagesPage("large", 1, 0).single().content)
            assertEquals(text, dao.messagesPage("large", 1, 131).single().content)
            assertCleanStaging()
        } finally {
            file.delete()
        }
    }

    @Test
    fun reorderedLegacyFieldsAndUnknownValuesRemainReadable() = runBlocking {
        val bytes = """{
            "messages":[{"id":"m","conversationId":"c","sortIndex":0,"type":"user","content":"你好😀"}],
            "unknown":{"nested":[1,true,null,{"text":"忽略"}]},
            "conversations":[{"id":"c","title":"旧备份","thinkingEnabled":false,"createdAt":1,"updatedAt":2}],
            "exportedAt":0,"schemaVersion":1,"format":"eta-backup"
        }""".toByteArray()

        val summary = EtaBackupRepository.import(context, ByteArrayInputStream(bytes))

        assertEquals(1, summary.conversationCount)
        assertEquals("你好😀", EtaDatabase.get(context).conversationDao().messages().single().content)
        assertCleanStaging()
    }

    @Test
    fun veryLargeMessageAndCheckpointFieldsArePreserved() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val text = "😀\n\"\\" + "x".repeat(17 * 1024 * 1024)
        val history = "[{\"role\":\"assistant\",\"content\":${Json.encodeToString(text)}}]"
        dao.insertConversations(listOf(conversation("large-field")))
        dao.insertMessages(listOf(ConversationMessageEntity("large-message", "large-field", 0, "thinking", text)))
        dao.insertContextCheckpoints(listOf(ConversationContextCheckpointEntity("large-field", history, history)))
        val file = File(context.cacheDir, "large-field-backup-test.json")
        try {
            file.outputStream().use { EtaBackupRepository.export(context, it) }
            dao.deleteConversations()

            file.inputStream().use { EtaBackupRepository.import(context, it) }

            assertTrue(dao.messagesPage("large-field", 1, 0).single().content == text)
            val checkpoint = dao.contextCheckpoint("large-field")!!
            assertTrue(checkpoint.historyJson == history)
            assertTrue(checkpoint.journalJson == history)
            assertCleanStaging()
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedOrLateInvalidRecordsNeverReplaceExistingData() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("original")))
        AgentMemoryRepository.replaceAll("原记忆")
        val document = EtaBackupDocument(exportedAt = 0, memoryMd = "新记忆",
            conversations = listOf(conversation("new")),
            messages = listOf(
                ConversationMessageEntity("valid", "new", 0, "user", "有效"),
                ConversationMessageEntity("orphan", "missing", 1, "user", "无所属会话"),
            ),
        )
        val encoded = Json.encodeToString(document)
        val invalid = listOf(encoded, encoded.dropLast(2), "$encoded {}",
            """{"format":"eta-backup","format":"other","exportedAt":0}""")
        for (source in invalid) {
            var rejected = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(source.toByteArray()))
            } catch (_: EtaBackupException) { rejected = true }
            assertTrue(rejected)
            assertEquals(listOf("original"), dao.conversationEntities().map { it.id })
            assertEquals("原记忆", AgentMemoryRepository.snapshot().content)
            assertCleanStaging()
        }
    }

    @Test
    fun cancellingStagingClosesTemporaryFilesAndPreservesTheCallerStream() = runBlocking {
        val job = Job()
        var closed = false
        val input = object : ByteArrayInputStream("""{"exportedAt":0,"messages":[],"memoryMd":"内容"}""".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, minOf(length, 8))
                job.cancel()
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        var cancelled = false
        try { withContext(job) { EtaBackupJsonStreams.stage(context, input).close() } }
        catch (_: CancellationException) { cancelled = true }

        assertTrue(cancelled)
        assertFalse(closed)
        assertCleanStaging()
    }

    @Test
    fun aLateExportReadFailureLeavesTheDestinationUntouchedAndRemovesStaging() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("broken")))
        dao.insertMessages(listOf(ConversationMessageEntity("broken-message", "broken", 0, "user", "x".repeat(40_000))))
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "DELETE FROM agent_text_chunks WHERE owner_table = 'conversation_messages' AND chunk_index = 0",
        )
        val output = ByteArrayOutputStream()
        var failed = false
        try { EtaBackupRepository.export(context, output) } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(0, output.size())
        assertCleanStaging()
    }

    @Test
    fun outputIoFailuresCleanStagingAndKeepDatabaseContents() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("existing")))
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("private-output-path") }
        }
        var failed = false
        try { EtaBackupRepository.export(context, output) } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("existing"), dao.conversationEntities().map { it.id })
        assertCleanStaging()
    }

    @Test
    fun byteLimitStopsBothSingleByteAndBufferedReads() {
        for (buffered in listOf(false, true)) {
            val input = EtaBackupJsonStreams.LimitedInput(ByteArrayInputStream(ByteArray(65)), 64)
            var rejected = false
            try {
                if (buffered) input.readBytes() else while (input.read() >= 0) { }
            } catch (_: EtaBackupException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test
    fun exportLimitsAndCancellationKeepTheDestinationUntouched() = runBlocking {
        val output = ByteArrayOutputStream()
        var rejected = false
        try {
            EtaBackupJsonStreams.export(context, output, maxBytes = 64) { writer ->
                writer.value("memoryMd", String.serializer(), "x".repeat(128))
                EtaBackupSummary(0, 0, 0, 0, 128)
            }
        } catch (_: EtaBackupException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, output.size())
        assertCleanStaging()

        val job = Job()
        var cancelled = false
        try {
            withContext(job) {
                EtaBackupJsonStreams.export(context, output) { writer ->
                    writer.value("memoryMd", String.serializer(), "内容")
                    job.cancel()
                    EtaBackupSummary(0, 0, 0, 0, 6)
                }
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, output.size())
        assertCleanStaging()
    }

    private fun conversation(id: String) = ConversationEntity(id, id, false, createdAt = 1, updatedAt = 2)

    private fun assertCleanStaging() {
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("eta_backup_") })
    }
}
