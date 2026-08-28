package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.PersonalTermSourceEntity

@Dao
interface PersonalTermSourceDao {
    @Query("SELECT * FROM personal_term_sources WHERE term_uid = :termUid ORDER BY position")
    suspend fun forTerm(termUid: String): List<PersonalTermSourceEntity>

    @Query("SELECT * FROM personal_term_sources ORDER BY term_uid, position")
    suspend fun allForBackup(): List<PersonalTermSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<PersonalTermSourceEntity>)

    @Query("DELETE FROM personal_term_sources WHERE term_uid = :termUid")
    suspend fun deleteForTerm(termUid: String)

    suspend fun replaceForTerm(termUid: String, sources: List<PersonalTermSourceEntity>) {
        deleteForTerm(termUid)
        if (sources.isNotEmpty()) insertAll(sources)
    }
}
