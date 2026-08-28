package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Provenance owned by a personal term. Position zero is the legacy source_url projection. */
@Entity(
    tableName = "personal_term_sources",
    foreignKeys = [
        ForeignKey(
            entity = UserTermEntity::class,
            parentColumns = ["uid"],
            childColumns = ["term_uid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["term_uid"]),
        Index(value = ["term_uid", "position"], unique = true),
        Index(value = ["term_uid", "url"], unique = true),
    ],
)
data class PersonalTermSourceEntity(
    @PrimaryKey val uid: String,
    @ColumnInfo(name = "term_uid") val termUid: String,
    val position: Int,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    val title: String,
    val url: String,
    val language: String,
    @ColumnInfo(name = "license_name") val licenseName: String,
    @ColumnInfo(name = "retrieved_at") val retrievedAt: String?,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
)
