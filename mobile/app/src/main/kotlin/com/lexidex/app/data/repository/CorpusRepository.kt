package com.lexidex.app.data.repository

import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.corpus.PackageIntegrityException
import com.lexidex.app.data.db.dao.RelatedTermRow
import com.lexidex.app.data.db.dao.TermDao
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.TermEntity
import com.lexidex.app.data.userdb.UserDatabaseProvider
import com.lexidex.app.data.userdb.dao.UserTermDao
import com.lexidex.app.data.userdb.entity.HistoryEntryEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.HistoryItem
import com.lexidex.app.domain.TermDetail
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.TermRelation
import com.lexidex.app.domain.TermSource
import com.lexidex.app.domain.TermSummary
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

private const val DEFAULT_SEARCH_LIMIT = 50
private const val DEFAULT_HISTORY_LIMIT = 50
private const val DEFAULT_PERSONAL_LIST_LIMIT = 500

/** Days from the proleptic-Gregorian epoch (year 1) to the Unix epoch - `date(1970,1,1).toordinal()` in Python. */
private const val PYTHON_ORDINAL_EPOCH_OFFSET = 719_163L

/**
 * The catalog as a whole: the read-only canonical package merged with the user's own writable
 * terms, favorites and history (docs/decisions/0002-personal-catalog-overlay.md) - one class,
 * mirroring how backend/lexidex_api.py's handlers each take both `package_conn` and `user_conn`
 * together rather than splitting canonical and personal into separate services.
 */
