package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Cambio autoritativo, ordenado por el cursor monotono que asignara el hub en 9.5. */
@Entity(
    tableName = "sync_journal",
    indices = [
        Index(value = ["source_device_id", "change_id"], unique = true),
        Index(value = ["entity_type", "entity_id_json"]),
    ],
)
data class SyncJournalEntity(
    @PrimaryKey(autoGenerate = true) val cursor: Long? = null,
    @ColumnInfo(name = "source_device_id") val sourceDeviceId: String,
    @ColumnInfo(name = "change_id") val changeId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id_json") val entityIdJson: String,
    val operation: String,
    val revision: Long,
    @ColumnInfo(name = "payload_version", defaultValue = "1") val payloadVersion: Int = 1,
    @ColumnInfo(name = "changed_at") val changedAt: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String?,
)

/** Ultimo cursor aplicado por replica; se avanza solo junto con la pagina completa. */
@Entity(tableName = "sync_replica_cursors")
data class SyncReplicaCursorEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "last_applied_cursor", defaultValue = "0") val lastAppliedCursor: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/** Ausencia explicita retenida para que una replica atrasada no resucite un borrado. */
@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entity_type", "entity_id_json"],
    indices = [Index(value = ["cursor"])],
)
data class SyncTombstoneEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id_json") val entityIdJson: String,
    val revision: Long,
    val cursor: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: String,
    @ColumnInfo(name = "purge_after") val purgeAfter: String,
)
