package com.lexidex.app.data.repository

import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.corpus.PackageIntegrityException
import com.lexidex.app.data.db.dao.RelatedTermRow
import com.lexidex.app.data.db.dao.TermDao
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.TermEntity
import com.lexidex.app.data.userdb.UserDatabaseProvider
import com.lexidex.app.data.userdb.dao.UserTermDao
import com.lexidex.app.data.userdb.entity.CollectionEntity
import com.lexidex.app.data.userdb.entity.HistoryEntryEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.CatalogFilter
import com.lexidex.app.domain.HistoryItem
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.domain.TermCollection
import com.lexidex.app.domain.TermCollectionDetail
import com.lexidex.app.domain.TermDetail
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.TermRelation
import com.lexidex.app.domain.TermSource
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.domain.games.CINCO_QUESTION_COUNT
import com.lexidex.app.domain.games.CincoQuestion
import com.lexidex.app.domain.games.CincoQuestionBuilder
import com.lexidex.app.domain.games.DistractorPicker
import com.lexidex.app.domain.games.GameTerm
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

private const val DEFAULT_SEARCH_LIMIT = 50
private const val DEFAULT_HISTORY_LIMIT = 50
private const val DEFAULT_PERSONAL_LIST_LIMIT = 500
private const val DEFAULT_CATALOG_PAGE = 100
private const val MAX_COLLECTION_NAME = 80

/** Three times what a round needs: candidates that cannot become a question are walked past. */
private const val CANDIDATES_PER_ROUND = CINCO_QUESTION_COUNT * 3