class CorpusRepository(
    private val databaseProvider: CorpusDatabaseProvider,
    private val userDatabaseProvider: UserDatabaseProvider,
) {

    /** Forces (or joins) the verify-and-open of the bundled package without running a query. */
    suspend fun ensureReady(): Result<Unit> = corpusResult {
        databaseProvider.get()
        Unit
    }

    // region Combined search, detail, daily, random

    suspend fun search(query: String): Result<List<TermSummary>> = corpusResult {
        val matchQuery = buildFtsMatchQuery(query)
        if (matchQuery.isBlank()) {
            emptyList()
        } else {
            val packageResults = termDao().search(matchQuery, DEFAULT_SEARCH_LIMIT, 0).map { it.toSummary() }
            val personalResults = userTermDao().search(matchQuery, DEFAULT_SEARCH_LIMIT, 0).map { it.toSummary() }
            packageResults + personalResults
        }
    }

    /** Personal terms win on a slug collision, matching `get_catalog_term` in backend/lexidex_api.py. */
    suspend fun getTermDetail(slug: String): Result<TermDetail?> = corpusResult {
        val personal = userTermDao().getBySlug(slug)
        if (personal != null) {
            buildPersonalDetail(personal)
        } else {
            val dao = termDao()
            dao.getBySlug(slug)?.let { buildDetail(dao, it) }
        }
    }

    /** The same term for everyone on a given day: a stable rank across both catalogs, package first. */
    suspend fun getDailyTerm(date: LocalDate = LocalDate.now()): Result<TermDetail?> = corpusResult {
        val packageDao = termDao()
        val personalDao = userTermDao()
        val packageCount = packageDao.countTerms()
        val total = packageCount + personalDao.countTerms()
        if (total == 0L) {
            null
        } else {
            val pythonOrdinal = date.toEpochDay() + PYTHON_ORDINAL_EPOCH_OFFSET
            val rank = Math.floorMod(pythonOrdinal, total)
            if (rank < packageCount) {
                packageDao.getTermAtSlugRank(rank)?.let { buildDetail(packageDao, it) }
            } else {
                personalDao.getTermAtSlugRank(rank - packageCount)?.let { buildPersonalDetail(it) }
            }
        }
    }

    suspend fun getRandomTerm(): Result<TermDetail?> = corpusResult {
        val packageDao = termDao()
        val personalDao = userTermDao()
        val packageCount = packageDao.countTerms()
        val total = packageCount + personalDao.countTerms()
        if (total == 0L) {
            null
        } else {
            // Weighted by catalog size so every row across both catalogs has equal probability,
            // without loading either catalog's full row set just to pick one at random.
            if (Random.nextLong(total) < packageCount) {
                packageDao.getRandomTerm()?.let { buildDetail(packageDao, it) }
            } else {
                personalDao.getRandomTerm()?.let { buildPersonalDetail(it) }
            }
        }
    }

    // endregion

    // region Personal term CRUD

    suspend fun createPersonalTerm(input: PersonalTermInput): Result<TermDetail> = corpusResult {
        val validated = validatePersonalTerm(input)
        requireNoDuplicate(validated.normalizedTitle, validated.language, excludeUid = null)
        val uid = newPersonalTermUid()
        val now = nowIso()
        val term = UserTermEntity(
            uid = uid,
            slug = personalTermSlug(uid, validated.language, validated.title),
            title = validated.title,
            normalizedTitle = validated.normalizedTitle,
            language = validated.language,
            kind = validated.kind,
            status = validated.status,
            summary = validated.summary,
            content = validated.content,
            sourceUrl = validated.sourceUrl,
            categories = validated.categories,
            tags = validated.tags,
            notes = validated.notes,
            createdAt = now,
            updatedAt = now,
        )
        userTermDao().insert(term)
        buildPersonalDetail(term)
    }

    suspend fun updatePersonalTerm(slug: String, input: PersonalTermInput): Result<TermDetail> = corpusResult {
        val current = userTermDao().getBySlug(slug) ?: throw CorpusError.PersonalTermNotFound(slug)
        val validated = validatePersonalTerm(input)
        requireNoDuplicate(validated.normalizedTitle, validated.language, excludeUid = current.uid)
        val updated = current.copy(
            title = validated.title,
            normalizedTitle = validated.normalizedTitle,
            language = validated.language,
            kind = validated.kind,
            status = validated.status,
            summary = validated.summary,
            content = validated.content,
            sourceUrl = validated.sourceUrl,
            categories = validated.categories,
            tags = validated.tags,
            notes = validated.notes,
            revision = current.revision + 1,
            updatedAt = nowIso(),
        )
        userTermDao().update(updated)
        buildPersonalDetail(updated)
    }

    suspend fun deletePersonalTerm(slug: String): Result<Unit> = corpusResult {
        if (userTermDao().deleteBySlug(slug) == 0) {
            throw CorpusError.PersonalTermNotFound(slug)
        }
        favoriteDao().remove(slug, TermOrigin.PERSONAL)
        historyDao().deleteByTerm(slug, TermOrigin.PERSONAL)
    }

    private suspend fun requireNoDuplicate(normalizedTitle: String, language: String, excludeUid: String?) {
        val existing = userTermDao().findDuplicate(normalizedTitle, language, excludeUid)
            ?: termDao().findByNormalizedTitle(normalizedTitle, language)
        if (existing != null) {
            throw CorpusError.DuplicateTitle(existing)
        }
    }

    /**
     * El catalogo personal completo, independiente de si un termino esta en favoritos o fue visto
     * alguna vez. Es el equivalente de `?origin=personal` que la API ya expone para la web.
     */
    suspend fun listPersonalTerms(limit: Int = DEFAULT_PERSONAL_LIST_LIMIT, offset: Int = 0): Result<List<TermSummary>> =
        corpusResult {
            userTermDao().listAll(limit, offset).map { it.toSummary() }
        }

    // endregion

    // region Favorites

    suspend fun isFavorite(slug: String, origin: TermOrigin): Result<Boolean> = corpusResult {
        favoriteDao().find(slug, origin) != null
    }

    /** Returns the new state: true if it's now a favorite, false if it was just removed. */
    suspend fun toggleFavorite(slug: String, origin: TermOrigin): Result<Boolean> = corpusResult {
        val dao = favoriteDao()
        if (dao.find(slug, origin) != null) {
            dao.remove(slug, origin)
            false
        } else {
            dao.add(slug, origin, nowIso())
            true
        }
    }

    suspend fun listFavorites(): Result<List<TermSummary>> = corpusResult {
        favoriteDao().listAll().mapNotNull { resolveSummary(it.termSlug, it.termOrigin) }
    }

    // endregion

    // region History

    suspend fun recordHistoryView(slug: String, origin: TermOrigin): Result<Unit> = corpusResult {
        historyDao().record(HistoryEntryEntity(termSlug = slug, termOrigin = origin, viewedAt = nowIso()))
    }

    suspend fun listRecentHistory(limit: Int = DEFAULT_HISTORY_LIMIT): Result<List<HistoryItem>> = corpusResult {
        historyDao().recentlyViewed(limit).mapNotNull { row ->
            resolveSummary(row.termSlug, row.termOrigin)?.let { HistoryItem(it, row.viewedAt) }
        }
    }

    // endregion

    private suspend fun resolveSummary(slug: String, origin: TermOrigin): TermSummary? = when (origin) {
        TermOrigin.PACKAGE -> termDao().getBySlug(slug)?.toSummary()
        TermOrigin.PERSONAL -> userTermDao().getBySlug(slug)?.toSummary()
    }

    private suspend fun termDao(): TermDao = databaseProvider.get().termDao()
    private suspend fun userTermDao(): UserTermDao = userDatabaseProvider.get().userTermDao()
    private suspend fun favoriteDao() = userDatabaseProvider.get().favoriteDao()
    private suspend fun historyDao() = userDatabaseProvider.get().historyDao()

    private suspend fun buildDetail(dao: TermDao, term: TermEntity): TermDetail {
        // id is nullable only because Room requires that type for an INTEGER PRIMARY KEY rowid
        // alias to match the pre-packaged schema; every row actually read from the table has one.
        val termId = requireNotNull(term.id)
        val categories = dao.getCategoriesForTerm(termId).map { it.name }
        val tags = dao.getTagsForTerm(termId).map { it.name }
        val sources = dao.getSourcesForTerm(termId).map { it.toDomain() }
        val occurrenceCount = dao.countOccurrencesForTerm(termId)
        val notes = dao.getNotesForTerm(termId)
        val relations = dao.getRelatedTerms(termId).map { it.toDomain() }
        return term.toDetail(categories, tags, sources, occurrenceCount, notes, relations)
    }

    /** Mirrors `personal_term_from_row` in backend/lexidex_api.py: no source_occurrences or term_relations exist for personal terms. */
    private fun buildPersonalDetail(term: UserTermEntity): TermDetail = TermDetail(
        slug = term.slug,
        title = term.title,
        language = term.language,
        kind = term.kind,
        status = term.status,
        summary = term.summary,
        content = term.content,
        categories = term.categories,
        tags = term.tags,
        sources = if (term.sourceUrl.isBlank()) emptyList() else listOf(term.sourceUrl.toManualSource(term.language)),
        occurrenceCount = 1,
        notes = if (term.notes.isBlank()) emptyList() else listOf(term.notes),
        relations = emptyList(),
        origin = TermOrigin.PERSONAL,
        editable = true,
    )

    private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private suspend fun <T> corpusResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageIntegrityException) {
            Result.failure(CorpusError.PackageCorrupted(e))
        } catch (e: PersonalTermValidationException) {
            Result.failure(CorpusError.InvalidField(e.field, e.message ?: "Campo invalido"))
        } catch (e: CorpusError) {
            Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e("CorpusRepository", "Unexpected corpus error", e)
            Result.failure(CorpusError.Unexpected(e))
        }
}

