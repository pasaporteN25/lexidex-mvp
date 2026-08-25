package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.data.userdb.entity.SyncReplicaCursorEntity
import com.lexidex.app.data.userdb.entity.SyncTombstoneEntity

@Dao
interface SyncStorageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendJournal(change: SyncJournalEntity): Long

    @Query("SELECT * FROM sync_journal WHERE cursor > :cursor ORDER BY cursor LIMIT :limit")
    suspend fun journalAfter(cursor: Long, limit: Int): List<SyncJournalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(cursor: SyncReplicaCursorEntity)

    @Query("SELECT * FROM sync_replica_cursors WHERE device_id = :deviceId LIMIT 1")
    suspend fun cursorFor(deviceId: String): SyncReplicaCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstone(tombstone: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones WHERE entity_type = :entityType AND entity_id_json = :entityIdJson LIMIT 1")
    suspend fun tombstone(entityType: String, entityIdJson: String): SyncTombstoneEntity?
}
