package fuck.andes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface SkillDao {
    @Query("SELECT * FROM skill_registry ORDER BY skill_id ASC")
    suspend fun registryEntries(): List<SkillRegistryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegistryEntry(entry: SkillRegistryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegistryEntries(entries: List<SkillRegistryEntity>)

    @Query("DELETE FROM skill_registry")
    suspend fun deleteRegistryEntries()

    @Query("DELETE FROM skill_registry WHERE skill_id = :skillId")
    suspend fun deleteRegistryEntry(skillId: String)

    @Transaction
    suspend fun replaceRegistry(entries: List<SkillRegistryEntity>) {
        deleteRegistryEntries()
        if (entries.isNotEmpty()) {
            insertRegistryEntries(entries)
        }
    }
}
