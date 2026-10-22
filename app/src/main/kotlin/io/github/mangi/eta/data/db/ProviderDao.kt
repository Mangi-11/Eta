package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ProviderDao {
    @Transaction
    @Query("SELECT * FROM model_providers ORDER BY sort_order ASC")
    fun providersFlow(): Flow<List<ProviderWithModels>>

    @Transaction
    @Query("SELECT * FROM model_providers ORDER BY sort_order ASC")
    suspend fun providers(): List<ProviderWithModels>

    @Query("DELETE FROM provider_models")
    suspend fun deleteAllModels()

    @Query("DELETE FROM model_providers")
    suspend fun deleteAllProviders()

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

    @Query("SELECT * FROM provider_models WHERE provider_id = :providerId ORDER BY sort_order ASC")
    suspend fun models(providerId: String): List<ProviderModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviders(providers: List<ProviderEntity>)

    @Update
    suspend fun updateProvider(provider: ProviderEntity): Int

    @Query(
        "UPDATE model_providers SET auth_mode = :authMode, is_enabled = :isEnabled " +
            "WHERE id = :providerId AND auth_mode = 'api_key' AND api_key = '' AND is_enabled = 0"
    )
    suspend fun activateRestoredOAuthProvider(providerId: String, authMode: String, isEnabled: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModels(models: List<ProviderModelEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertModelsIgnoringConflicts(models: List<ProviderModelEntity>)

    @Query(
        """
        UPDATE provider_models
        SET reasoning_capabilities_json = :updatedJson
        WHERE id = :modelId AND provider_id = :providerId AND model_id = :apiModelId
            AND source = 'catalog' AND reasoning_capabilities_json = :previousJson
        """
    )
    suspend fun updateCatalogReasoningCapabilitiesIfUnchanged(
        providerId: String,
        modelId: String,
        apiModelId: String,
        previousJson: String,
        updatedJson: String,
    ): Int

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
    suspend fun replaceModels(
        providerId: String,
        models: List<ProviderModelEntity>,
    ) {
        deleteModelsForProvider(providerId)
        if (models.isNotEmpty()) {
            upsertModels(models)
        }
    }

    @Transaction
    suspend fun appendModelsIfAbsent(
        providerId: String,
        candidates: List<ProviderModelEntity>,
    ) {
        if (candidates.isEmpty()) return
        val stored = models(providerId)
        val knownModelIds = stored.mapTo(mutableSetOf()) { it.modelId.trim().lowercase() }
        var nextSortOrder = (stored.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val missing = candidates.mapNotNull { candidate ->
            if (candidate.modelId.isBlank() || !knownModelIds.add(candidate.modelId.trim().lowercase())) {
                null
            } else {
                candidate.copy(providerId = providerId, sortOrder = nextSortOrder++)
            }
        }
        if (missing.isNotEmpty()) insertModelsIgnoringConflicts(missing)
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

    @Transaction
    suspend fun replaceAll(providers: List<ProviderWithModelsSeed>) {
        deleteAllModels()
        deleteAllProviders()
        insertProvidersWithModels(providers)
    }
}

internal data class ProviderWithModelsSeed(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity>,
)
