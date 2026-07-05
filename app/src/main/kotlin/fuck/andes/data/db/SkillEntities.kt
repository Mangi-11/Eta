package fuck.andes.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skill_registry")
internal data class SkillRegistryEntity(
    @PrimaryKey @ColumnInfo(name = "skill_id") val skillId: String,
    val enabled: Boolean,
    val source: String,
    @ColumnInfo(name = "install_state") val installState: String,
)
