package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Espeja la tabla `collections` de backend/lexidex_api.py. */
@Entity(
    tableName = "collections",
    indices = [Index(value = ["uid"], unique = true), Index(value = ["normalized_name"], unique = true)],
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    val uid: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)
