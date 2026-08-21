package com.lexidex.app.data.db.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Query
import com.lexidex.app.data.db.entity.CategoryEntity
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.TagEntity
import com.lexidex.app.data.db.entity.TermEntity

/** The columns the mini-game needs from a term it may show as one of the four options. */
data class GameOptionRow(
    val slug: String,
    val title: String,
    val language: String,
)

/** A [GameOptionRow] repeated once per category it belongs to, to be grouped by slug. */
data class GameCategoryOptionRow(
    val slug: String,
    val title: String,
    val language: String,
    @ColumnInfo(name = "category_name") val categoryName: String,
)

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

    @Query("SELECT COUNT(*) FROM terms WHERE content <> ''")
    suspend fun countEnrichedTerms(): Long

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

    // region Etiquetas navegables

    /**
     * Los terminos de una categoria. `COLLATE NOCASE` porque el nombre puede venir escrito a mano
     * en un termino personal, y ahi nadie respeta las mayusculas del paquete.
     */
    @Query(
        """
        SELECT terms.* FROM terms
        JOIN term_categories ON term_categories.term_id = terms.id
        JOIN categories ON categories.id = term_categories.category_id
        WHERE categories.name = :name COLLATE NOCASE
        ORDER BY terms.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun listByCategory(name: String, limit: Int): List<TermEntity>

    /** El paquete v0.4.0 no trae ninguna, pero un paquete futuro puede: la consulta ya existe. */
    @Query(
        """
        SELECT terms.* FROM terms
        JOIN term_tags ON term_tags.term_id = terms.id
        JOIN tags ON tags.id = term_tags.tag_id
        WHERE tags.name = :name COLLATE NOCASE
        ORDER BY terms.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun listByTag(name: String, limit: Int): List<TermEntity>

    // endregion

    // region Minijuego "Cinco"

    /**
     * Terms the game can ask about: the ones carrying an extract to redact. The count and the
     * draw share the criterion so a round can be weighted against the personal catalog first.
     */
    @Query("SELECT * FROM terms WHERE content <> '' ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomEligibleTerms(limit: Int): List<TermEntity>

    /**
     * The same draw restricted to terms in a category with at least [minMembers] members - three
     * decoys and the answer - which is what the category boost needs to have anything to work
     * with: 779 of the 4425 enriched terms of package v0.4.0, in 199 categories.
     *
     * Membership is counted across languages here, while `DistractorPicker` counts it per
     * language when it actually picks. The looser criterion costs one term (779 against 778) and
     * buys a query that does not correlate its grouping to each candidate row - the per-language
     * version measured 1.7 seconds against 13 milliseconds on the real package. A category that
     * turns out to be unusable in the answer's language just falls back to the language pool.
     */
    @Query(
        """
        SELECT terms.* FROM terms
        WHERE terms.content <> '' AND terms.id IN (
            SELECT term_categories.term_id FROM term_categories
            WHERE term_categories.category_id IN (
                SELECT big.category_id FROM term_categories AS big
                JOIN terms AS members ON members.id = big.term_id
                WHERE members.content <> ''
                GROUP BY big.category_id
                HAVING COUNT(*) >= :minMembers
            )
        )
        ORDER BY RANDOM()
        LIMIT :limit
        """,
    )
    suspend fun randomEligibleTermsWithUsableCategory(minMembers: Int, limit: Int): List<TermEntity>

    /** Candidate decoys: same language as the answer, never the answer, and askable themselves. */
    @Query(
        """
        SELECT slug, title, language FROM terms
        WHERE content <> '' AND language = :language AND slug <> :excludeSlug
        ORDER BY RANDOM()
        LIMIT :limit
        """,
    )
    suspend fun randomEligibleOptions(
        language: String,
        excludeSlug: String,
        limit: Int,
    ): List<GameOptionRow>

    /**
     * Every askable same-language member of [categoryNames], unlimited on purpose: the picker
     * decides whether a category is big enough, and it can only count what it was handed.
     */
    @Query(
        """
        SELECT terms.slug AS slug, terms.title AS title, terms.language AS language,
               categories.name AS category_name
        FROM terms
        JOIN term_categories ON term_categories.term_id = terms.id
        JOIN categories ON categories.id = term_categories.category_id
        WHERE terms.content <> '' AND terms.language = :language
        AND categories.name IN (:categoryNames)
        """,
    )
    suspend fun eligibleOptionsInCategories(
        categoryNames: List<String>,
        language: String,
    ): List<GameCategoryOptionRow>

    // endregion

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
