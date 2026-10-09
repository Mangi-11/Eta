package io.github.mangi.eta.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "learning_proposals", indices = [Index("status"), Index("createdAt")])
internal data class LearningProposalEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val detailMarkdown: String,
    val argumentsJson: String,
    val conversationId: String,
    val runId: String,
    val automatic: Boolean,
    val status: String = "pending",
    val errorCode: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val read: Boolean = false,
    val applyOwner: String = "",
)
