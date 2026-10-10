package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.mangi.eta.agent.skill.SkillAuthoringService
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LearningProposalRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var db: EtaDatabase
    private lateinit var secondDb: EtaDatabase
    private lateinit var memory: AgentMemoryStore
    private lateinit var authoring: SkillAuthoringService
    private lateinit var skillRoot: File
    private lateinit var databaseName: String
    private val allowed = AtomicBoolean(true)
    private val writes = AtomicInteger()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "learning-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName).enableMultiInstanceInvalidation().build()
        secondDb = Room.databaseBuilder(context, EtaDatabase::class.java, databaseName).enableMultiInstanceInvalidation().build()
        memory = AgentMemoryStore(folder.newFolder("memory-root"))
        memory.replaceAll("# Core\nOriginal")
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        skillRoot = folder.newFolder("skills")
        val index = SkillIndexService(context, skillRoot)
        authoring = SkillAuthoringService(index, SkillPackageInstaller(skillRoot, index))
    }

    @After fun cleanup() {
        db.close(); secondDb.close(); EtaDatabase.closeForTests()
        context.deleteDatabase(databaseName)
    }

    private fun repository(database: EtaDatabase = db) = LearningProposalRepository(context,
        database.learningProposalDao(), memory::preview, memory::mutate, authoring,
        permitted = { _, _ -> allowed.get() }, memoryUpdated = { writes.incrementAndGet() })
    private fun args() = JSONObject().put("mode", "append").put("revision", memory.snapshot().revision).put("content", "Durable preference")
    private suspend fun stage(repo: LearningProposalRepository = repository(), payload: JSONObject = args()) =
        repo.stage("memory_write", payload, "conversation", "run").getString("proposal_id")

    @Test fun draftsArePersistentDeduplicatedAndRejectionDoesNotWrite() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        val original = memory.snapshot()
        val id = stage(repo)
        assertEquals(id, stage(repo))
        assertEquals(id, repo.stage("memory_write", args(), "second-conversation", "second-run").getString("proposal_id"))
        assertEquals(original, memory.snapshot())
        assertEquals(1, repo.observe().first().size)
        assertEquals("pending", repository(secondDb).get(id)?.status)
        assertTrue(repo.reject(id))
        assertFalse(repo.approve(id))
        assertEquals(original, memory.snapshot())
        assertEquals(0, writes.get())
    }

    @Test fun pendingLimitDoesNotHideOlderProposalsOrEvictUnapprovedContent() = runBlocking(Dispatchers.IO) {
        val dao = db.learningProposalDao()
        for (index in 0 until 200) {
            assertNotNull(dao.enqueue(io.github.mangi.eta.data.db.LearningProposalEntity(
                "id-$index", "memory", "Title $index", "Fixture", "{\"fixture\":$index}", "conversation", "run", false,
                createdAt = index.toLong(), updatedAt = index.toLong())))
        }
        assertFalse(repository().stage("memory_write", args(), "conversation", "run").getBoolean("ok"))
        assertEquals(200, repository().observe().first().count { it.status == "pending" })
        assertTrue(repository().reject("id-0"))
        val next = repository().stage("memory_write", args(), "conversation", "run")
        assertTrue(next.getBoolean("ok"))
        assertEquals(200, repository().observe().first().count { it.status == "pending" })
        assertNotNull(dao.get("id-1"))
    }

    @Test fun simultaneousApprovalsFromIndependentDatabaseClientsApplyOnce() = runBlocking(Dispatchers.IO) {
        val first = repository()
        val second = repository(secondDb)
        val id = stage(first)
        val approvals = listOf(async { first.approve(id) }, async { second.approve(id) }).awaitAll()
        assertEquals(1, approvals.count { it })
        assertEquals(1, writes.get())
        assertEquals("# Core\nOriginal\nDurable preference", memory.snapshot().content)
        assertEquals("approved", withTimeout(3000) { second.observe().first { it.single().status == "approved" } }.single().status)
    }

    @Test fun changedRevisionAndRevokedPermissionsCannotOverwriteNewerMemory() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        val id = stage(repo)
        memory.replaceAll("Newer manual edit")
        assertFalse(repo.approve(id))
        assertEquals("stale", repo.get(id)?.status)
        assertEquals("Newer manual edit", memory.snapshot().content)
        val next = stage(repo)
        allowed.set(false)
        assertFalse(repo.approve(next))
        assertEquals("LEARNING_PERMISSION_REVOKED", repo.get(next)?.errorCode)
        assertEquals("Newer manual edit", memory.snapshot().content)
        assertEquals(0, writes.get())
    }

    @Test fun interruptedClaimsAreNotReplayedAndCanSeedRefinement() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        val id = stage(repo)
        assertEquals(1, db.learningProposalDao().claim(id, "old-process", 1))
        db.learningProposalDao().recoverInterrupted("new-process", 2)
        assertEquals("uncertain", repo.get(id)?.status)
        assertFalse(repo.approve(id))
        assertEquals(0, writes.get())
        assertTrue(repo.refinementPrompt(id)!!.contains("Durable preference"))
        assertTrue(repo.markRefining(id))
        assertEquals("refining", repo.get(id)?.status)
        assertNull(repo.refinementPrompt(id))
    }

    @Test fun skillProposalDoesNotInstallUntilApprovalAndInvalidPathsCannotBeStaged() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        val payload = JSONObject().put("action", "create").put("skillId", "fixture-workflow")
            .put("description", "Use for fixture tasks").put("bodyMarkdown", "# Steps\nObserve, then check the result.")
        val staged = repo.stage("skills_manage", payload, "conversation", "run")
        assertTrue(staged.toString(), staged.getBoolean("ok"))
        assertFalse(File(skillRoot, "fixture-workflow").exists())
        assertTrue(repo.approve(staged.getString("proposal_id")))
        assertTrue(File(skillRoot, "fixture-workflow/SKILL.md").isFile)
        assertFalse(repo.stage("skills_manage", payload.put("skillId", "../escape"), "conversation", "other").getBoolean("ok"))
        assertFalse(File(skillRoot.parentFile, "escape").exists())
    }
}
