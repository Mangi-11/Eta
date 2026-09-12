package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface CharacterDao : ChunkedTextDao {
    @Query("SELECT * FROM roleplay_characters WHERE :includeArchived OR archived = 0 ORDER BY updated_at DESC, id ASC")
    suspend fun characterRows(includeArchived: Boolean): List<CharacterEntity>

    @Transaction
    suspend fun characters(includeArchived: Boolean = false): List<CharacterEntity> =
        characterRows(includeArchived).map { restoreCharacter(it) }

    @Query("SELECT * FROM roleplay_characters WHERE id = :id")
    suspend fun characterRow(id: String): CharacterEntity?

    @Transaction
    suspend fun character(id: String): CharacterEntity? = characterRow(id)?.let { restoreCharacter(it) }

    suspend fun restoreCharacter(row: CharacterEntity) = row.copy(
        cardJson = restoreText("roleplay_characters", row.id, "card", row.cardJson),
    )

    @Upsert
    suspend fun upsertCharacterRow(row: CharacterEntity)

    @Transaction
    suspend fun upsertCharacter(row: CharacterEntity) = upsertCharacterRow(row.copy(
        cardJson = storeText("roleplay_characters", row.id, "card", row.cardJson),
    ))

    @Query("DELETE FROM roleplay_characters WHERE id = :id")
    suspend fun deleteCharacter(id: String)

    @Query("DELETE FROM roleplay_characters")
    suspend fun deleteCharacters()

    @Query("SELECT * FROM roleplay_user_persona WHERE id = 'main'")
    suspend fun personaRow(): UserPersonaEntity?

    @Transaction
    suspend fun persona(): UserPersonaEntity? = personaRow()?.let { row ->
        row.copy(description = restoreText("roleplay_user_persona", row.id, "description", row.description))
    }

    @Upsert
    suspend fun upsertPersonaRow(row: UserPersonaEntity)

    @Transaction
    suspend fun upsertPersona(row: UserPersonaEntity) = upsertPersonaRow(row.copy(
        description = storeText("roleplay_user_persona", row.id, "description", row.description),
    ))

    @Query("DELETE FROM roleplay_user_persona")
    suspend fun deletePersona()

    @Transaction
    suspend fun replaceAll(characters: List<CharacterEntity>, persona: UserPersonaEntity?) {
        deleteCharacters()
        deletePersona()
        characters.forEach { upsertCharacter(it) }
        persona?.let { upsertPersona(it) }
    }
}
