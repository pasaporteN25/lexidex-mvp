package com.lexidex.app.domain.sync

import com.lexidex.app.data.sync.requestJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * El protocolo v2 (10.10b), leido de las **mismas** fixtures que `tests/test_local_sync_contract_v2.py`.
 *
 * Lo que mas importa no es que v2 acepte lo nuevo sino que v1 siga rechazando exactamente lo mismo
 * que antes, y que el telefono nunca escriba en un pedido v1 algo que un hub viejo no conoce.
 */
class LocalSyncContractV2Test {

    @Test
    fun `v2 carries copies and the active choice`() {
        val request = parseSyncExchangeRequest(fixture("exchange-request.valid.json"))

        assertEquals(2, request.version)
        assertEquals(
            listOf("term_version", "term_active", "term_version", "favorite"),
            request.changes.map { it.entityType },
        )
        assertEquals(64, request.changes.first().entityId.contentSha256?.length)
    }

    @Test
    fun `v2 responses carry them too`() {
        val response = parseSyncExchangeResponse(fixture("exchange-response.valid.json"))

        assertEquals(listOf("term_version", "term_active"), response.changes.map { it.entityType })
    }

    @Test
    fun `a v1 document still cannot name a copy`() {
        assertEquals("invalid_change", rejected("exchange-request.invalid-v1-with-term-version.json"))
    }

    @Test
    fun `a v1 identity still rejects content_sha256 even null`() {
        // Kotlin decodifica a data classes, donde ausente y null quedan iguales; Python los distingue.
        // Si este test fallara, los dos lectores aceptarian documentos distintos.
        assertEquals("invalid_change", rejected("exchange-request.invalid-v1-identity-field.json"))
    }

    @Test
    fun `a copy whose text does not match its identity is rejected`() {
        assertEquals("invalid_change", rejected("exchange-request.invalid-hash-mismatch.json"))
    }

    @Test
    fun `an unknown version is unsupported, which is what triggers the fall back`() {
        assertEquals("unsupported_version", rejected("exchange-request.invalid-unsupported-version.json"))
    }

    @Test
    fun `the phone never writes content_sha256 into a v1 request`() {
        // La trampa que motivo @EncodeDefault(NEVER). El cliente codifica con encodeDefaults, que
        // escribe los nulls; sin la anotacion, un telefono actualizado mandaria
        // `"content_sha256": null` en cada identidad, y un hub viejo lo rechazaria por clave
        // desconocida. Actualizar el telefono lo dejaria sin sincronizar.
        val favorite = SyncClientChange(
            changeId = "chg_" + "a".repeat(32),
            deviceId = "dev_" + "1".repeat(32),
            entityType = "favorite",
            entityId = SyncEntityId(origin = "package", slug = "poligenismo"),
            operation = "upsert",
            baseRevision = 0,
            payloadVersion = 1,
            changedAt = "2026-09-08T03:21:00Z",
            payload = JsonObject(mapOf("at" to JsonPrimitive("2026-09-08T03:21:00Z"))),
        )
        val request = SyncExchangeRequest(
            protocol = SYNC_PROTOCOL_NAME,
            version = SYNC_PROTOCOL_VERSION,
            requestId = "req_" + "0".repeat(32),
            deviceId = "dev_" + "1".repeat(32),
            packageDescriptor = SyncPackageDescriptor("lexidex-core", "0.5.1-licensed.1"),
            sinceCursor = "0",
            limit = 100,
            changes = listOf(favorite),
        )

        val encoded = requestJson.encodeToString(request)

        assertFalse("se colo content_sha256 en un pedido v1: $encoded", "content_sha256" in encoded)
        // Y lo que se escribio lo tiene que aceptar el propio lector estricto.
        parseSyncExchangeRequest(encoded)
    }

    @Test
    fun `a copy identity does get its hash written`() {
        val identity = SyncEntityId(origin = "package", slug = "poligenismo", contentSha256 = "f".repeat(64))

        val encoded = requestJson.encodeToString(identity)

        assertTrue(encoded, "\"content_sha256\":\"" + "f".repeat(64) + "\"" in encoded)
    }

    @Test
    fun `extent must be intro or full`() {
        assertEquals("invalid_change", rejectedEdit { payload -> payload + ("extent" to JsonPrimitive("PARTIAL")) })
    }

    @Test
    fun `revision_id may be null but not zero, a bool or a string`() {
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-5), JsonPrimitive(true), JsonPrimitive("175190592"))) {
            assertEquals(
                "aceptado: $bad",
                "invalid_change",
                rejectedEdit { payload -> payload + ("revision_id" to bad) },
            )
        }
    }

    private fun rejected(name: String): String = try {
        parseSyncExchangeRequest(fixture(name))
        fail("$name deberia haberse rechazado")
        ""
    } catch (error: InvalidSyncContractException) {
        error.code
    }

    /** Toma la primera copia de la fixture valida, le cambia el payload y la vuelve a leer. */
    private fun rejectedEdit(edit: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>): String {
        val document = Json.parseToJsonElement(fixture("exchange-request.valid.json")).jsonObject
        val first = document["changes"]!!.jsonArray.first().jsonObject
        val payload = first["payload"]!!.jsonObject
        val change = JsonObject(first + ("payload" to JsonObject(edit(payload))))
        val text = JsonObject(document + ("changes" to kotlinx.serialization.json.JsonArray(listOf(change)))).toString()
        return try {
            parseSyncExchangeRequest(text)
            fail("deberia haberse rechazado")
            ""
        } catch (error: InvalidSyncContractException) {
            error.code
        }
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/local-sync/v2/fixtures/$name"),
    ) { "falta la fixture compartida $name" }.readText()
}
