package io.github.mangi.eta.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** 旧大字段按 SQL 子串搬迁，迁移本身也不把整行装入 CursorWindow。 */
internal object HistoryPayloadMigration {
    fun migrate(database: SupportSQLiteDatabase) {
        move(database, "conversations", "id", mapOf("applied_runtime_run_ids_json" to "runs"))
        move(database, "conversation_context_checkpoints", "conversation_id", mapOf("history_json" to "history", "journal_json" to "journal"))
        move(database, "conversation_messages", "id", mapOf("content" to "content", "images_json" to "images"))
        val resultFields = mapOf("content" to "content", "reasoning_content" to "reasoningContent",
            "transcript_json" to "transcriptJson", "context_snapshot_json" to "contextSnapshotJson")
        move(database, "runtime_results", "run_id", resultFields)
        move(database, "runtime_archive_runs", "archive_run_id", resultFields + ("user_image_previews_json" to "userImagePreviewsJson"))
        move(database, "runtime_inflight_runs", "run_id", mapOf("context_snapshot_json" to "contextSnapshotJson", "transcript_json" to "transcriptJson"))
    }

    private fun move(db: SupportSQLiteDatabase, table: String, key: String, fields: Map<String, String>) {
        fields.forEach { (column, field) ->
            val owners = db.query("SELECT $key FROM $table WHERE length(CAST($column AS BLOB)) > 16384").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            owners.forEach { owner ->
                var index = 0
                var length = 0
                while (true) {
                    val chunk = db.query("SELECT substr($column, ?, 8192) FROM $table WHERE $key = ?",
                        arrayOf(index * 8192 + 1, owner)).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(0)
                    }
                    if (chunk.isEmpty()) break
                    db.execSQL("INSERT INTO agent_text_chunks (owner_table, owner_id, field, chunk_index, content) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(table, owner, field, index++, chunk))
                    length += chunk.length
                }
                val reference = "${ChunkedTextDao.REFERENCE_PREFIX}$index:$length"
                db.execSQL("UPDATE $table SET $column = ? WHERE $key = ?", arrayOf(reference, owner))
            }
        }
    }
}
