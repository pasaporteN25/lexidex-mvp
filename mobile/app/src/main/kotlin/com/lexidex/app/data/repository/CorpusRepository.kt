package com.lexidex.app.data.repository

import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.corpus.PackageIntegrityException
import com.lexidex.app.data.db.dao.RelatedTermRow
import com.lexidex.app.data.db.dao.TermDao
import com.lexidex.app.data.db.entity.SourceEntity
import com.lexidex.app.data.db.entity.TermEntity
import com.lexidex.app.data.userdb.UserDatabaseProvider
import com.lexidex.app.data.userdb.LexidexUserDatabase
import com.lexidex.app.data.userdb.mergeLegacyPrimarySource
import com.lexidex.app.data.userdb.personalContentSha256
import com.lexidex.app.data.userdb.stampImportedContent
import com.lexidex.app.data.userdb.dao.UserTermDao
import com.lexidex.app.data.userdb.entity.CollectionEntity
import com.lexidex.app.data.userdb.entity.PersonalTermSourceEntity
import com.lexidex.app.data.userdb.entity.TermVersionEntity
import com.lexidex.app.data.db.dao.RefreshableTermRow
import com.lexidex.app.data.knowledge.KnowledgeArticle
import com.lexidex.app.data.knowledge.wikipediaResultFromUrl
import com.lexidex.app.domain.BulkRefreshProgress
import com.lexidex.app.domain.RefreshBatch
import com.lexidex.app.domain.RefreshCandidate
import com.lexidex.app.domain.RefreshDecision
import com.lexidex.app.domain.planRefreshBatches
import com.lexidex.app.domain.TermRefresh
import com.lexidex.app.domain.TermVersion
import com.lexidex.app.domain.nextActiveAfterDeleting
import com.lexidex.app.domain.refreshDecision
import com.lexidex.app.domain.versionsToDrop
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.CatalogFilter
import com.lexidex.app.domain.HistoryItem
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.domain.TermCollection
import com.lexidex.app.domain.TermCollectionDetail
import com.lexidex.app.domain.TermDetail
import com.lexidex.app.domain.TermLabelKind
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.TermRelation
import com.lexidex.app.domain.TermSource
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.domain.backup.BackupCollection
import com.lexidex.app.domain.backup.BackupTerm
import com.lexidex.app.domain.backup.BackupTermRef
import com.lexidex.app.domain.backup.BackupTermSource
import com.lexidex.app.domain.backup.PersonalCatalogBackup
import com.lexidex.app.domain.games.CINCO_QUESTION_COUNT
import com.lexidex.app.domain.games.CincoQuestion
import com.lexidex.app.domain.games.CincoQuestionBuilder
import com.lexidex.app.domain.games.DistractorPicker
import com.lexidex.app.domain.games.GameTerm
import androidx.room3.withWriteTransaction
import com.lexidex.app.data.sync.DependentDelete
import com.lexidex.app.data.sync.FixedSyncDeviceIdentity
import com.lexidex.app.data.sync.SyncChangeRecorder
import com.lexidex.app.data.sync.SyncDeviceIdentity
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

/** Sin paginado: la categoria mas grande del paquete tiene 15 miembros. */
private const val DEFAULT_LABEL_LIMIT = 200

/**
 * Identidad de reserva para cuando nadie inyecto una.
 *
 * Solo la usan las pruebas y las vistas previas. En la aplicacion real la pone
 * [com.lexidex.app.LexidexApplication] desde preferencias, porque un `device_id` que cambia entre
 * sesiones rompe la idempotencia del hub, que se indexa por `(device_id, change_id)`.
 */
