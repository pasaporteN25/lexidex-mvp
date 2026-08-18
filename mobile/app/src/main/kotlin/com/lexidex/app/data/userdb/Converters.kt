package com.lexidex.app.data.userdb

import androidx.room3.ColumnTypeConverter
import com.lexidex.app.domain.TermOrigin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** Stores `List<String>` as the same JSON-array text shape backend/lexidex_api.py uses for categories/tags. */
class StringListConverter {
    @ColumnTypeConverter
    fun fromJson(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    @ColumnTypeConverter
    fun toJson(value: List<String>): String = json.encodeToString(value)
}

class TermOriginConverter {
    @ColumnTypeConverter
    fun fromWireValue(value: String): TermOrigin = when (value) {
        "personal" -> TermOrigin.PERSONAL
        else -> TermOrigin.PACKAGE
    }

    @ColumnTypeConverter
    fun toWireValue(value: TermOrigin): String = when (value) {
        TermOrigin.PACKAGE -> "package"
        TermOrigin.PERSONAL -> "personal"
    }
}
