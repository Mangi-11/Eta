package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LearningProposalDao {
    @Query("SELECT * FROM learning_proposals ORDER BY CASE WHEN status IN ('pending','applying','uncertain') THEN 0 ELSE 1 END, createdAt DESC LIMIT 200")
    fun observe(): Flow<List<LearningProposalEntity>>

    @Query("SELECT * FROM learning_proposals WHERE id=:id")
    suspend fun get(id: String): LearningProposalEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(proposal: LearningProposalEntity): Long

    @Query("SELECT id FROM learning_proposals WHERE id=:id OR (kind=:kind AND argumentsJson=:arguments AND status='pending') ORDER BY createdAt DESC LIMIT 1")
    suspend fun existing(id: String, kind: String, arguments: String): String?

    @Query("SELECT COUNT(*) FROM learning_proposals WHERE status IN ('pending','applying','uncertain')")
    suspend fun activeCount(): Int

    @Transaction
    suspend fun enqueue(proposal: LearningProposalEntity): String? {
        existing(proposal.id, proposal.kind, proposal.argumentsJson)?.let { return it }
        if (activeCount() >= 200) return null
        insert(proposal)
        prune()
        return proposal.id
    }

    @Query("UPDATE learning_proposals SET read=1 WHERE id=:id")
    suspend fun markRead(id: String)

    @Query("UPDATE learning_proposals SET status='applying',applyOwner=:owner,updatedAt=:now,read=1 WHERE id=:id AND status='pending'")
    suspend fun claim(id: String, owner: String, now: Long): Int

    @Query("UPDATE learning_proposals SET status=:status,errorCode=:code,updatedAt=:now WHERE id=:id AND status='applying' AND applyOwner=:owner")
    suspend fun finish(id: String, owner: String, status: String, code: String, now: Long): Int

    @Query("UPDATE learning_proposals SET status='uncertain',errorCode='APPROVAL_INTERRUPTED',updatedAt=:now WHERE status='applying' AND applyOwner!=:owner")
    suspend fun recoverInterrupted(owner: String, now: Long): Int

    @Query("UPDATE learning_proposals SET status='rejected',updatedAt=:now,read=1 WHERE id=:id AND status IN ('pending','failed','stale','uncertain')")
    suspend fun reject(id: String, now: Long): Int

    @Query("UPDATE learning_proposals SET status='refining',updatedAt=:now,read=1 WHERE id=:id AND status IN ('pending','failed','stale','uncertain','approved','rejected')")
    suspend fun refine(id: String, now: Long): Int

    @Query("DELETE FROM learning_proposals WHERE status NOT IN ('pending','applying','uncertain') AND id NOT IN (SELECT id FROM learning_proposals ORDER BY createdAt DESC LIMIT 200)")
    suspend fun prune()
}
