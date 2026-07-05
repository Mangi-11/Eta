package fuck.andes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ProviderDao {
    @Transaction
    @Query("SELECT * FROM model_providers ORDER BY sort_order ASC")
    fun providersFlow(): Flow<List<ProviderWithModels>>

    @Transaction
    @Query("SELECT * FROM model_providers ORDER BY sort_order ASC")
    suspend fun providers(): List<ProviderWithModels>

    @Transaction
    @Query("SELECT * FROM model_providers WHERE id = :id")
    suspend fun providerById(id: String): ProviderWithModels?

    @Transaction
    @Query(
        """
        SELECT model_providers.*
        FROM model_providers
        INNER JOIN provider_models ON provider_models.provider_id = model_providers.id
        WHERE provider_models.id = :modelId
        LIMIT 1
        """
    )
    suspend fun providerByModelId(modelId: String): ProviderWithModels?

    @Query("SELECT * FROM provider_models WHERE provider_id = :providerId ORDER BY sort_order ASC")
    fun modelsFlow(providerId: String): Flow<List<ProviderModelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviders(providers: List<ProviderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModels(models: List<ProviderModelEntity>)

    @Query("DELETE FROM provider_models WHERE provider_id = :providerId")
    suspend fun deleteModelsForProvider(providerId: String)

    @Query("DELETE FROM model_providers WHERE id = :providerId")
    suspend fun deleteProvider(providerId: String)

    @Transaction
    suspend fun replaceProvider(
        provider: ProviderEntity,
        models: List<ProviderModelEntity>,
    ) {
        upsertProvider(provider)
        deleteModelsForProvider(provider.id)
        if (models.isNotEmpty()) {
            upsertModels(models)
        }
    }

    @Transaction
    suspend fun insertProvidersWithModels(providers: List<ProviderWithModelsSeed>) {
        if (providers.isEmpty()) return
        upsertProviders(providers.map { it.provider })
        providers.forEach { seed ->
            deleteModelsForProvider(seed.provider.id)
            if (seed.models.isNotEmpty()) {
                upsertModels(seed.models)
            }
        }
    }
}

internal data class ProviderWithModelsSeed(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity>,
)
