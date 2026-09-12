package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.ContextWrapper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CharacterMemoryRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun storyMemoryIsScopedAndCannotTouchRealMemoryOrOtherCharacters() {
        val context = context()
        val real = AgentMemoryStore(context.filesDir)
        real.replaceAll("现实偏好")
        CharacterMemoryRepository.replaceAll(context, "character-a", "甲的剧情")
        CharacterMemoryRepository.replaceAll(context, "character-b", "乙的剧情")
        assertEquals("甲的剧情", CharacterMemoryRepository.snapshot(context, "character-a").content)
        assertEquals("乙的剧情", CharacterMemoryRepository.snapshot(context, "character-b").content)
        assertEquals("现实偏好", real.snapshot().content)
        assertThrows(IllegalArgumentException::class.java) {
            CharacterMemoryRepository.replaceAll(context, "../", "不能越界")
        }
    }

    @Test
    fun editorRevisionConflictPreservesConcurrentStoryWrite() {
        val context = context()
        val empty = CharacterMemoryRepository.snapshot(context, "character-a")
        val saved = CharacterMemoryRepository.replaceAllIfRevision(context, "character-a", empty.revision, "初始剧情")
        assertTrue(saved is AgentMemoryWriteResult.Success)
        CharacterMemoryRepository.replaceAll(context, "character-a", "运行中更新")
        val conflict = CharacterMemoryRepository.replaceAllIfRevision(context, "character-a", empty.revision, "过时草稿")
        assertTrue(conflict is AgentMemoryWriteResult.Conflict)
        assertEquals("运行中更新", CharacterMemoryRepository.snapshot(context, "character-a").content)
    }

    @Test
    fun disabledMemoryRejectsCharacterWritesBeforeOpeningTheStore() {
        val context = context()
        var enabled = true
        val tools = CharacterMemoryTools(context, "character-a") { enabled }
        enabled = false
        val result = tools.execute(AgentModelClient.ToolCall("call", CharacterMemoryTools.WRITE, "{}"))
        assertEquals("MEMORY_DISABLED", JSONObject(result.content).getString("code"))
        assertTrue(result.sensitive)
        assertFalse(File(context.filesDir, "roleplay").exists())
    }

    private fun context(): Context {
        val files = temporaryFolder.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
    }
}
