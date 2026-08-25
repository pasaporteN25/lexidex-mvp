package com.lexidex.app.data.repository

import com.lexidex.app.domain.backup.BACKUP_FORMAT_NAME
import com.lexidex.app.domain.backup.BackupCollection
import com.lexidex.app.domain.backup.BackupTerm
import com.lexidex.app.domain.backup.BackupTermRef
import com.lexidex.app.domain.backup.PersonalCatalogBackup
import com.lexidex.app.domain.backup.toJson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersonalCatalogImportTest {

    @Test
    fun `the version one fixture stays importable`() {
        val text = requireNotNull(javaClass.getResource("/backup/personal-catalog-v1.json"))
            .readText()

        val backup = validatedPersonalCatalogBackupFromJson(text)
        val plan = planPersonalCatalogImport(backup, emptyBackup())

        assertEquals(1, plan.summary.termsAdded)
        assertEquals(1, plan.summary.collectionsAdded)
        assertEquals(2, plan.summary.membersAdded)
        assertEquals(2, plan.summary.pendingPackageReferences)
    }

    @Test
    fun `a backup with the wrong identity or a future version is rejected`() {
        assertInvalid("no es un respaldo de Lexidex") {
            validatedPersonalCatalogBackupFromJson(
                validBackup().toJson().replace(BACKUP_FORMAT_NAME, "something-else"),
            )
        }
        assertInvalid("version 99") {
            validatedPersonalCatalogBackupFromJson(
                validBackup().copy(version = 99).toJson(),
            )
        }
    }

    @Test
    fun `invalid terms and forged personal slugs reject the whole file`() {
        val invalidLanguage = validBackup().copy(
            terms = listOf(term(language = "not a language")),
        )
        assertInvalid("idioma") {
            validatedPersonalCatalogBackupFromJson(invalidLanguage.toJson())
        }

        val forgedSlug = validBackup().copy(
            terms = listOf(term(slug = "personal-es-tango--ffffffff")),
        )
        assertInvalid("slug") {
            validatedPersonalCatalogBackupFromJson(forgedSlug.toJson())
        }
    }

    @Test
    fun `newer revisions update while title collisions preserve the local term`() {
        val local = term(revision = 2, summary = "Local")
        val update = local.copy(revision = 3, summary = "Desde el respaldo")
        val newTerm = term(
            uid = UID_B,
            slug = "personal-en-mate--bbbbbbbb",
            title = "Mate",
            language = "en",
        )
        val collision = term(
            uid = UID_C,
            slug = "personal-es-tango--cccccccc",
            title = " TANGO ",
        )

        val plan = planPersonalCatalogImport(
            incoming = validBackup().copy(terms = listOf(update, newTerm, collision)),
            current = validBackup().copy(terms = listOf(local)),
        )

        assertEquals(listOf(newTerm), plan.termsToAdd)
        assertEquals(listOf(update), plan.termsToUpdate)
        assertEquals(1, plan.summary.skippedConflicts)
        assertEquals(2, plan.summary.totalChanges)
    }

    @Test
    fun `dangling personal references are omitted but missing package references stay pending`() {
        val incoming = validBackup().copy(
            favorites = listOf(
                BackupTermRef("personal-es-borrado--dddddddd", "personal", LATER),
                BackupTermRef("es-paquete-ausente", "package", LATER),
            ),
            history = listOf(
                BackupTermRef("personal-es-tango--aaaaaaaa", "personal", LATER),
            ),
        )

        val plan = planPersonalCatalogImport(
            incoming = incoming,
            current = emptyBackup(),
            installedPackage = InstalledPackageSnapshot(slugs = emptySet()),
        )

        assertEquals(
            listOf(BackupTermRef("es-paquete-ausente", "package", LATER)),
            plan.favoritesToAdd,
        )
        assertEquals(1, plan.historyToAdd.size)
        assertEquals(1, plan.summary.omittedPersonalReferences)
        assertEquals(1, plan.summary.pendingPackageReferences)
    }

    @Test
    fun `history only imports a newer visit and importing twice is idempotent`() {
        val older = BackupTermRef("personal-es-tango--aaaaaaaa", "personal", EARLIER)
        val newer = older.copy(at = LATER)
        val incoming = validBackup().copy(history = listOf(newer))
        val local = validBackup().copy(history = listOf(older))

        val firstPlan = planPersonalCatalogImport(incoming, local)
        assertEquals(listOf(newer), firstPlan.historyToAdd)

        val alreadyImported = local.copy(history = listOf(newer))
        val secondPlan = planPersonalCatalogImport(incoming, alreadyImported)
        assertTrue(secondPlan.hasNoChanges)
        assertEquals(0, secondPlan.summary.totalChanges)
    }

    @Test
    fun `collections merge by uid and keep members without duplicating them`() {
        val existingMember = BackupTermRef("personal-es-tango--aaaaaaaa", "personal", EARLIER)
        val packageMember = BackupTermRef("es-paquete", "package", LATER)
        val currentCollection = BackupCollection(
            uid = COLLECTION_UID,
            name = "Lecturas",
            createdAt = EARLIER,
            updatedAt = EARLIER,
            members = listOf(existingMember),
        )
        val incomingCollection = currentCollection.copy(
            name = "Para leer",
            updatedAt = LATER,
            members = listOf(existingMember, packageMember),
        )

        val plan = planPersonalCatalogImport(
            incoming = validBackup().copy(collections = listOf(incomingCollection)),
            current = validBackup().copy(collections = listOf(currentCollection)),
            installedPackage = InstalledPackageSnapshot(slugs = setOf("es-paquete")),
        )

        assertEquals(listOf(incomingCollection.copy(members = emptyList())), plan.collectionsToUpdate)
        assertEquals(
            listOf(PlannedCollectionMember(COLLECTION_UID, packageMember)),
            plan.membersToAdd,
        )
        assertEquals(2, plan.summary.totalChanges)
    }

    private fun validBackup() = PersonalCatalogBackup(
        exportedAt = EARLIER,
        terms = listOf(term()),
    )

    private fun emptyBackup() = PersonalCatalogBackup(exportedAt = EARLIER)

    private fun term(
        uid: String = UID_A,
        slug: String = "personal-es-tango--aaaaaaaa",
        title: String = "Tango",
        language: String = "es",
        revision: Long = 1,
        summary: String = "",
    ) = BackupTerm(
        uid = uid,
        slug = slug,
        title = title,
        language = language,
        kind = "reference",
        status = "seed",
        summary = summary,
        createdAt = EARLIER,
        updatedAt = LATER,
        revision = revision,
    )

    private fun assertInvalid(expectedMessagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected InvalidPersonalCatalogBackupException")
        } catch (error: InvalidPersonalCatalogBackupException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expectedMessagePart))
        }
    }

    private companion object {
        const val UID_A = "usr_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val UID_B = "usr_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val UID_C = "usr_cccccccccccccccccccccccccccccccc"
        const val COLLECTION_UID = "col_11111111111111111111111111111111"
        const val EARLIER = "2026-08-20T12:00:00Z"
        const val LATER = "2026-08-21T12:00:00Z"
    }
}
