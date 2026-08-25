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

    /**
     * Lo que esta esperando para salir hacia el hub, en el orden en que se edito.
     *
     * En una replica el journal es la bandeja de salida: una fila vive aca hasta que el hub la
     * reconoce. En el hub la misma tabla es el registro autoritativo y no se vacia nunca.
     */
    @Query("SELECT * FROM sync_journal ORDER BY cursor LIMIT :limit")
    suspend fun pendingChanges(limit: Int): List<SyncJournalEntity>

    @Query("SELECT COUNT(*) FROM sync_journal")
    suspend fun pendingCount(): Long

    /** Se llama solo con lo que el hub confirmo: `applied` o `duplicate`. */
    @Query("DELETE FROM sync_journal WHERE change_id IN (:changeIds)")
    suspend fun forgetChanges(changeIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(cursor: SyncReplicaCursorEntity)

    @Query("SELECT * FROM sync_replica_cursors WHERE device_id = :deviceId LIMIT 1")
    suspend fun cursorFor(deviceId: String): SyncReplicaCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstone(tombstone: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones WHERE entity_type = :entityType AND entity_id_json = :entityIdJson LIMIT 1")
    suspend fun tombstone(entityType: String, entityIdJson: String): SyncTombstoneEntity?
}