const val UNPAIRED_DEVICE_ID = "dev_00000000000000000000000000000000"
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
    private val deviceIdentity: SyncDeviceIdentity = FixedSyncDeviceIdentity(UNPAIRED_DEVICE_ID),
) {

    /**
     * Toda escritura del catalogo personal pasa por aca: aplica y anota en el journal dentro de la
     * misma transaccion.
     *
     * Es lo que le da al telefono algo para mandarle al hub. Una fila aplicada sin anotar se
     * pierde -nada la vuelve a mirar- y una anotada sin aplicar le contaria al otro lado un cambio
     * que no ocurrio, asi que las dos cosas van juntas o no van.
     */
    private suspend fun <T> journaling(
        block: suspend (LexidexUserDatabase, SyncChangeRecorder) -> T,
    ): T {
        val database = userDatabaseProvider.get()
        return database.withWriteTransaction {
            block(database, SyncChangeRecorder(database.syncStorageDao(), deviceIdentity.deviceId()))
        }
    }

    /** Forces (or joins) the verify-and-open of the bundled package without running a query. */
    suspend fun ensureReady(): Result<Unit> = corpusResult {
        databaseProvider.get()
        return@corpusResult Unit
    }

    // region Combined search, detail, daily, random

    suspend fun search(query: String): Result<List<TermSummary>> = corpusResult {
        val matchQuery = buildFtsMatchQuery(query)
        if (matchQuery.isBlank()) {
            emptyList()
        } else {
            val versions = versionDao()
            // Los terminos con una copia activa se buscan por esa copia y **se sacan del catalogo
            // de base**: si no, una palabra que la copia nueva ya no dice seguiria encontrandolos
            // por el texto viejo, que no es el que se va a leer.
            val overriddenPackage = versions.overriddenSlugs(TermOrigin.PACKAGE).toSet()
            val overriddenPersonal = versions.overriddenSlugs(TermOrigin.PERSONAL).toSet()

            val packageResults = termDao().search(matchQuery, DEFAULT_SEARCH_LIMIT, 0)
                .filterNot { it.slug in overriddenPackage }
                .map { it.toSummary() }
            val personalResults = userTermDao().search(matchQuery, DEFAULT_SEARCH_LIMIT, 0)
                .filterNot { it.slug in overriddenPersonal }
                .map { it.toSummary() }
            val versionResults = summariesForVersions(versions.search(matchQuery, DEFAULT_SEARCH_LIMIT))

            versionResults + packageResults + personalResults
        }
    }

    /**
     * El resumen de los terminos cuya copia activa coincidio con la busqueda.
     *
     * La copia guarda el texto pero no el titulo ni el idioma, que no cambian al actualizar, asi
     * que se los pide al termino de base y solo se reemplaza lo que la copia si tiene.
     */
    private suspend fun summariesForVersions(hits: List<TermVersionEntity>): List<TermSummary> {
        if (hits.isEmpty()) return emptyList()
        val byOrigin = hits.groupBy { it.origin }
        val packageBase = byOrigin[TermOrigin.PACKAGE]
            ?.let { rows -> termDao().bySlugs(rows.map { it.slug }).associateBy { it.slug } }
            .orEmpty()
        val personalBase = byOrigin[TermOrigin.PERSONAL]
            ?.let { rows -> userTermDao().bySlugs(rows.map { it.slug }).associateBy { it.slug } }
            .orEmpty()

        return hits.mapNotNull { version ->
            val base = when (version.origin) {
                TermOrigin.PACKAGE -> packageBase[version.slug]?.toSummary()
                TermOrigin.PERSONAL -> personalBase[version.slug]?.toSummary()
            }
            // Una copia sin termino de base quedo colgada -el paquete cambio y el slug ya no esta-
            // y no hay nada que mostrar; 10.6 se ocupa de limpiarlas.
            base?.copy(summary = version.summary.ifBlank { base.summary })
        }
    }

    /** Personal terms win on a slug collision, matching `get_catalog_term` in backend/lexidex_api.py. */
    suspend fun getTermDetail(slug: String): Result<TermDetail?> = corpusResult {
        val personal = userTermDao().getBySlug(slug)
        val detail = if (personal != null) {
            buildPersonalDetail(personal)
        } else {
            val dao = termDao()
            dao.getBySlug(slug)?.let { buildDetail(dao, it) }
        }
        detail?.let { withActiveVersion(it) }
    }

    /**
     * Reemplaza el texto por la copia activa, si el termino tiene una.
     *
     * Tambien mueve la fecha de la fuente de la que salio esa copia. Es lo que evita que la ficha
     * muestre el texto nuevo debajo de un "consultada el 19/08": las dos cosas tienen que hablar
     * de la misma copia, que es justamente lo que la epica vino a arreglar.
     *
     * Un termino sin copias guardadas se lee de su texto de base y no paga ninguna consulta de
     * mas mas alla de esta, que es un indice por slug + origen.
     */
    private suspend fun withActiveVersion(detail: TermDetail): TermDetail {
        val version = versionDao().active(detail.slug, detail.origin) ?: return detail
        return detail.copy(
            summary = version.summary.ifBlank { detail.summary },
            content = version.content,
            sources = detail.sources.map { source ->
                if (source.url == version.sourceUrl && version.sourceUrl.isNotBlank()) {
                    source.copy(
                        retrievedAt = version.retrievedAt,
                        contentSha256 = version.contentSha256,
                    )
                } else {
                    source
                }
            },
        )
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

    /**
     * Todos los terminos que llevan [name], de los dos catalogos y ordenados juntos por titulo.
     * Es lo que se ve al tocar un chip: una categoria del paquete y una etiqueta propia se
     * recorren igual aunque esten guardadas de formas distintas.
     */
    suspend fun listTermsByLabel(
        kind: TermLabelKind,
        name: String,
        limit: Int = DEFAULT_LABEL_LIMIT,
    ): Result<List<TermSummary>> = corpusResult {
        val packageDao = termDao()
        val personalDao = userTermDao()
        val fromPackage = when (kind) {
            TermLabelKind.CATEGORY -> packageDao.listByCategory(name, limit)
            TermLabelKind.TAG -> packageDao.listByTag(name, limit)
        }
        val fromPersonal = when (kind) {
            TermLabelKind.CATEGORY -> personalDao.listByCategory(name, limit)
            TermLabelKind.TAG -> personalDao.listByTag(name, limit)
        }
        (fromPackage.map { it.toSummary() } + fromPersonal.map { it.toSummary() })
            .sortedBy { it.title.lowercase() }
    }

    // region Respaldo

    /**
     * Todo el catalogo personal en un solo objeto, listo para escribir a un archivo. El paquete
     * no entra: viene con la aplicacion y se puede volver a instalar, mientras que esto es lo
     * unico que el usuario no puede recuperar de ningun otro lado.
     */
    suspend fun exportPersonalCatalog(): Result<PersonalCatalogBackup> = corpusResult {
        personalCatalogSnapshot(userDatabaseProvider.get(), nowIso())
    }

    /** Reads and compares an untrusted backup without writing anything. */
    suspend fun previewPersonalCatalogImport(text: String): Result<PersonalCatalogImportSummary> =
        corpusResult {
            val incoming = validatedPersonalCatalogBackupFromJson(text)
            val installedPackage = installedPackageSnapshot(incoming)
            val userDatabase = userDatabaseProvider.get()
            planPersonalCatalogImport(
                incoming = incoming,
                current = personalCatalogSnapshot(userDatabase, nowIso()),
                installedPackage = installedPackage,
            ).summary
        }

    /** Applies a freshly rebuilt merge plan atomically. Repeating the same file is safe. */
    suspend fun importPersonalCatalog(text: String): Result<PersonalCatalogImportSummary> =
        corpusResult {
            val incoming = validatedPersonalCatalogBackupFromJson(text)
            // The package is immutable and lives in another database. Resolve it before holding
            // the personal database's writer transaction.
            val installedPackage = installedPackageSnapshot(incoming)
            journaling { userDatabase, recorder ->
                val plan = planPersonalCatalogImport(
                    incoming = incoming,
                    current = personalCatalogSnapshot(userDatabase, nowIso()),
                    installedPackage = installedPackage,
                )
                applyPersonalCatalogImport(userDatabase, plan, recorder)
                plan.summary
            }
        }

    private suspend fun personalCatalogSnapshot(
        database: LexidexUserDatabase,
        exportedAt: String,
    ): PersonalCatalogBackup {
        val collections = database.collectionDao().listAllForBackup()
        val sourcesByTerm = database.personalTermSourceDao().allForBackup().groupBy { it.termUid }
        val membersByCollection = database.collectionDao().listAllMembersForBackup()
            .groupBy { it.collectionUid }
        return PersonalCatalogBackup(
            exportedAt = exportedAt,
            terms = database.userTermDao().listAllForBackup().map { term ->
                BackupTerm(
                    uid = term.uid,
                    slug = term.slug,
                    title = term.title,
                    language = term.language,
                    kind = term.kind,
                    status = term.status,
                    summary = term.summary,
                    content = term.content,
                    sourceUrl = term.sourceUrl,
                    sources = sourcesByTerm[term.uid].orEmpty().map { it.toBackupSource() },
                    categories = term.categories,
                    tags = term.tags,
                    notes = term.notes,
                    revision = term.revision,
                    createdAt = term.createdAt,
                    updatedAt = term.updatedAt,
                )
            },
            favorites = database.favoriteDao().listAll().map {
                BackupTermRef(it.termSlug, it.termOrigin.wireValue(), it.createdAt)
            },
            history = database.historyDao().listAllForBackup().map {
                BackupTermRef(it.termSlug, it.termOrigin.wireValue(), it.viewedAt)
            },
            collections = collections.map { collection ->
                BackupCollection(
                    uid = collection.uid,
                    name = collection.name,
                    createdAt = collection.createdAt,
                    updatedAt = collection.updatedAt,
                    members = membersByCollection[collection.uid].orEmpty().map {
                        BackupTermRef(it.termSlug, it.termOrigin.wireValue(), it.addedAt)
                    },
                )
            },
        )
    }

    private suspend fun installedPackageSnapshot(
        incoming: PersonalCatalogBackup,
    ): InstalledPackageSnapshot {
        val dao = termDao()
        val titleSlugs = buildMap {
            incoming.terms
                .map { TermTitleKey(normalizedKey(it.title), it.language) }
                .distinct()
                .forEach { key ->
                    dao.findByNormalizedTitle(key.normalizedTitle, key.language)?.let { slug ->
                        put(key, slug)
                    }
                }
        }
        val referencedPackageSlugs = buildSet {
            incoming.favorites.filterToPackageSlugs(this)
            incoming.history.filterToPackageSlugs(this)
            incoming.collections.forEach { it.members.filterToPackageSlugs(this) }
        }
        val installedSlugs = referencedPackageSlugs.filterTo(mutableSetOf()) { slug ->
            dao.getBySlug(slug) != null
        }
        return InstalledPackageSnapshot(installedSlugs, titleSlugs)
    }

    /**
     * Escribe el plan y lo anota, fila por fila.
     *
     * Es el bootstrap del ADR 0004: no hay un segundo formato de mezcla, el plan confirmado se
     * convierte en cambios normales del contrato. Por eso lo importado se anota igual que lo que
     * el usuario escribe a mano; si no, un catalogo entero traido de otro telefono quedaria
     * invisible para el hub.
     *
     * Cada revision se relee despues de escribir en vez de asumirla: la fila pudo existir ya, y
     * anotar una revision que no es la que quedo en la tabla le contaria al hub un encadenado que
     * no existe.
     */
    private suspend fun applyPersonalCatalogImport(
        database: LexidexUserDatabase,
        plan: PersonalCatalogImportPlan,
        recorder: SyncChangeRecorder,
    ) {
        val termDao = database.userTermDao()
        val sourceDao = database.personalTermSourceDao()
        (plan.termsToAdd + plan.termsToUpdate).forEach { imported ->
            val local = termDao.getByUid(imported.uid)
            if (local == null) {
                termDao.insert(imported.toEntity())
            } else {
                termDao.update(imported.toEntity(id = local.id))
            }
            val stored = termDao.getByUid(imported.uid)
                ?: throw CorpusError.PersonalTermNotFound(imported.slug)
            val incomingSources = imported.sources.mapIndexed { position, source ->
                source.toEntity(imported.uid, position)
            }
            val sources = if (plan.sourcePayloadVersion == 1) {
                mergeLegacyPrimarySource(
                    imported.uid,
                    imported.language,
                    imported.sourceUrl,
                    sourceDao.forTerm(imported.uid),
                )
            } else {
                incomingSources
            }
            sourceDao.replaceForTerm(imported.uid, sources)
            val projected = sources.firstOrNull()?.url.orEmpty()
            val projectedTerm = if (stored.sourceUrl == projected) stored else stored.copy(sourceUrl = projected)
            if (stored.sourceUrl != projected) termDao.update(projectedTerm)
            recorder.termUpserted(projectedTerm, sources, projectedTerm.updatedAt)
        }

        val collectionDao = database.collectionDao()
        plan.collectionsToAdd.forEach { collection ->
            collectionDao.insert(collection.toEntity())
            recorder.collectionUpserted(
                uid = collection.uid,
                name = collection.name,
                createdAt = collection.createdAt,
                updatedAt = collection.updatedAt,
                revision = collectionDao.findByUid(collection.uid)?.revision ?: 1,
            )
        }
        plan.collectionsToUpdate.forEach { collection ->
            collectionDao.rename(
                uid = collection.uid,
                name = collection.name,
                normalizedName = normalizedKey(collection.name),
                updatedAt = collection.updatedAt,
            )
            recorder.collectionUpserted(
                uid = collection.uid,
                name = collection.name,
                createdAt = collection.createdAt,
                updatedAt = collection.updatedAt,
                revision = collectionDao.findByUid(collection.uid)?.revision ?: 1,
            )
        }

        plan.favoritesToAdd.forEach { reference ->
            val origin = reference.origin.toTermOrigin()
            database.favoriteDao().add(reference.slug, origin, reference.at)
            recorder.favoriteChanged(
                slug = reference.slug,
                origin = origin,
                present = true,
                revision = database.favoriteDao().row(reference.slug, origin)?.revision ?: 1,
                changedAt = reference.at,
            )
        }
        plan.historyToAdd.forEach { reference ->
            val origin = reference.origin.toTermOrigin()
            database.historyDao().record(reference.slug, origin, reference.at)
            recorder.historyChanged(
                slug = reference.slug,
                origin = origin,
                present = true,
                revision = database.historyDao().row(reference.slug, origin)?.revision ?: 1,
                changedAt = reference.at,
            )
        }
        plan.membersToAdd.forEach { member ->
            collectionDao.findByUid(member.collectionUid)
                ?: throw CorpusError.CollectionNotFound(member.collectionUid)
            val origin = member.reference.origin.toTermOrigin()
            collectionDao.addMember(
                collectionUid = member.collectionUid,
                slug = member.reference.slug,
                origin = origin,
                addedAt = member.reference.at,
            )
            recorder.memberChanged(
                collectionUid = member.collectionUid,
                slug = member.reference.slug,
                origin = origin,
                present = true,
                revision = collectionDao.memberRow(
                    member.collectionUid,
                    member.reference.slug,
                    origin,
                )?.revision ?: 1,
                changedAt = member.reference.at,
            )
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
        journaling { database, recorder ->
            database.userTermDao().insert(term)
            val sources = stampImportedContent(
                mergeLegacyPrimarySource(uid, term.language, term.sourceUrl, emptyList()),
                term.content,
                input.contentCameFromSource,
                now,
            )
            database.personalTermSourceDao().replaceForTerm(uid, sources)
            recorder.termUpserted(term, sources, now)
        }
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
        journaling { database, recorder ->
            val sources = stampImportedContent(
                mergeLegacyPrimarySource(
                    updated.uid,
                    updated.language,
                    validated.sourceUrl,
                    database.personalTermSourceDao().forTerm(updated.uid),
                ),
                updated.content,
                input.contentCameFromSource,
                updated.updatedAt,
            )
            val projected = updated.copy(sourceUrl = sources.firstOrNull()?.url.orEmpty())
            database.userTermDao().update(projected)
            database.personalTermSourceDao().replaceForTerm(projected.uid, sources)
            recorder.termUpserted(projected, sources, projected.updatedAt)
        }
        buildPersonalDetail(userTermDao().getByUid(updated.uid) ?: updated)
    }

    // region Copias fechadas

    /**
     * Guarda el texto que acaba de llegar de la fuente, o dice que no cambio.
     *
     * La red **no** entra aca: el ViewModel la trae y esto decide y escribe. Es la misma division
     * que usa el minijuego, y es lo que deja probar la decision sin levantar Room ni salir a
     * internet.
     *
     * La primera vez que se actualiza un termino se guarda tambien **el texto de base** como una
     * copia mas, fechada con lo que sepamos de el. Sin eso, actualizar seria un camino de ida:
     * el paquete es de solo lectura y no habria adonde volver.
     */
    suspend fun storeRefreshedCopy(
        slug: String,
        summary: String,
        content: String,
        sourceUrl: String,
        retrievedAt: String,
    ): Result<TermRefresh> = corpusResult {
        val detail = requireNotNull(getTermDetail(slug).getOrThrow()) {
            "No existe el termino $slug"
        }
        val versions = versionDao()
        val stored = versions.forTerm(slug, detail.origin).map { it.toDomain() }
        val incomingSha = personalContentSha256(content)
        val activeSince = stored.firstOrNull { it.isActive }?.retrievedAt
            ?: detail.sources.firstOrNull { it.url == sourceUrl }?.retrievedAt.orEmpty()

        when (
            val decision = refreshDecision(
                incomingSha = incomingSha,
                activeSha = personalContentSha256(detail.content),
                activeSince = activeSince,
                stored = stored,
            )
        ) {
            is RefreshDecision.Keep -> TermRefresh.Unchanged(decision.since)

            is RefreshDecision.Reactivate -> {
                versions.activate(decision.uid)
                TermRefresh.Updated(decision.retrievedAt)
            }

            RefreshDecision.Store -> {
                if (stored.isEmpty()) {
                    versions.insert(
                        baseVersion(detail, sourceUrl, fallbackDate = retrievedAt),
                    )
                }
                val fresh = TermVersionEntity(
                    uid = newVersionUid(),
                    slug = slug,
                    origin = detail.origin,
                    summary = summary,
                    content = content,
                    contentSha256 = incomingSha,
                    retrievedAt = retrievedAt,
                    sourceUrl = sourceUrl,
                    isActive = false,
                    createdAt = nowIso(),
                )
                versions.insert(fresh)
                versions.activate(fresh.uid)
                dropExcessVersions(slug, detail.origin)
                TermRefresh.Updated(retrievedAt)
            }
        }
    }

    /** Las copias de un termino, de la mas nueva a la mas vieja. */
    suspend fun termVersions(slug: String, origin: TermOrigin): Result<List<TermVersion>> =
        corpusResult { versionDao().forTerm(slug, origin).map { it.toDomain() } }

    /** Deja [uid] como la copia que se lee y se busca. */
    suspend fun activateVersion(uid: String): Result<Unit> =
        corpusResult { versionDao().activate(uid) }

    /**
     * Borra una copia y deja activa la que corresponda.
     *
     * Se decide **antes** de borrar, porque despues la fila ya no esta para saber si era la activa.
     * Borrar la ultima que quedaba devuelve el termino a su texto de base.
     */
    suspend fun deleteVersion(slug: String, origin: TermOrigin, uid: String): Result<Unit> =
        corpusResult {
            val versions = versionDao()
            val next = nextActiveAfterDeleting(versions.forTerm(slug, origin).map { it.toDomain() }, uid)
            versions.deleteByUid(listOf(uid))
            if (next != null) versions.activate(next)
        }

    /**
     * El texto que el termino tenia antes de actualizarse, guardado como una copia mas.
     *
     * Se fecha con lo que la fuente diga de el; si no dice nada -los terminos importados antes de
     * 10.1a no tienen fecha- se usa la de esta actualizacion, que al menos no inventa un dia
     * anterior al que podemos justificar.
     */
    private fun baseVersion(
        detail: TermDetail,
        sourceUrl: String,
        fallbackDate: String,
    ): TermVersionEntity {
        val known = detail.sources.firstOrNull { it.url == sourceUrl }?.retrievedAt
        return TermVersionEntity(
            uid = newVersionUid(),
            slug = detail.slug,
            origin = detail.origin,
            summary = detail.summary,
            content = detail.content,
            contentSha256 = personalContentSha256(detail.content),
            retrievedAt = known?.takeIf { it.isNotBlank() } ?: fallbackDate,
            sourceUrl = sourceUrl,
            isActive = false,
            createdAt = nowIso(),
        )
    }

    private suspend fun dropExcessVersions(slug: String, origin: TermOrigin) {
        val versions = versionDao()
        val excess = versionsToDrop(versions.forTerm(slug, origin).map { it.toDomain() })
        if (excess.isNotEmpty()) versions.deleteByUid(excess)
    }

    /**
     * Todos los terminos que la actualizacion masiva puede revisar.
     *
     * Un termino cuya URL no se puede volver a pedir -no es de Wikipedia, o no dice de que edicion
     * es- queda afuera aca y no como un fallo en el medio del recorrido.
     */
    suspend fun refreshCandidates(): Result<List<RefreshCandidate>> = corpusResult {
        val fromPackage = termDao().refreshableTerms().mapNotNull { it.toCandidate(TermOrigin.PACKAGE) }
        val fromPersonal = userTermDao().refreshableTerms().mapNotNull { it.toCandidate(TermOrigin.PERSONAL) }
        fromPackage + fromPersonal
    }

    /**
     * Recorre los candidatos pidiendolos de a lotes y guardando solo lo que cambio.
     *
     * La red entra por [fetchBatch], igual que en la actualizacion de a uno: aca se decide y se
     * guarda. Cancelar es cancelar la corrutina, y [onProgress] se llama despues de cada termino
     * para que la pantalla pueda mostrar por donde va.
     *
     * [startAt] permite retomar, pero es una **optimizacion y no una condicion**: como no se
     * escribe nada para un termino que no cambio, volver a empezar desde cero es correcto, solo
     * mas lento. Eso es lo que hace que perder el cursor -porque el sistema mato el proceso- no
     * rompa nada.
     */
    suspend fun refreshAll(
        candidates: List<RefreshCandidate>,
        resumeFrom: BulkRefreshProgress = BulkRefreshProgress(),
        now: () -> String = { nowIso() },
        fetchBatch: suspend (RefreshBatch) -> Map<String, KnowledgeArticle>,
        onProgress: suspend (BulkRefreshProgress) -> Unit = {},
    ): Result<BulkRefreshProgress> = corpusResult {
        val ordered = planRefreshBatches(candidates).flatMap { it.candidates }
        val startAt = resumeFrom.processed
        // Los contadores vienen de la pasada anterior y siguen sumando: un barrido cortado y
        // retomado es un solo barrido, y decir "206 revisados, 40 sin cambios" haria desaparecer
        // los 126 de antes.
        var progress = resumeFrom.copy(total = ordered.size)
        if (startAt >= ordered.size) return@corpusResult progress

        for (batch in planRefreshBatches(ordered.drop(startAt))) {
            // `runCatching` no sirve aca: se traga la CancellationException y el recorrido seguiria
            // sin red, contando como fallidos todos los terminos que quedaban. Se vio en el
            // emulador -cortar a los 186 terminos reportaba 4.470 revisados- y por eso la
            // cancelacion se deja pasar y solo se absorbe el fallo de la fuente.
            val articles = try {
                fetchBatch(batch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                emptyMap()
            }
            for (candidate in batch.candidates) {
                val article = articles[candidate.externalId]
                progress = if (article == null) {
                    // El lote entero pudo fallar, o este articulo no vino. En los dos casos es un
                    // termino que no se pudo revisar, no un motivo para cortar el recorrido.
                    progress.copy(processed = progress.processed + 1, failed = progress.failed + 1)
                } else {
                    val outcome = storeRefreshedCopy(
                        slug = candidate.slug,
                        summary = article.summary,
                        content = article.content,
                        sourceUrl = article.sourceUrl,
                        retrievedAt = now(),
                    ).getOrNull()
                    when (outcome) {
                        is TermRefresh.Updated ->
                            progress.copy(processed = progress.processed + 1, updated = progress.updated + 1)
                        is TermRefresh.Unchanged ->
                            progress.copy(processed = progress.processed + 1, unchanged = progress.unchanged + 1)
                        null ->
                            progress.copy(processed = progress.processed + 1, failed = progress.failed + 1)
                    }
                }
                onProgress(progress)
            }
        }
        progress
    }

    // endregion

    suspend fun deletePersonalTerm(slug: String): Result<Unit> = corpusResult {
        journaling { database, recorder ->
            val now = nowIso()
            val term = database.userTermDao().getBySlug(slug)
                ?: throw CorpusError.PersonalTermNotFound(slug)
            // Se enumeran los dependientes antes de apagarlos, porque despues ya no se distinguen
            // de los que estaban ausentes desde antes y no corresponde volver a anunciarlos.
            val dependents = dependentsOf(database, slug)

            database.userTermDao().deleteBySlug(slug)
            database.favoriteDao().remove(slug, TermOrigin.PERSONAL, now)
            database.historyDao().deleteByTerm(slug, TermOrigin.PERSONAL, now)
            database.collectionDao().removeTermEverywhere(slug, TermOrigin.PERSONAL, now)

            recorder.termDeleted(term.uid, slug, term.revision + 1, now, dependents)
        }
    }

    /**
     * Lo que se apaga al borrar un termino personal: su favorito, su historial y cada pertenencia.
     *
     * Es la misma cascada que `_dependent_deletes` deriva en el hub. Cada uno viaja como un cambio
     * propio para que las dos puntas apliquen exactamente lo mismo, en vez de que una deduzca de
     * un borrado a secas lo que la otra escribio fila por fila.
     */
    private suspend fun dependentsOf(
        database: LexidexUserDatabase,
        slug: String,
    ): List<DependentDelete> = buildList {
        database.favoriteDao().row(slug, TermOrigin.PERSONAL)
            ?.takeIf { it.isPresent }
            ?.let {
                add(
                    DependentDelete(
                        SyncChangeRecorder.ENTITY_FAVORITE,
                        SyncChangeRecorder.referenceIdentity(TermOrigin.PERSONAL, slug),
                        it.revision + 1,
                    ),
                )
            }
        database.historyDao().row(slug, TermOrigin.PERSONAL)
            ?.takeIf { it.isPresent }
            ?.let {
                add(
                    DependentDelete(
                        SyncChangeRecorder.ENTITY_HISTORY,
                        SyncChangeRecorder.referenceIdentity(TermOrigin.PERSONAL, slug),
                        it.revision + 1,
                    ),
                )
            }
        database.collectionDao().membershipsOf(slug, TermOrigin.PERSONAL).forEach { member ->
            add(
                DependentDelete(
                    SyncChangeRecorder.ENTITY_MEMBER,
                    SyncChangeRecorder.memberIdentity(
                        member.collectionUid,
                        TermOrigin.PERSONAL,
                        slug,
                    ),
                    member.revision + 1,
                ),
            )
        }
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
        journaling { database, recorder ->
            val dao = database.favoriteDao()
            val now = nowIso()
            val current = dao.row(slug, origin)
            val present = current?.isPresent != true
            if (present) dao.add(slug, origin, now) else dao.remove(slug, origin, now)
            recorder.favoriteChanged(slug, origin, present, (current?.revision ?: 0) + 1, now)
            present
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
        journaling { database, recorder ->
            database.collectionDao().insert(
                CollectionEntity(
                    uid = uid,
                    name = clean,
                    normalizedName = normalized,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            recorder.collectionUpserted(uid, clean, now, now, revision = 1)
        }
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
        journaling { database, recorder ->
            val dao = database.collectionDao()
            val current = dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
            val now = nowIso()
            dao.rename(uid, clean, normalized, now)
            recorder.collectionUpserted(uid, clean, current.createdAt, now, current.revision + 1)
        }
    }

    suspend fun deleteCollection(uid: String): Result<Unit> = corpusResult {
        journaling { database, recorder ->
            val dao = database.collectionDao()
            val collection = dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
            // Los miembros se leen antes del DELETE: el ON DELETE CASCADE se los lleva, y una vez
            // que no estan ya no hay como anunciarlos.
            val members = dao.members(uid).map { member ->
                DependentDelete(
                    SyncChangeRecorder.ENTITY_MEMBER,
                    SyncChangeRecorder.memberIdentity(uid, member.termOrigin, member.termSlug),
                    member.revision + 1,
                )
            }
            val now = nowIso()
            dao.deleteByUid(uid)
            recorder.collectionDeleted(uid, collection.revision + 1, now, members)
        }
    }

    /**
     * Los miembros que ya no se pueden resolver se omiten en vez de romper la coleccion: un
     * termino personal pudo borrarse, o un paquete nuevo puede no traer mas uno del paquete.
     */
    suspend fun getCollection(uid: String): Result<TermCollectionDetail> = corpusResult {
        val dao = collectionDao()
        val collection = dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
        val terms = dao.members(collection.uid)
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
        journaling { database, recorder ->
            val dao = database.collectionDao()
            dao.findByUid(uid) ?: throw CorpusError.CollectionNotFound(uid)
            val current = dao.memberRow(uid, slug, origin)
            if ((current?.isPresent == true) != member) {
                val now = nowIso()
                if (member) {
                    dao.addMember(uid, slug, origin, now)
                } else {
                    dao.removeMember(uid, slug, origin, now)
                }
                // La coleccion ya no se toca al cambiar un miembro. Su `revision` es el token con
                // el que se resuelve un conflicto de renombre, y subirla por algo que el otro lado
                // no sube dejaria a las dos replicas discutiendo por un cambio que nadie hizo.
                recorder.memberChanged(uid, slug, origin, member, (current?.revision ?: 0) + 1, now)
            }
        }
    }

    // endregion

    // region History

    suspend fun recordHistoryView(slug: String, origin: TermOrigin): Result<Unit> = corpusResult {
        journaling { database, recorder ->
            val now = nowIso()
            val current = database.historyDao().row(slug, origin)
            database.historyDao().record(slug, origin, now)
            recorder.historyChanged(slug, origin, true, (current?.revision ?: 0) + 1, now)
        }
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
    private suspend fun versionDao() = userDatabaseProvider.get().termVersionDao()

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
    private suspend fun buildPersonalDetail(term: UserTermEntity): TermDetail = TermDetail(
        slug = term.slug,
        title = term.title,
        language = term.language,
        kind = term.kind,
        status = term.status,
        summary = term.summary,
        content = term.content,
        categories = term.categories,
        tags = term.tags,
        sources = userDatabaseProvider.get().personalTermSourceDao().forTerm(term.uid).map { it.toDomain() },
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
        } catch (e: InvalidPersonalCatalogBackupException) {
            Result.failure(CorpusError.InvalidBackup(e.message ?: "El respaldo no es valido.", e))
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

/** "package"/"personal", los mismos valores que guarda la base y que usa la API. */
private fun TermOrigin.wireValue(): String = when (this) {
    TermOrigin.PACKAGE -> "package"
    TermOrigin.PERSONAL -> "personal"
}

private fun String.toTermOrigin(): TermOrigin = when (this) {
    "package" -> TermOrigin.PACKAGE
    "personal" -> TermOrigin.PERSONAL
    else -> error("Origen de termino no validado: $this")
}

private fun List<BackupTermRef>.filterToPackageSlugs(destination: MutableSet<String>) {
    asSequence().filter { it.origin == "package" }.mapTo(destination) { it.slug }
}

private fun BackupTerm.toEntity(id: Long = 0) = UserTermEntity(
    id = id,
    uid = uid,
    slug = slug,
    title = title,
    normalizedTitle = normalizedKey(title),
    language = language,
    kind = kind,
    status = status,
    summary = summary,
    content = content,
    sourceUrl = sourceUrl,
    categories = categories,
    tags = tags,
    notes = notes,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun BackupTermSource.toEntity(termUid: String, position: Int) = PersonalTermSourceEntity(
    uid = uid,
    termUid = termUid,
    position = position,
    providerId = providerId,
    sourceKind = kind,
    title = title,
    url = url,
    language = language,
    licenseName = licenseName,
    retrievedAt = retrievedAt,
    contentSha256 = contentSha256,
)

private fun PersonalTermSourceEntity.toBackupSource() = BackupTermSource(
    uid = uid,
    providerId = providerId,
    kind = sourceKind,
    title = title,
    url = url,
    language = language,
    licenseName = licenseName,
    retrievedAt = retrievedAt,
    contentSha256 = contentSha256,
)

/**
 * Marca la fuente primaria con el hash del texto que trajo, y se lo saca cuando el texto dejo de
 * ser el suyo. Sin el segundo caso, un termino importado y despues reescrito seguiria diciendo
 * que su contenido es de la fuente.
 */
/** Null cuando la URL guardada no se puede volver a pedir; ese termino no es candidato. */
private fun RefreshableTermRow.toCandidate(origin: TermOrigin): RefreshCandidate? {
    val result = wikipediaResultFromUrl(sourceUrl) ?: return null
    return RefreshCandidate(
        slug = slug,
        origin = origin,
        sourceUrl = sourceUrl,
        externalId = result.externalId,
        language = result.language,
    )
}

private fun TermVersionEntity.toDomain() = TermVersion(
    uid = uid,
    slug = slug,
    origin = origin,
    summary = summary,
    content = content,
    contentSha256 = contentSha256,
    retrievedAt = retrievedAt,
    sourceUrl = sourceUrl,
    isActive = isActive,
)

private fun PersonalTermSourceEntity.toDomain() = TermSource(
    kind = sourceKind,
    url = url,
    host = runCatching { URI(url).host.orEmpty() }.getOrDefault(""),
    language = language,
    licenseName = licenseName,
    retrievedAt = retrievedAt,
    contentSha256 = contentSha256,
)

private fun BackupCollection.toEntity() = CollectionEntity(
    uid = uid,
    name = name,
    normalizedName = normalizedKey(name),
    createdAt = createdAt,
    updatedAt = updatedAt,
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
    contentSha256 = contentSha256,
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
