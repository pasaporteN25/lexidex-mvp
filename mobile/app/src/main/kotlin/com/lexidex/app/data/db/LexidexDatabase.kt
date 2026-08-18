package com.lexidex.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.lexidex.app.data.db.dao.TermDao
import com.lexidex.app.data.db.entity.AliasEntity
import com.lexidex.app.data.db.entity.CategoryEntity
import com.lexidex.app.data.db.entity.ImportEntity
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.SourceOccurrenceEntity
import com.lexidex.app.data.db.entity.TagEntity
import com.lexidex.app.data.db.entity.TermCategoryCrossRef
import com.lexidex.app.data.db.entity.TermEntity
import com.lexidex.app.data.db.entity.TermFtsEntity
import com.lexidex.app.data.db.entity.TermRelationEntity
import com.lexidex.app.data.db.entity.TermTagCrossRef

/**
 * The canonical knowledge package, opened read-only (see docs/decisions/0001 and 0002): no DAO
 * here exposes an insert/update/delete. `version` is pinned to `PRAGMA user_version` in
 * docs/corpus-schema.sql - the package is built outside Room, so a mismatch here would make
 * Room attempt a migration against a schema it doesn't own.
 */
@Database(
    entities = [
        TermEntity::class,
        ImportEntity::class,
        SourceEntity::class,
        SourceOccurrenceEntity::class,
        AliasEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        TermCategoryCrossRef::class,
        TermTagCrossRef::class,
        TermRelationEntity::class,
        TermFtsEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LexidexDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
}
