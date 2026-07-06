package fuck.andes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ConversationMessageEntity::class,
        ConversationStateEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        RuntimeResultEntity::class,
        RuntimeArchiveRunEntity::class,
        RuntimeArchiveEventEntity::class,
        SkillRegistryEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
internal abstract class FuckAndesDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun providerDao(): ProviderDao
    abstract fun runtimeRunDao(): RuntimeRunDao
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile
        private var instance: FuckAndesDatabase? = null

        fun get(context: Context): FuckAndesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FuckAndesDatabase::class.java,
                    "fuck_andes.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