/** Same-language terms drawn per question: enough to survive the picker's filtering. */
private const val OPTION_SAMPLE_SIZE = 40

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

    // region Minijuego "Cinco"

    /**
     * A whole round: five questions with their clue and their four options already assembled, so
     * that nothing goes back to the database between one question and the next.
     *
     * Candidates are drawn weighted by catalog size, the way [getRandomTerm] does, so a personal
     * term comes up as often as it is a share of everything askable. [boostWithCategories] changes
     * which package terms are drawn - only those in a category big enough to furnish a question -
     * but not that share, and it never forces the mode: a category that cannot supply three decoys
     * in the answer's own language falls back to the language pool, question by question.
     *
     * More candidates are drawn than a round needs. A term whose extract yields no fair clue, or
     * whose language holds no three others to stand next to it, is walked past rather than treated
     * as an error.
     */
    suspend fun buildCincoRound(boostWithCategories: Boolean = false): Result<List<CincoQuestion>> =
        corpusResult {
            val packageDao = termDao()
            val personalTerms = userTermDao().listEligible(DEFAULT_PERSONAL_LIST_LIMIT)
            val packageCount = packageDao.countEnrichedTerms()
            if (packageCount + personalTerms.size < CINCO_QUESTION_COUNT) {
                throw CorpusError.NotEnoughPlayableTerms()
            }

            val builder = CincoQuestionBuilder()
            val questions = mutableListOf<CincoQuestion>()
            val candidates =
                drawCandidates(packageDao, personalTerms, packageCount, boostWithCategories)
            for (candidate in candidates) {
                if (questions.size == CINCO_QUESTION_COUNT) break
                val pool =
                    optionPoolFor(packageDao, personalTerms, candidate.term, boostWithCategories)
                builder.build(candidate.term, candidate.extract, pool, boostWithCategories)
                    ?.let(questions::add)
            }
            if (questions.size < CINCO_QUESTION_COUNT) {
                throw CorpusError.NotEnoughPlayableTerms()
            }
            questions
        }

    /** A term drawn as a possible answer, before anyone knows whether it can become a question. */
    private class GameCandidate(val term: GameTerm, val extract: String)

    private suspend fun drawCandidates(
        packageDao: TermDao,
        personalTerms: List<UserTermEntity>,
        packageCount: Long,
        boostWithCategories: Boolean,
    ): List<GameCandidate> {
        val total = packageCount + personalTerms.size
        val personalSlots = (1..CANDIDATES_PER_ROUND)
            .count { Random.nextLong(total) >= packageCount }
            .coerceAtMost(personalTerms.size)
        val fromPackage = if (boostWithCategories) {
            packageDao.randomEligibleTermsWithUsableCategory(
                DistractorPicker.MIN_CATEGORY_MEMBERS,
                CANDIDATES_PER_ROUND - personalSlots,
            )
        } else {
            packageDao.randomEligibleTerms(CANDIDATES_PER_ROUND - personalSlots)
        }
        val fromPersonal = personalTerms.shuffled().take(personalSlots)
        return (
            fromPackage.map { term ->
                // id is nullable only to match the pre-packaged schema; a row read always has one.
                val categories = if (boostWithCategories) {
                    packageDao.getCategoriesForTerm(requireNotNull(term.id)).map { it.name }
                } else {
                    emptyList()
                }
                GameCandidate(
                    GameTerm(term.slug, term.title, term.language, categories),
                    term.content,
                )
            } + fromPersonal.map { GameCandidate(it.toGameTerm(), it.content) }
            ).shuffled()
    }

    /**
     * What the decoys for [answer] may be drawn from: every same-language member of its own
     * categories, the personal catalog, and a random sample of the package. The picker does the
     * filtering and the counting, and it can only count the categories it is handed - which is why
     * the category members arrive whole rather than sampled.
     */
    private suspend fun optionPoolFor(
        packageDao: TermDao,
        personalTerms: List<UserTermEntity>,
        answer: GameTerm,
        boostWithCategories: Boolean,
    ): List<GameTerm> {
        val categoryMembers = if (boostWithCategories && answer.categories.isNotEmpty()) {
            packageDao.eligibleOptionsInCategories(answer.categories, answer.language)
                .groupBy { it.slug }
                .map { (slug, rows) ->
                    GameTerm(
                        slug = slug,
                        title = rows.first().title,
                        language = rows.first().language,
                        categories = rows.map { it.categoryName },
                    )
                }
        } else {
            emptyList()
        }
        val personalOptions = personalTerms
            .filter { it.language.equals(answer.language, ignoreCase = true) }
            .map { it.toGameTerm() }
        val sample = packageDao
            .randomEligibleOptions(answer.language, answer.slug, OPTION_SAMPLE_SIZE)
            .map { GameTerm(it.slug, it.title, it.language) }
        // Category members first: where a term appears twice, the copy carrying its categories is
        // the one that survives the dedupe.
        return (categoryMembers + personalOptions + sample).distinctBy { it.slug }
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
        collectionDao().removeTermEverywhere(slug, TermOrigin.PERSONAL)
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

    /**
     * Una pagina del catalogo segun [filter]. Paginado de verdad porque el paquete son miles de
     * terminos y la pantalla los recorre; el equivalente de `?origin=` de la API.
     *
     * Con [CatalogFilter.ALL] los personales van primero y el paquete despues, en vez de
     * intercalarse: mezclar dos consultas ordenadas exigiria pedir todo de ambas para ordenar
     * bien, que es justo lo que la paginacion evita.
     */
    suspend fun listCatalog(
        filter: CatalogFilter,
        limit: Int = DEFAULT_CATALOG_PAGE,
        offset: Int = 0,
    ): Result<List<TermSummary>> = corpusResult {
        when (filter) {
            CatalogFilter.PERSONAL -> userTermDao().listAll(limit, offset).map { it.toSummary() }
            CatalogFilter.PACKAGE -> termDao().listAll(limit, offset).map { it.toSummary() }
            CatalogFilter.ALL -> {
                val personalTotal = userTermDao().countTerms()
                val personal = if (offset < personalTotal) {
                    userTermDao().listAll(limit, offset).map { it.toSummary() }
                } else {
                    emptyList()
                }
                if (personal.size >= limit) {
                    personal
                } else {
                    val packageOffset = (offset - personalTotal + personal.size).coerceAtLeast(0L)
                    val packageRows = termDao()
                        .listAll(limit - personal.size, packageOffset.toInt())
                        .map { it.toSummary() }
                    personal + packageRows
                }
            }
        }
    }

    /** De donde sale y donde se guarda todo, para la pantalla de opciones. */
    suspend fun getStorageInfo(knowledgeSources: List<String>): Result<StorageInfo> = corpusResult {
        val installed = databaseProvider.installedPackage()
        val userDatabase = userDatabaseProvider.get()
        StorageInfo(
            packageId = installed.marker?.packageId.orEmpty(),
            packageVersion = installed.marker?.packageVersion.orEmpty(),
            packageSha256 = installed.marker?.sha256.orEmpty(),
            packagePath = installed.databasePath,
            packageBytes = installed.databaseBytes,
            packageTerms = termDao().countTerms(),
            enrichedTerms = termDao().countEnrichedTerms(),
            personalPath = userDatabaseProvider.databasePath(),
            personalTerms = userDatabase.userTermDao().countTerms(),
            favorites = userDatabase.favoriteDao().countAll(),
            historyEntries = userDatabase.historyDao().countDistinctTerms(),
            knowledgeSources = knowledgeSources,
        )
    }

    suspend fun countCatalog(filter: CatalogFilter): Result<Long> = corpusResult {
        when (filter) {
            CatalogFilter.PERSONAL -> userTermDao().countTerms()
            CatalogFilter.PACKAGE -> termDao().countTerms()
            CatalogFilter.ALL -> userTermDao().countTerms() + termDao().countTerms()
        }
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

    // region Colecciones

    suspend fun listCollections(): Result<List<TermCollection>> = corpusResult {
        collectionDao().listAll().map { TermCollection(it.uid, it.name, it.termCount) }
    }

    suspend fun createCollection(name: String): Result<TermCollection> = corpusResult {
        val clean = normalizeText(name)
        if (clean.isBlank()) {
            throw CorpusError.InvalidField("name", "El nombre de la coleccion es obligatorio.")
        }
        if (clean.length > MAX_COLLECTION_NAME) {
            throw CorpusError.InvalidField("name", "El nombre supera los $MAX_COLLECTION_NAME caracteres.")
        }
        val normalized = normalizedKey(clean)
        if (collectionDao().findDuplicateName(normalized, null) != null) {
            throw CorpusError.DuplicateCollection(clean)
        }
        val now = nowIso()
        val uid = "col_${UUID.randomUUID().toString().replace("-", "")}"
        collectionDao().insert(
            CollectionEntity(
                uid = uid,
                name = clean,
                normalizedName = normalized,
                createdAt = now,
                updatedAt = now,
            ),
        )
        TermCollection(uid, clean, 0)
    }

    suspend fun renameCollection(uid: String, name: String): Result<Unit> = corpusResult {
        val clean = normalizeText(name)
        if (clean.isBlank()) {
            throw CorpusError.InvalidField("name", "El nombre de la coleccion es obligatorio.")
        }
        val normalized = normalizedKey(clean)
        if (collectionDao().findDuplicateName(normalized, uid) != null) {
            throw CorpusError.DuplicateCollection(clean)
        }
        collectionDao().rename(uid, clean, normalized, nowIso())
    }

    suspend fun deleteCollection(uid: String): Result<Unit> = corpusResult {
        val dao = collectionDao()
        val collection = dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
        collection.id?.let { dao.deleteMembers(it) }
        dao.deleteByUid(uid)
    }

    /**
     * Los miembros que ya no se pueden resolver se omiten en vez de romper la coleccion: un
     * termino personal pudo borrarse, o un paquete nuevo puede no traer mas uno del paquete.
     */
    suspend fun getCollection(uid: String): Result<TermCollectionDetail> = corpusResult {
        val dao = collectionDao()
        val collection = dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
        val terms = dao.members(collection.id ?: 0L)
            .mapNotNull { resolveSummary(it.termSlug, it.termOrigin) }
        TermCollectionDetail(collection.uid, collection.name, terms)
    }

    suspend fun collectionsContaining(slug: String, origin: TermOrigin): Result<Set<String>> =
        corpusResult { collectionDao().uidsContaining(slug, origin).toSet() }

    suspend fun setCollectionMembership(
        uid: String,
        slug: String,
        origin: TermOrigin,
        member: Boolean,
    ): Result<Unit> = corpusResult {
        val dao = collectionDao()
        val collection = dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
        val id = collection.id ?: throw CorpusError.CollectionNotFound(uid)
        if (member) dao.addMember(id, slug, origin, nowIso()) else dao.removeMember(id, slug, origin)
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
    private suspend fun collectionDao() = userDatabaseProvider.get().collectionDao()

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

private fun UserTermEntity.toGameTerm() = GameTerm(
    slug = slug,
    title = title,
    language = language,
    categories = categories,
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
