package com.lexidex.app.data.repository

import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.corpus.PackageIntegrityException
import com.lexidex.app.data.db.dao.RelatedTermRow
import com.lexidex.app.data.db.dao.TermDao
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.TermEntity
import com.lexidex.app.domain.TermDetail
import com.lexidex.app.domain.TermRelation
import com.lexidex.app.domain.TermSource
import com.lexidex.app.domain.TermSummary
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

private const val DEFAULT_SEARCH_LIMIT = 50

/** Days from the proleptic-Gregorian epoch (year 1) to the Unix epoch - `date(1970,1,1).toordinal()` in Python. */
private const val PYTHON_ORDINAL_EPOCH_OFFSET = 719_163L

class CorpusRepository(private val databaseProvider: CorpusDatabaseProvider) {

    /** Forces (or joins) the verify-and-open of the bundled package without running a query. */
    suspend fun ensureReady(): Result<Unit> = corpusResult {
        databaseProvider.get()
        Unit
    }

    suspend fun search(query: String): Result<List<TermSummary>> = corpusResult {
        val matchQuery = buildFtsMatchQuery(query)
        if (matchQuery.isBlank()) {
            emptyList()
        } else {
            termDao().search(matchQuery, DEFAULT_SEARCH_LIMIT, 0).map { it.toSummary() }
        }
    }

    suspend fun getTermDetail(slug: String): Result<TermDetail?> = corpusResult {
        val dao = termDao()
        dao.getBySlug(slug)?.let { buildDetail(dao, it) }
    }

    /** The same term for everyone on a given day: a stable rank into `terms` ordered by slug. */
    suspend fun getDailyTerm(date: LocalDate = LocalDate.now()): Result<TermDetail?> = corpusResult {
        val dao = termDao()
        val count = dao.countTerms()
        if (count == 0L) {
            null
        } else {
            val pythonOrdinal = date.toEpochDay() + PYTHON_ORDINAL_EPOCH_OFFSET
            val rank = Math.floorMod(pythonOrdinal, count)
            dao.getTermAtSlugRank(rank)?.let { buildDetail(dao, it) }
        }
    }

    suspend fun getRandomTerm(): Result<TermDetail?> = corpusResult {
        val dao = termDao()
        dao.getRandomTerm()?.let { buildDetail(dao, it) }
    }

    private suspend fun termDao(): TermDao = databaseProvider.get().termDao()

    private suspend fun buildDetail(dao: TermDao, term: TermEntity): TermDetail {
        val categories = dao.getCategoriesForTerm(term.id).map { it.name }
        val tags = dao.getTagsForTerm(term.id).map { it.name }
        val sources = dao.getSourcesForTerm(term.id).map { it.toDomain() }
        val occurrenceCount = dao.countOccurrencesForTerm(term.id)
        val notes = dao.getNotesForTerm(term.id)
        val relations = dao.getRelatedTerms(term.id).map { it.toDomain() }
        return term.toDetail(categories, tags, sources, occurrenceCount, notes, relations)
    }

    private suspend fun <T> corpusResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageIntegrityException) {
            Result.failure(CorpusError.PackageCorrupted(e))
        } catch (e: Exception) {
            Result.failure(CorpusError.Unexpected(e))
        }
}

private fun TermEntity.toSummary() = TermSummary(
    slug = slug,
    title = title,
    summary = summary,
    language = language,
    status = status,
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
)

private fun SourceEntity.toDomain() = TermSource(
    kind = sourceKind,
    url = url,
    host = host,
    language = language,
    licenseName = licenseName,
    retrievedAt = retrievedAt,
)

private fun RelatedTermRow.toDomain() = TermRelation(
    slug = term.slug,
    title = term.title,
    summary = term.summary,
    relationType = relationType,
    origin = origin,
    confidence = confidence,
)
