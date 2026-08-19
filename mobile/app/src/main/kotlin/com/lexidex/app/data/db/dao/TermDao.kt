package com.lexidex.app.data.db.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Query
import com.lexidex.app.data.db.entity.CategoryEntity
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.TagEntity
import com.lexidex.app.data.db.entity.TermEntity

/** A term joined with the relation row that connects it to the term being looked up. */
data class RelatedTermRow(
    @Embedded val term: TermEntity,
    @ColumnInfo(name = "relation_type") val relationType: String,
    val origin: String,
    val confidence: Double,
)

@Dao
interface TermDao {
    @Query(
        """
        SELECT terms.* FROM terms
        JOIN terms_fts ON terms_fts.rowid = terms.id
        WHERE terms_fts MATCH :matchQuery
        ORDER BY bm25(terms_fts)
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(matchQuery: String, limit: Int, offset: Int): List<TermEntity>

    @Query("SELECT * FROM terms WHERE slug = :slug LIMIT 1")
    suspend fun getBySlug(slug: String): TermEntity?

    @Query("SELECT * FROM terms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TermEntity?

    @Query("SELECT COUNT(*) FROM terms")
    suspend fun countTerms(): Long

    /** Todo el paquete, por pagina: es el listado que alimenta la pantalla de catalogo. */
    @Query("SELECT * FROM terms ORDER BY title COLLATE NOCASE LIMIT :limit OFFSET :offset")
    suspend fun listAll(limit: Int, offset: Int): List<TermEntity>

    /** Mirrors find_existing_term's canonical-package half in backend/lexidex_api.py. */
    @Query("SELECT slug FROM terms WHERE normalized_title = :normalizedTitle AND language = :language LIMIT 1")
    suspend fun findByNormalizedTitle(normalizedTitle: String, language: String): String?

    /** Deterministic "term of the day": the term at a stable rank in slug order. */
    @Query("SELECT * FROM terms ORDER BY slug LIMIT 1 OFFSET :rank")
    suspend fun getTermAtSlugRank(rank: Long): TermEntity?

    @Query("SELECT * FROM terms ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomTerm(): TermEntity?

    @Query(
        """
        SELECT categories.* FROM categories
        JOIN term_categories ON term_categories.category_id = categories.id
        WHERE term_categories.term_id = :termId
        ORDER BY categories.name
        """,
    )
    suspend fun getCategoriesForTerm(termId: Long): List<CategoryEntity>

    @Query(
        """
        SELECT tags.* FROM tags
        JOIN term_tags ON term_tags.tag_id = tags.id
        WHERE term_tags.term_id = :termId
        ORDER BY tags.name
        """,
    )
    suspend fun getTagsForTerm(termId: Long): List<TagEntity>

    @Query("SELECT * FROM sources WHERE term_id = :termId ORDER BY id")
    suspend fun getSourcesForTerm(termId: Long): List<SourceEntity>

    @Query("SELECT COUNT(*) FROM source_occurrences WHERE term_id = :termId")
    suspend fun countOccurrencesForTerm(termId: Long): Long

    @Query(
        """
        SELECT note FROM source_occurrences
        WHERE term_id = :termId AND note <> ''
        GROUP BY note
        ORDER BY MIN(line_number)
        """,
    )
    suspend fun getNotesForTerm(termId: Long): List<String>

    /**
     * Both directions of `term_relations` for [termId], mirroring `related_terms` in
     * backend/lexidex_api.py: outgoing relations always count, incoming ones only when
     * marked `bidirectional`.
     */
    @Query(
        """
        SELECT target.*, rel.relation_type AS relation_type, rel.origin AS origin, rel.confidence AS confidence
        FROM term_relations rel
        JOIN terms target ON target.id = rel.target_term_id
        WHERE rel.source_term_id = :termId
        UNION ALL
        SELECT source.*, rel.relation_type AS relation_type, rel.origin AS origin, rel.confidence AS confidence
        FROM term_relations rel
        JOIN terms source ON source.id = rel.source_term_id
        WHERE rel.target_term_id = :termId AND rel.bidirectional = 1
        ORDER BY title COLLATE NOCASE
        """,
    )
    suspend fun getRelatedTerms(termId: Long): List<RelatedTermRow>
}