private fun TermEntity.toSummary() = TermSummary(
    slug = slug,
    title = title,
    summary = summary,
    language = language,
    status = status,
    origin = TermOrigin.PACKAGE,
)

private fun UserTermEntity.toSummary() = TermSummary(
    slug = slug,
    title = title,
    summary = summary,
    language = language,
    status = status,
    origin = TermOrigin.PERSONAL,
)

private fun TermEntity.toDetail(
    categories: List<String>,
    tags: List<String>,
    sources: List<TermSource>,
    occurrenceCount: Long,
    notes: List<String>,
    relations: List<TermRelation>,
) = TermDetail(
    slug = slug,
    title = title,
    language = language,
    kind = kind,
    status = status,
    summary = summary,
    content = content,
    categories = categories,
    tags = tags,
    sources = sources,
    occurrenceCount = occurrenceCount,
    notes = notes,
    relations = relations,
    origin = TermOrigin.PACKAGE,
    editable = false,
)

private fun SourceEntity.toDomain() = TermSource(
    kind = sourceKind,
    url = url,
    host = host,
    language = language,
    licenseName = licenseName,
    retrievedAt = retrievedAt,
)

private fun String.toManualSource(language: String) = TermSource(
    kind = "manual",
    url = this,
    host = runCatching { URI(this).host }.getOrNull().orEmpty(),
    language = language,
    licenseName = "",
    retrievedAt = null,
)

private fun RelatedTermRow.toDomain() = TermRelation(
    slug = term.slug,
    title = term.title,
    summary = term.summary,
    relationType = relationType,
    origin = origin,
    confidence = confidence,
)
