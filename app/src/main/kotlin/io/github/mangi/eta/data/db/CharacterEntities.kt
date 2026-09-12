package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "roleplay_characters")
internal data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "card_json") val cardJson: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String? = null,
    val archived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Serializable
@Entity(tableName = "roleplay_user_persona")
internal data class UserPersonaEntity(
    @PrimaryKey val id: String = "main",
    val name: String = "用户",
    val description: String = "",
)
