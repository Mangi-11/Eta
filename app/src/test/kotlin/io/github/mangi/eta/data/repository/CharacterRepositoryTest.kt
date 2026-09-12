package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CharacterRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        CharacterRepository.initialize(context)
    }

    @Test
    fun largeCardsPersonaAndConversationBindingsUseChunkedStorage() = runBlocking {
        val description = "山与海😀".repeat(30_000)
        val profile = CharacterRepository.create(CharacterCardCodec.create("角色").withEdits(description = description))
        CharacterRepository.savePersona(UserPersona("旅人", description))
        assertEquals(description, CharacterRepository.get(profile.id)?.card?.description)
        assertEquals(description, CharacterRepository.binding(profile.id).userDescription)
        val dao = EtaDatabase.get(context).conversationDao()
        val row = ConversationEntity("session", "角色会话", false, roleplayJson = description,
            revisionsJson = description, createdAt = 1, updatedAt = 2)
        dao.insertConversations(listOf(row))
        assertEquals(description, dao.roleplayJson(row.id))
        assertEquals(description, dao.conversationEntities().single().revisionsJson)
        EtaDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT card_json FROM roleplay_characters WHERE id = ?", arrayOf(profile.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).startsWith("@eta:chunks:v1:"))
        }
        CharacterRepository.archive(profile.id)
        assertEquals(emptyList<String>(), CharacterRepository.list().map { it.id })
        assertEquals(listOf(profile.id), CharacterRepository.list(true).map { it.id })
        val copy = CharacterRepository.duplicate(profile.id)
        assertEquals(description, copy.card.description)
        assertEquals(false, copy.archived)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun invalidAvatarDoesNotPartiallySaveEditedCard() = runBlocking {
        val profile = CharacterRepository.create(CharacterCardCodec.create("原角色"))
        var failed = false
        try {
            CharacterRepository.save(profile.copy(card = profile.card.withEdits(name = "修改后")), "不是图片".toByteArray())
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals("原角色", CharacterRepository.get(profile.id)?.card?.name)
    }
}
