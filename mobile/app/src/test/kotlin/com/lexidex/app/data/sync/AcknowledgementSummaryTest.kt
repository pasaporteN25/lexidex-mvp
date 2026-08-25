package com.lexidex.app.data.sync

import com.lexidex.app.domain.sync.SyncAcknowledgement
import com.lexidex.app.domain.sync.SyncProblem
import com.lexidex.app.ui.options.outcomeMessage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun applied(id: String) =
    SyncAcknowledgement(changeId = id, status = "applied", revision = 1, cursor = "1")

private fun duplicate(id: String) =
    SyncAcknowledgement(changeId = id, status = "duplicate", revision = 1, cursor = "1")

private fun conflict(id: String, code: String) = SyncAcknowledgement(
    changeId = id,
    status = "conflict",
    problem = SyncProblem(code = code, message = "no se aplico", details = JsonObject(emptyMap())),
)

private fun rejected(id: String, code: String) = SyncAcknowledgement(
    changeId = id,
    status = "rejected",
    problem = SyncProblem(code = code, message = "no se aplico", details = JsonObject(emptyMap())),
)

class AcknowledgementSummaryTest {

    @Test
    fun `a duplicate counts as accepted, because the hub already has it`() {
        val summary = summarizeAcknowledgements(
            listOf(applied("chg_a"), duplicate("chg_b")),
            emptyMap(),
        )

        assertEquals(2, summary.accepted)
        assertTrue(summary.refused.isEmpty())
    }

    @Test
    fun `everything the hub evaluated leaves the outbox, refusals included`() {
        val acknowledgements = listOf(
            applied("chg_a"),
            conflict("chg_b", "stale_revision"),
            rejected("chg_c", "parent_deleted"),
        )

        val summary = summarizeAcknowledgements(acknowledgements, emptyMap())

        // Un cambio en conflicto no mejora reintentandolo: su base_revision quedo vieja para
        // siempre. Si no saliera de la bandeja, chocaria en cada intercambio y no avanzaria nunca.
        assertEquals(listOf("chg_a", "chg_b", "chg_c"), summary.evaluated)
        assertEquals(1, summary.accepted)
        assertEquals(listOf("stale_revision", "parent_deleted"), summary.refused.map { it.code })
    }

    @Test
    fun `a refusal carries the entity type so the screen can name what failed`() {
        val summary = summarizeAcknowledgements(
            listOf(conflict("chg_b", "identity_conflict")),
            mapOf("chg_b" to "personal_term"),
        )

        assertEquals("personal_term", summary.refused.single().entityType)
    }

    @Test
    fun `an unacknowledged change stays in the outbox`() {
        // El contrato permite que el hub no evalue alguna mutacion. Esa tiene que sobrevivir para
        // volver a salir en el proximo intercambio.
        val summary = summarizeAcknowledgements(listOf(applied("chg_a")), emptyMap())

        assertEquals(listOf("chg_a"), summary.evaluated)
    }
}

class OutcomeMessageTest {

    @Test
    fun `says nothing moved instead of pretending something happened`() {
        assertEquals("Ya estaba todo al dia.", outcomeMessage(SyncOutcome()))
    }

    @Test
    fun `counts what went and what came`() {
        val outcome = SyncOutcome(sent = 3, accepted = 3, received = 2)

        assertEquals("3 enviados, 2 recibidos", outcomeMessage(outcome))
    }

    @Test
    fun `names the ones that could not be applied`() {
        val outcome = SyncOutcome(
            sent = 2,
            accepted = 1,
            refused = listOf(RefusedChange("chg_b", "personal_term", "stale_revision", "")),
        )

        assertEquals("1 enviados, 1 no se pudieron aplicar", outcomeMessage(outcome))
    }
}
