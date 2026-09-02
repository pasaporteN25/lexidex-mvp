package com.lexidex.app.data.userdb

import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.lexidex.app.data.userdb.dao.CollectionDao
import com.lexidex.app.data.userdb.dao.FavoriteDao
import com.lexidex.app.data.userdb.dao.HistoryDao
import com.lexidex.app.data.userdb.dao.PersonalTermSourceDao
import com.lexidex.app.data.userdb.dao.SyncStorageDao
import com.lexidex.app.data.userdb.dao.TermVersionDao
import com.lexidex.app.data.userdb.dao.UserTermDao
import com.lexidex.app.data.userdb.entity.CollectionEntity
import com.lexidex.app.data.userdb.entity.CollectionTermEntity
import com.lexidex.app.data.userdb.entity.FavoriteEntity
import com.lexidex.app.data.userdb.entity.HistoryEntryEntity
import com.lexidex.app.data.userdb.entity.PersonalTermSourceEntity
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.data.userdb.entity.SyncReplicaCursorEntity
import com.lexidex.app.data.userdb.entity.SyncTombstoneEntity
import com.lexidex.app.data.userdb.entity.TermVersionEntity
import com.lexidex.app.data.userdb.entity.TermVersionFtsEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.data.userdb.entity.UserTermFtsEntity

/**
 * The user's own writable catalog (docs/decisions/0002-personal-catalog-overlay.md): personal
 * terms, favorites, history. A small, independent artifact Room creates and owns outright -
 * unlike LexidexDatabase, there's no external file and no identity-check workaround needed.
 */
@Database(
    entities = [
        UserTermEntity::class,
        PersonalTermSourceEntity::class,
        UserTermFtsEntity::class,
        FavoriteEntity::class,
        HistoryEntryEntity::class,
        CollectionEntity::class,
        CollectionTermEntity::class,
        SyncJournalEntity::class,
        SyncReplicaCursorEntity::class,
        SyncTombstoneEntity::class,
        TermVersionEntity::class,
        TermVersionFtsEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@ColumnTypeConverters(StringListConverter::class, TermOriginConverter::class)
abstract class LexidexUserDatabase : RoomDatabase() {
    abstract fun userTermDao(): UserTermDao
    abstract fun personalTermSourceDao(): PersonalTermSourceDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun collectionDao(): CollectionDao
    abstract fun syncStorageDao(): SyncStorageDao
    abstract fun termVersionDao(): TermVersionDao
}
